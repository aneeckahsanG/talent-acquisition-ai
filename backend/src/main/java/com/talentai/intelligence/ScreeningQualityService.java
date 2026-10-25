package com.talentai.intelligence;

import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.common.entity.AppUser;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.screening.entity.ScreeningResult;
import com.talentai.screening.repository.ScreeningResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * How consistent and trustworthy screening is: how often recruiters agree with the AI,
 * whether the score means anything, and whether recruiters decide alike.
 *
 * A "human decision" is a reviewer decision on the Screening page, or a recruiter moving the
 * candidate on or rejecting them on the Pipeline board. Automatic moves are not counted.
 */
@Service
@RequiredArgsConstructor
public class ScreeningQualityService {

    private static final Set<String> SHORTLIST_OR_LATER = Set.of("SHORTLISTED", "INTERVIEW_SCHEDULED", "OFFER", "HIRED");
    /** Below this many human decisions the figures are only indicative. */
    static final int MIN_RELIABLE_DECISIONS = 10;

    private final ScreeningResultRepository screeningRepository;
    private final PipelineStageRepository stageRepository;
    private final InterviewScheduleRepository interviewRepository;
    private final JobRequisitionRepository requisitionRepository;
    private final AppUserRepository userRepository;

    public record Quality(LocalDateTime generatedAt, Totals totals, Agreement agreement, List<MatrixRow> matrix,
                          List<ScoreBand> scoreBands, ScoreSeparation scoreSeparation, List<ReviewerStat> reviewers,
                          List<RoleStat> roles, List<Outcome> outcomes, List<Insight> insights, String confidence) {}

    public record Totals(int screened, int aiAdvance, int aiReject, int aiReview, int humanDecided, int awaitingReview) {}

    public record Agreement(Double rate, int agreed, int compared, int aiAdvanceOverturned, int aiRejectOverturned,
                            int aiRejectsWithHumanDecision, Double aiRejectOverturnRate) {}

    public record MatrixRow(String aiRecommendation, int humanAdvance, int humanReject, int noHumanDecision) {}

    public record ScoreBand(String band, int decided, int humanAdvanced, Double humanAdvanceRate) {}

    public record ScoreSeparation(Double avgScoreHumanAdvance, Double avgScoreHumanReject, int advanceCount, int rejectCount) {}

    public record ReviewerStat(String reviewer, int decisions, Double advanceRate, int comparedWithAi, Double agreementWithAi) {}

    public record RoleStat(Long requisitionId, String title, int screened, Double avgScore, Double aiAdvanceRate,
                           int humanDecided, Double agreementRate) {}

    public record Outcome(String aiRecommendation, int results, int shortlisted, int interviewed, int hired) {}

    public record Insight(String severity, String title, String detail) {}

    private record Row(ScreeningResult result, String ai, String human, boolean fromReviewer) {}

    public Quality overview() {
        List<ScreeningResult> results = screeningRepository.findAll();
        Map<String, PipelineStage> stageByKey = new HashMap<>();
        stageRepository.findAll().forEach(s -> stageByKey.put(key(s.getCandidateId(), s.getRequisitionId()), s));
        Set<String> interviewKeys = interviewRepository.findAll().stream()
                .map(i -> key(i.getCandidateId(), i.getRequisitionId())).collect(Collectors.toSet());
        Map<Long, JobRequisition> reqById = requisitionRepository.findAll().stream().collect(Collectors.toMap(JobRequisition::getId, r -> r));
        Map<Long, String> userNames = userRepository.findAll().stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));

        List<Row> rows = new ArrayList<>();
        for (ScreeningResult r : results) {
            String ai = norm(r.getRecommendation());
            String human = null;
            boolean fromReviewer = false;
            if (r.getReviewerDecision() != null) {
                human = norm(r.getReviewerDecision());
                fromReviewer = true;
            } else {
                PipelineStage st = stageByKey.get(key(r.getCandidateId(), r.getRequisitionId()));
                if (st != null && "HUMAN".equals(st.getUpdatedByAgent())) {
                    if (SHORTLIST_OR_LATER.contains(st.getStage())) human = "ADVANCE";
                    else if ("REJECTED".equals(st.getStage())) human = "REJECT";
                }
            }
            rows.add(new Row(r, ai, human, fromReviewer));
        }

        // ---- totals and agreement
        int humanDecided = (int) rows.stream().filter(r -> r.human() != null).count();
        int awaiting = (int) rows.stream().filter(r -> "REVIEW".equals(r.ai()) && r.human() == null).count();
        Totals totals = new Totals(rows.size(), count(rows, "ADVANCE"), count(rows, "REJECT"), count(rows, "REVIEW"), humanDecided, awaiting);

        List<Row> compared = rows.stream().filter(r -> r.human() != null && !"REVIEW".equals(r.ai())).toList();
        int agreed = (int) compared.stream().filter(r -> r.ai().equals(r.human())).count();
        int advOverturned = (int) compared.stream().filter(r -> "ADVANCE".equals(r.ai()) && "REJECT".equals(r.human())).count();
        int rejOverturned = (int) compared.stream().filter(r -> "REJECT".equals(r.ai()) && "ADVANCE".equals(r.human())).count();
        int rejDecided = (int) compared.stream().filter(r -> "REJECT".equals(r.ai())).count();
        Agreement agreement = new Agreement(rate(agreed, compared.size()), agreed, compared.size(), advOverturned, rejOverturned,
                rejDecided, rate(rejOverturned, rejDecided));

        List<MatrixRow> matrix = new ArrayList<>();
        for (String ai : List.of("ADVANCE", "REJECT", "REVIEW")) {
            List<Row> of = rows.stream().filter(r -> ai.equals(r.ai())).toList();
            matrix.add(new MatrixRow(ai, (int) of.stream().filter(r -> "ADVANCE".equals(r.human())).count(),
                    (int) of.stream().filter(r -> "REJECT".equals(r.human())).count(),
                    (int) of.stream().filter(r -> r.human() == null).count()));
        }

        // ---- does the score mean anything?
        List<ScoreBand> bands = new ArrayList<>();
        double[][] ranges = {{0, 40}, {40, 60}, {60, 80}, {80, 100.01}};
        String[] names = {"Below 40", "40 to 59", "60 to 79", "80 and above"};
        for (int i = 0; i < ranges.length; i++) {
            final double lo = ranges[i][0], hi = ranges[i][1];
            List<Row> in = rows.stream().filter(r -> r.human() != null && r.result().getOverallScore() != null
                    && r.result().getOverallScore().doubleValue() >= lo && r.result().getOverallScore().doubleValue() < hi).toList();
            int adv = (int) in.stream().filter(r -> "ADVANCE".equals(r.human())).count();
            bands.add(new ScoreBand(names[i], in.size(), adv, rate(adv, in.size())));
        }
        List<Row> hAdv = rows.stream().filter(r -> "ADVANCE".equals(r.human()) && r.result().getOverallScore() != null).toList();
        List<Row> hRej = rows.stream().filter(r -> "REJECT".equals(r.human()) && r.result().getOverallScore() != null).toList();
        ScoreSeparation separation = new ScoreSeparation(avgScore(hAdv), avgScore(hRej), hAdv.size(), hRej.size());

        // ---- do recruiters decide alike? (only decisions made on the Screening page carry a reviewer)
        List<ReviewerStat> reviewers = new ArrayList<>();
        Map<Long, List<Row>> byReviewer = rows.stream().filter(r -> r.fromReviewer() && r.result().getReviewedBy() != null)
                .collect(Collectors.groupingBy(r -> r.result().getReviewedBy()));
        for (var e : byReviewer.entrySet()) {
            List<Row> mine = e.getValue();
            int adv = (int) mine.stream().filter(r -> "ADVANCE".equals(r.human())).count();
            List<Row> cmp = mine.stream().filter(r -> !"REVIEW".equals(r.ai())).toList();
            int ag = (int) cmp.stream().filter(r -> r.ai().equals(r.human())).count();
            reviewers.add(new ReviewerStat(userNames.getOrDefault(e.getKey(), "User " + e.getKey()), mine.size(),
                    rate(adv, mine.size()), cmp.size(), rate(ag, cmp.size())));
        }
        reviewers.sort(Comparator.comparingInt(ReviewerStat::decisions).reversed());

        // ---- per role
        List<RoleStat> roles = new ArrayList<>();
        for (var e : rows.stream().collect(Collectors.groupingBy(r -> r.result().getRequisitionId())).entrySet()) {
            List<Row> in = e.getValue();
            List<Row> cmp = in.stream().filter(r -> r.human() != null && !"REVIEW".equals(r.ai())).toList();
            int ag = (int) cmp.stream().filter(r -> r.ai().equals(r.human())).count();
            JobRequisition req = reqById.get(e.getKey());
            roles.add(new RoleStat(e.getKey(), req != null ? req.getTitle() : "Role " + e.getKey(), in.size(), avgScore(in),
                    rate(count(in, "ADVANCE"), in.size()), (int) in.stream().filter(r -> r.human() != null).count(), rate(ag, cmp.size())));
        }
        roles.sort(Comparator.comparingInt(RoleStat::screened).reversed());

        // ---- what happened to people the AI advanced or rejected
        List<Outcome> outcomes = new ArrayList<>();
        for (String ai : List.of("ADVANCE", "REVIEW", "REJECT")) {
            List<Row> of = rows.stream().filter(r -> ai.equals(r.ai())).toList();
            int shortlisted = 0, interviewed = 0, hired = 0;
            for (Row r : of) {
                String k = key(r.result().getCandidateId(), r.result().getRequisitionId());
                PipelineStage st = stageByKey.get(k);
                String stage = st != null ? st.getStage() : "";
                if (SHORTLIST_OR_LATER.contains(stage) || interviewKeys.contains(k)) shortlisted++;
                if (interviewKeys.contains(k) || Set.of("INTERVIEW_SCHEDULED", "OFFER", "HIRED").contains(stage)) interviewed++;
                if ("HIRED".equals(stage)) hired++;
            }
            outcomes.add(new Outcome(ai, of.size(), shortlisted, interviewed, hired));
        }

        String confidence = humanDecided == 0
                ? "No human decisions yet, so there is nothing to compare the AI against."
                : humanDecided < MIN_RELIABLE_DECISIONS
                ? "Based on only " + humanDecided + " human decision" + (humanDecided == 1 ? "" : "s") + ". Treat these figures as a rough guide until there are " + MIN_RELIABLE_DECISIONS + " or more."
                : "Based on " + humanDecided + " human decisions.";
        return new Quality(LocalDateTime.now(), totals, agreement, matrix, bands, separation, reviewers, roles, outcomes,
                insights(totals, agreement, separation, reviewers, humanDecided), confidence);
    }

    private List<Insight> insights(Totals t, Agreement a, ScoreSeparation sep, List<ReviewerStat> reviewers, int humanDecided) {
        List<Insight> out = new ArrayList<>();
        if (a.compared() >= MIN_RELIABLE_DECISIONS && a.rate() != null && a.rate() < 75) {
            out.add(new Insight("HIGH", "Recruiters disagree with the AI on " + Math.round(100 - a.rate()) + "% of decisions",
                    (a.compared() - a.agreed()) + " of " + a.compared() + " decisions went the other way. The screening criteria may need adjusting."));
        }
        if (a.aiRejectsWithHumanDecision() >= 5 && a.aiRejectOverturnRate() != null && a.aiRejectOverturnRate() >= 30) {
            out.add(new Insight("MEDIUM", "AI rejections are often overturned (" + Math.round(a.aiRejectOverturnRate()) + "%)",
                    "Good candidates may be getting rejected: the screening bar could be too strict."));
        }
        if (sep.advanceCount() >= 3 && sep.rejectCount() >= 3 && sep.avgScoreHumanAdvance() != null && sep.avgScoreHumanReject() != null
                && sep.avgScoreHumanAdvance() - sep.avgScoreHumanReject() < 10) {
            out.add(new Insight("MEDIUM", "The AI score barely separates who recruiters advance from who they reject",
                    "Average " + Math.round(sep.avgScoreHumanAdvance()) + " for advanced candidates against " + Math.round(sep.avgScoreHumanReject()) + " for rejected ones."));
        }
        List<ReviewerStat> enough = reviewers.stream().filter(r -> r.decisions() >= 5 && r.advanceRate() != null).toList();
        if (enough.size() >= 2) {
            double min = enough.stream().mapToDouble(ReviewerStat::advanceRate).min().orElse(0);
            double max = enough.stream().mapToDouble(ReviewerStat::advanceRate).max().orElse(0);
            if (max - min >= 30) {
                ReviewerStat hi = enough.stream().max(Comparator.comparingDouble(ReviewerStat::advanceRate)).get();
                ReviewerStat lo = enough.stream().min(Comparator.comparingDouble(ReviewerStat::advanceRate)).get();
                out.add(new Insight("MEDIUM", "Recruiters are not screening alike",
                        hi.reviewer() + " advances " + Math.round(hi.advanceRate()) + "% of candidates; " + lo.reviewer() + " advances " + Math.round(lo.advanceRate()) + "%."));
            }
        }
        if (t.screened() >= 10 && t.aiReview() * 100.0 / t.screened() > 40) {
            out.add(new Insight("LOW", "Many results are left for human review (" + Math.round(t.aiReview() * 100.0 / t.screened()) + "%)",
                    "The AI is not confident on a large share of candidates."));
        }
        if (t.awaitingReview() > 0) {
            out.add(new Insight("LOW", t.awaitingReview() + " result" + (t.awaitingReview() == 1 ? " is" : "s are") + " waiting for a recruiter's decision",
                    "These candidates are neither advanced nor rejected yet."));
        }
        out.sort(Comparator.comparingInt(i -> switch (i.severity()) { case "HIGH" -> 0; case "MEDIUM" -> 1; default -> 2; }));
        return out;
    }

    private static int count(List<Row> rows, String ai) {
        return (int) rows.stream().filter(r -> ai.equals(r.ai())).count();
    }

    private static Double avgScore(List<Row> rows) {
        OptionalDouble avg = rows.stream().filter(r -> r.result().getOverallScore() != null)
                .mapToDouble(r -> r.result().getOverallScore().doubleValue()).average();
        return avg.isPresent() ? Math.round(avg.getAsDouble() * 10) / 10.0 : null;
    }

    private static Double rate(int part, int whole) {
        return whole > 0 ? Math.round(1000.0 * part / whole) / 10.0 : null;
    }

    private static String norm(String v) {
        if (v == null) return "REVIEW";
        String u = v.trim().toUpperCase();
        return u.equals("ADVANCE") || u.equals("REJECT") ? u : "REVIEW";
    }

    private static String key(Long candidateId, Long requisitionId) {
        return candidateId + ":" + requisitionId;
    }
}
