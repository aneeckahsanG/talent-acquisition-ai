package com.talentai.intelligence;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.Offer;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.OfferRepository;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.screening.entity.ScreeningResult;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.sourcing.entity.SourcingMatch;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Talent market intelligence: pipeline depth per role, funnel conversion, channel effectiveness,
 * skills supply vs demand, time-to-fill and early-warning alerts, all derived from data already in the system.
 */
@Service
@RequiredArgsConstructor
public class IntelligenceService {

    /** Qualified candidates we want in the pipeline for each open role. */
    static final int TARGET_QUALIFIED_PER_ROLE = 3;
    /** A candidate sitting in one stage this long is flagged as stalled. */
    static final int STALLED_AFTER_DAYS = 7;
    /** A role open this long with no interviews at all is flagged. */
    static final int SLOW_ROLE_DAYS = 30;

    private static final Set<String> CLOSED_STAGES = Set.of("REJECTED", "NO_SHOW", "HIRED");
    private static final Set<String> SHORTLIST_OR_LATER = Set.of("SHORTLISTED", "INTERVIEW_SCHEDULED", "OFFER", "HIRED");

    private final JobRequisitionRepository requisitionRepository;
    private final CandidateRepository candidateRepository;
    private final PipelineStageRepository stageRepository;
    private final ScreeningResultRepository screeningRepository;
    private final SourcingMatchRepository matchRepository;
    private final InterviewScheduleRepository interviewRepository;
    private final OfferRepository offerRepository;

    // ---- response shapes -------------------------------------------------------------------

    public record Overview(LocalDateTime generatedAt, Kpis kpis, List<Alert> alerts, List<RoleCoverage> roleCoverage,
                           List<FunnelStep> funnel, List<SkillGap> skills, List<ChannelStat> channels,
                           List<Stalled> stalled, TimeToFill timeToFill) {}

    public record Kpis(int openRoles, int qualifiedCandidates, int candidatesInProcess, Double avgDaysToFill,
                       Double offerAcceptanceRate, int hires) {}

    public record Alert(String severity, String title, String detail) {}

    public record RoleCoverage(Long requisitionId, String title, String department, int daysOpen, int applicants,
                               int qualified, int interviewing, int offers, int hired, String status,
                               String expectedFillDate) {}

    public record FunnelStep(String step, int count, Double conversionFromPrevious) {}

    public record SkillGap(String skill, int openRolesNeeding, int candidatesWithSkill, String status) {}

    public record ChannelStat(String channel, int candidates, int advanced, int hired, Double avgScore,
                              Double advanceRate, Double hireRate) {}

    /** What needs a person's action today, plus the most urgent early warnings. */
    public record Attention(int edgeCasesToReview, int offersAwaitingReply, int interviewsToday, int waitingToBook,
                            int applicantsWaitingScreening, List<Alert> topWarnings) {}

    public record Stalled(Long candidateId, String candidateName, Long requisitionId, String roleTitle, String stage,
                          int daysInStage) {}

    public record TimeToFill(int sampleSize, Double averageDays, String note) {}

    // ---- main entry point --------------------------------------------------------------------

    public Overview overview() {
        LocalDateTime now = LocalDateTime.now();
        List<JobRequisition> requisitions = requisitionRepository.findAll();
        Map<Long, JobRequisition> reqById = requisitions.stream().collect(Collectors.toMap(JobRequisition::getId, r -> r));
        List<JobRequisition> openRoles = requisitions.stream().filter(r -> "OPEN".equals(r.getStatus())).toList();

        Map<Long, Candidate> candById = candidateRepository.findAll().stream().collect(Collectors.toMap(Candidate::getId, c -> c));
        List<PipelineStage> stages = stageRepository.findAll();
        List<ScreeningResult> screenings = screeningRepository.findAll();
        List<SourcingMatch> matches = matchRepository.findAll();
        List<InterviewSchedule> interviews = interviewRepository.findAll();
        List<Offer> offers = offerRepository.findAll();

        Map<String, PipelineStage> stageByKey = new HashMap<>();
        stages.forEach(s -> stageByKey.put(key(s.getCandidateId(), s.getRequisitionId()), s));
        Map<String, ScreeningResult> screeningByKey = new HashMap<>();
        screenings.forEach(s -> screeningByKey.put(key(s.getCandidateId(), s.getRequisitionId()), s));

        // ---- funnel (each step includes everyone who got further, so counts only shrink)
        Set<String> hired = new HashSet<>();
        stages.stream().filter(s -> "HIRED".equals(s.getStage())).forEach(s -> hired.add(key(s.getCandidateId(), s.getRequisitionId())));
        offers.stream().filter(o -> "ACCEPTED".equals(o.getStatus())).forEach(o -> hired.add(key(o.getCandidateId(), o.getRequisitionId())));
        Set<String> offered = new HashSet<>(hired);
        offers.forEach(o -> offered.add(key(o.getCandidateId(), o.getRequisitionId())));
        Set<String> interviewed = new HashSet<>(offered);
        interviews.forEach(i -> interviewed.add(key(i.getCandidateId(), i.getRequisitionId())));
        Set<String> shortlisted = new HashSet<>(interviewed);
        stages.stream().filter(s -> SHORTLIST_OR_LATER.contains(s.getStage()))
                .forEach(s -> shortlisted.add(key(s.getCandidateId(), s.getRequisitionId())));
        Set<String> advanced = new HashSet<>(shortlisted);
        screenings.stream().filter(this::advancedByScreening).forEach(s -> advanced.add(key(s.getCandidateId(), s.getRequisitionId())));
        Set<String> screened = new HashSet<>(advanced);
        screenings.forEach(s -> screened.add(key(s.getCandidateId(), s.getRequisitionId())));
        Set<String> applied = new HashSet<>(screened);
        matches.forEach(m -> applied.add(key(m.getCandidateId(), m.getRequisitionId())));
        stages.forEach(s -> applied.add(key(s.getCandidateId(), s.getRequisitionId())));

        List<FunnelStep> funnel = new ArrayList<>();
        int prev = -1;
        for (Object[] step : new Object[][]{{"Entered pipeline", applied}, {"Screened", screened}, {"Advanced by screening", advanced},
                {"Shortlisted", shortlisted}, {"Interviewed", interviewed}, {"Offered", offered}, {"Hired", hired}}) {
            int n = ((Set<?>) step[1]).size();
            funnel.add(new FunnelStep((String) step[0], n, prev > 0 ? round(100.0 * n / prev) : null));
            prev = n;
        }

        // ---- role coverage
        Map<Long, List<InterviewSchedule>> interviewsByReq = interviews.stream().collect(Collectors.groupingBy(InterviewSchedule::getRequisitionId));
        TimeToFill ttf = timeToFill(stages, reqById);
        List<RoleCoverage> coverage = new ArrayList<>();
        int qualifiedTotal = 0;
        for (JobRequisition r : openRoles) {
            Set<Long> applicantIds = new HashSet<>();
            screenings.stream().filter(s -> r.getId().equals(s.getRequisitionId())).forEach(s -> applicantIds.add(s.getCandidateId()));
            stages.stream().filter(s -> r.getId().equals(s.getRequisitionId())).forEach(s -> applicantIds.add(s.getCandidateId()));
            matches.stream().filter(m -> r.getId().equals(m.getRequisitionId()) && Boolean.FALSE.equals(m.getIsProactive()))
                    .forEach(m -> applicantIds.add(m.getCandidateId()));

            int qualified = 0, interviewing = 0, offerCount = 0, hiredCount = 0;
            for (Long cid : applicantIds) {
                PipelineStage st = stageByKey.get(key(cid, r.getId()));
                String stage = st != null ? st.getStage() : null;
                if ("HIRED".equals(stage)) { hiredCount++; continue; }
                if ("REJECTED".equals(stage) || "NO_SHOW".equals(stage)) continue;
                if ("OFFER".equals(stage)) offerCount++;
                else if ("INTERVIEW_SCHEDULED".equals(stage)) interviewing++;
                ScreeningResult sr = screeningByKey.get(key(cid, r.getId()));
                if ((sr != null && advancedByScreening(sr)) || (stage != null && SHORTLIST_OR_LATER.contains(stage))) qualified++;
            }
            qualifiedTotal += qualified;
            int daysOpen = r.getCreatedAt() != null ? (int) Duration.between(r.getCreatedAt(), now).toDays() : 0;
            String status = hiredCount > 0 ? "FILLED" : qualified >= TARGET_QUALIFIED_PER_ROLE ? "HEALTHY" : qualified >= 1 ? "AT_RISK" : "CRITICAL";
            String expected = (hiredCount == 0 && ttf.averageDays() != null && r.getCreatedAt() != null)
                    ? r.getCreatedAt().plusDays(Math.round(ttf.averageDays())).toLocalDate().toString() : null;
            coverage.add(new RoleCoverage(r.getId(), r.getTitle(), r.getDepartment(), daysOpen, applicantIds.size(),
                    qualified, interviewing, offerCount, hiredCount, status, expected));
        }
        coverage.sort(Comparator.comparingInt((RoleCoverage c) -> statusRank(c.status())).thenComparing(RoleCoverage::daysOpen, Comparator.reverseOrder()));

        // ---- skills supply vs demand
        List<SkillGap> skills = skillGaps(openRoles, candById.values());

        // ---- channel effectiveness
        List<ChannelStat> channels = channelStats(candById, applied, advanced, hired, screenings);

        // ---- stalled candidates
        List<Stalled> stalled = new ArrayList<>();
        for (PipelineStage s : stages) {
            if (CLOSED_STAGES.contains(s.getStage()) || s.getEnteredAt() == null) continue;
            int days = (int) Duration.between(s.getEnteredAt(), now).toDays();
            JobRequisition r = reqById.get(s.getRequisitionId());
            Candidate c = candById.get(s.getCandidateId());
            if (days >= STALLED_AFTER_DAYS && r != null && "OPEN".equals(r.getStatus()) && c != null) {
                stalled.add(new Stalled(c.getId(), c.getFullName(), r.getId(), r.getTitle(), s.getStage(), days));
            }
        }
        stalled.sort(Comparator.comparingInt(Stalled::daysInStage).reversed());

        // ---- offers
        long accepted = offers.stream().filter(o -> "ACCEPTED".equals(o.getStatus())).count();
        long declined = offers.stream().filter(o -> "DECLINED".equals(o.getStatus())).count();
        Double acceptanceRate = (accepted + declined) > 0 ? round(100.0 * accepted / (accepted + declined)) : null;

        int inProcess = (int) stages.stream().filter(s -> !CLOSED_STAGES.contains(s.getStage())).count();
        Kpis kpis = new Kpis(openRoles.size(), qualifiedTotal, inProcess, ttf.averageDays(), acceptanceRate, hired.size());

        List<Alert> alerts = alerts(coverage, skills, stalled, funnel, accepted, declined, interviewsByReq, openRoles, now);
        return new Overview(now, kpis, alerts, coverage, funnel, skills, channels,
                stalled.stream().limit(10).toList(), ttf);
    }

    public Attention attention() {
        LocalDateTime now = LocalDateTime.now();
        List<ScreeningResult> screenings = screeningRepository.findAll();
        int edgeCases = (int) screenings.stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsEdgeCase()) && s.getReviewerDecision() == null).count();
        int offersAwaiting = (int) offerRepository.findAll().stream().filter(o -> "SENT".equals(o.getStatus())).count();

        List<InterviewSchedule> interviews = interviewRepository.findAll();
        int today = (int) interviews.stream().filter(i -> "CONFIRMED".equals(i.getStatus()) && i.getConfirmedSlot() != null
                && i.getConfirmedSlot().toLocalDate().equals(now.toLocalDate())).count();
        int waitingToBook = (int) interviews.stream().filter(i -> "PROPOSED".equals(i.getStatus())).count();

        Set<String> screenedKeys = screenings.stream().map(s -> key(s.getCandidateId(), s.getRequisitionId())).collect(Collectors.toSet());
        Map<Long, Candidate> candById = candidateRepository.findAll().stream().collect(Collectors.toMap(Candidate::getId, c -> c));
        Set<Long> openRoleIds = requisitionRepository.findAll().stream().filter(r -> "OPEN".equals(r.getStatus()))
                .map(JobRequisition::getId).collect(Collectors.toSet());
        LocalDateTime since = now.minusDays(14);
        int unscreened = (int) matchRepository.findAll().stream()
                .filter(m -> Boolean.FALSE.equals(m.getIsProactive()) && "APPLIED".equals(m.getStatus())
                        && m.getCreatedAt() != null && m.getCreatedAt().isAfter(since) && openRoleIds.contains(m.getRequisitionId()))
                .filter(m -> !screenedKeys.contains(key(m.getCandidateId(), m.getRequisitionId())))
                .filter(m -> { Candidate c = candById.get(m.getCandidateId()); return c != null && c.getResumeText() != null && !c.getResumeText().isBlank(); })
                .count();

        return new Attention(edgeCases, offersAwaiting, today, waitingToBook, unscreened,
                overview().alerts().stream().limit(3).toList());
    }

    // ---- pieces --------------------------------------------------------------------------------

    private boolean advancedByScreening(ScreeningResult s) {
        String decision = s.getReviewerDecision() != null ? s.getReviewerDecision() : s.getRecommendation();
        return "ADVANCE".equals(decision);
    }

    private TimeToFill timeToFill(List<PipelineStage> stages, Map<Long, JobRequisition> reqById) {
        List<Long> days = new ArrayList<>();
        for (PipelineStage s : stages) {
            JobRequisition r = reqById.get(s.getRequisitionId());
            if ("HIRED".equals(s.getStage()) && r != null && r.getCreatedAt() != null && s.getEnteredAt() != null) {
                days.add(Math.max(0, Duration.between(r.getCreatedAt(), s.getEnteredAt()).toDays()));
            }
        }
        if (days.isEmpty()) return new TimeToFill(0, null, "Not enough history yet. This fills in after the first hire.");
        double avg = days.stream().mapToLong(Long::longValue).average().orElse(0);
        String note = days.size() < 5 ? "Based on only " + days.size() + " hire" + (days.size() == 1 ? "" : "s") + "; treat as a rough guide." : "Based on " + days.size() + " hires.";
        return new TimeToFill(days.size(), round(avg), note);
    }

    private List<SkillGap> skillGaps(List<JobRequisition> openRoles, Collection<Candidate> candidates) {
        Map<String, Integer> demand = new LinkedHashMap<>();
        for (JobRequisition r : openRoles) {
            if (r.getRequiredSkills() == null) continue;
            Set<String> perRole = new LinkedHashSet<>();
            for (String raw : r.getRequiredSkills().split("[,;\\n/|]")) {
                String skill = raw.trim().toLowerCase();
                if (skill.length() >= 2 && skill.length() <= 40) perRole.add(skill);
            }
            perRole.forEach(sk -> demand.merge(sk, 1, Integer::sum));
        }
        List<String> haystacks = candidates.stream()
                .map(c -> ((c.getSkills() != null ? c.getSkills() : "") + " " + (c.getResumeText() != null ? c.getResumeText() : "")).toLowerCase())
                .toList();
        List<SkillGap> out = new ArrayList<>();
        for (var e : demand.entrySet()) {
            Pattern p = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(e.getKey()) + "(?![a-z0-9])");
            int supply = (int) haystacks.stream().filter(h -> p.matcher(h).find()).count();
            String status = supply >= e.getValue() * TARGET_QUALIFIED_PER_ROLE ? "OK" : supply >= e.getValue() ? "TIGHT" : "SHORTAGE";
            out.add(new SkillGap(e.getKey(), e.getValue(), supply, status));
        }
        out.sort(Comparator.comparingInt(SkillGap::openRolesNeeding).reversed().thenComparingInt(SkillGap::candidatesWithSkill));
        return out.stream().limit(12).toList();
    }

    private List<ChannelStat> channelStats(Map<Long, Candidate> candById, Set<String> applied, Set<String> advanced,
                                           Set<String> hired, List<ScreeningResult> screenings) {
        Map<String, Set<Long>> appliedBy = new HashMap<>(), advancedBy = new HashMap<>(), hiredBy = new HashMap<>();
        BiConsumerLike add = (keys, target) -> keys.forEach(k -> {
            Long cid = Long.valueOf(k.split(":")[0]);
            Candidate c = candById.get(cid);
            if (c != null) target.computeIfAbsent(c.getSourceChannel() != null ? c.getSourceChannel() : "UNKNOWN", x -> new HashSet<>()).add(cid);
        });
        add.accept(applied, appliedBy);
        add.accept(advanced, advancedBy);
        add.accept(hired, hiredBy);

        Map<String, List<BigDecimal>> scores = new HashMap<>();
        for (ScreeningResult s : screenings) {
            Candidate c = candById.get(s.getCandidateId());
            if (c != null && s.getOverallScore() != null) {
                scores.computeIfAbsent(c.getSourceChannel() != null ? c.getSourceChannel() : "UNKNOWN", x -> new ArrayList<>()).add(s.getOverallScore());
            }
        }
        List<ChannelStat> out = new ArrayList<>();
        for (var e : appliedBy.entrySet()) {
            int n = e.getValue().size();
            int adv = advancedBy.getOrDefault(e.getKey(), Set.of()).size();
            int hr = hiredBy.getOrDefault(e.getKey(), Set.of()).size();
            List<BigDecimal> sc = scores.getOrDefault(e.getKey(), List.of());
            Double avg = sc.isEmpty() ? null : round(sc.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0));
            out.add(new ChannelStat(e.getKey(), n, adv, hr, avg, round(100.0 * adv / n), round(100.0 * hr / n)));
        }
        out.sort(Comparator.comparingInt(ChannelStat::candidates).reversed());
        return out;
    }

    @FunctionalInterface
    private interface BiConsumerLike { void accept(Set<String> keys, Map<String, Set<Long>> target); }

    private List<Alert> alerts(List<RoleCoverage> coverage, List<SkillGap> skills, List<Stalled> stalled, List<FunnelStep> funnel,
                               long accepted, long declined, Map<Long, List<InterviewSchedule>> interviewsByReq,
                               List<JobRequisition> openRoles, LocalDateTime now) {
        List<Alert> out = new ArrayList<>();
        for (RoleCoverage c : coverage) {
            if ("CRITICAL".equals(c.status())) {
                out.add(new Alert("HIGH", "No qualified candidates for " + c.title(),
                        "Open " + c.daysOpen() + " day" + (c.daysOpen() == 1 ? "" : "s") + " with " + c.applicants() + " applicant" + (c.applicants() == 1 ? "" : "s") + ". Source more candidates or review screening."));
            } else if ("AT_RISK".equals(c.status())) {
                out.add(new Alert("MEDIUM", "Thin pipeline for " + c.title(),
                        c.qualified() + " qualified candidate" + (c.qualified() == 1 ? "" : "s") + " against a target of " + TARGET_QUALIFIED_PER_ROLE + "."));
            }
        }
        for (JobRequisition r : openRoles) {
            long days = r.getCreatedAt() != null ? Duration.between(r.getCreatedAt(), now).toDays() : 0;
            if (days >= SLOW_ROLE_DAYS && interviewsByReq.getOrDefault(r.getId(), List.of()).isEmpty()) {
                out.add(new Alert("MEDIUM", r.getTitle() + " has had no interviews in " + days + " days", "The role may be stuck at screening or sourcing."));
            }
        }
        skills.stream().filter(s -> "SHORTAGE".equals(s.status())).limit(3).forEach(s ->
                out.add(new Alert("MEDIUM", "Skills shortage: " + s.skill(),
                        s.candidatesWithSkill() + " candidate" + (s.candidatesWithSkill() == 1 ? "" : "s") + " in the system for " + s.openRolesNeeding() + " open role" + (s.openRolesNeeding() == 1 ? "" : "s") + " needing it.")));
        if (!stalled.isEmpty()) {
            out.add(new Alert("LOW", stalled.size() + " candidate" + (stalled.size() == 1 ? " is" : "s are") + " stuck in a stage for " + STALLED_AFTER_DAYS + "+ days",
                    "Longest: " + stalled.get(0).candidateName() + " (" + stalled.get(0).daysInStage() + " days in " + stalled.get(0).stage().replace('_', ' ').toLowerCase() + ")."));
        }
        if (accepted + declined >= 3 && (double) declined / (accepted + declined) >= 0.4) {
            out.add(new Alert("MEDIUM", "Many offers are being declined", declined + " of " + (accepted + declined) + " answered offers were declined. Check salary and timing."));
        }
        FunnelStep screened = funnel.get(1), advancedStep = funnel.get(2);
        if (screened.count() >= 10 && advancedStep.conversionFromPrevious() != null && advancedStep.conversionFromPrevious() < 20) {
            out.add(new Alert("LOW", "Few candidates pass screening (" + advancedStep.conversionFromPrevious().intValue() + "%)",
                    "Either sourcing quality is low or the screening bar is too strict for these roles."));
        }
        out.sort(Comparator.comparingInt(a -> severityRank(a.severity())));
        return out.stream().limit(10).toList();
    }

    private static int statusRank(String s) {
        return switch (s) { case "CRITICAL" -> 0; case "AT_RISK" -> 1; case "HEALTHY" -> 2; default -> 3; };
    }

    private static int severityRank(String s) {
        return switch (s) { case "HIGH" -> 0; case "MEDIUM" -> 1; default -> 2; };
    }

    private static String key(Long candidateId, Long requisitionId) {
        return candidateId + ":" + requisitionId;
    }

    private static Double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
