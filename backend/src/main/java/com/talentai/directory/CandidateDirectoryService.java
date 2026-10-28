package com.talentai.directory;

import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.InterviewScorecard;
import com.talentai.admin.entity.Offer;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.admin.repository.OfferRepository;
import com.talentai.common.entity.AppUser;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.NotificationLog;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.NotificationLogRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.screening.entity.ScreeningResult;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.sourcing.entity.SourcingMatch;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Finds candidates by email, name, phone or skills, and assembles everything held about one candidate. */
@Service
@RequiredArgsConstructor
public class CandidateDirectoryService {

    static final int MIN_QUERY_LENGTH = 2;
    static final int MAX_RESULTS = 25;

    private final CandidateRepository candidateRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final SourcingMatchRepository sourcingMatchRepository;
    private final InterviewScheduleRepository interviewScheduleRepository;
    private final InterviewScorecardRepository scorecardRepository;
    private final OfferRepository offerRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final JobRequisitionRepository requisitionRepository;
    private final AppUserRepository appUserRepository;
    private final CandidateAccessLogRepository accessLogRepository;

    public record SearchHit(Long id, String fullName, String email, String phone, String headline,
                            String matchedOn, int applicationCount, String latestRole, String latestStage) {}

    public record SharedEmail(Long id, String fullName) {}

    public record ApplicationView(Long requisitionId, String roleTitle, String department, String stage,
                                  LocalDateTime stageEnteredAt, String stageNotes,
                                  BigDecimal screeningScore, String recommendation, String reviewerDecision,
                                  String reviewerNotes, String rationale, String strengths, String gaps,
                                  BigDecimal matchScore) {}

    public record ScorecardView(String verdict, Map<String, Integer> ratings, String notes,
                                String noticePeriod, String expectedSalary, LocalDateTime submittedAt) {}

    public record InterviewView(Long id, Long requisitionId, String roleTitle, String interviewType, String status,
                                String interviewerName, LocalDateTime confirmedSlot, String notes,
                                ScorecardView scorecard) {}

    public record OfferView(Long id, String roleTitle, BigDecimal salaryAmount, String currency,
                            LocalDate startDate, LocalDate expiryDate, String status, LocalDateTime createdAt) {}

    public record EmailView(Long id, String roleTitle, String type, String recipientEmail, String subject,
                            String body, String status, LocalDateTime createdAt) {}

    public record AccessEntry(String username, LocalDateTime at) {}

    public record Profile(Long id, String fullName, String email, String phone, String sourceChannel,
                          String headline, String skills, BigDecimal yearsExperience, String profileUrl,
                          String resumeFilename, String resumeText, LocalDateTime createdAt,
                          List<SharedEmail> sharedEmailWith, List<ApplicationView> applications,
                          List<InterviewView> interviews, List<OfferView> offers, List<EmailView> emails,
                          List<AccessEntry> recentViews) {}

    public List<SearchHit> search(String rawQuery, String username) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.length() < MIN_QUERY_LENGTH) return List.of();

        List<Candidate> found = candidateRepository.search(escapeLike(q));
        String needle = q.toLowerCase();

        List<SearchHit> hits = found.stream()
                .map(c -> Map.entry(c, rank(c, needle)))
                .sorted(Map.Entry.<Candidate, Integer>comparingByValue().reversed()
                        .thenComparing(e -> nullSafe(e.getKey().getFullName()).toLowerCase()))
                .limit(MAX_RESULTS)
                .map(e -> toHit(e.getKey(), needle))
                .toList();

        log("SEARCH", null, truncate(q, 200), found.size(), username);
        return hits;
    }

    public Profile profile(Long id, String username, boolean admin) {
        Candidate c = candidateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + id));

        Map<Long, JobRequisition> roles = requisitionRepository.findAll().stream()
                .collect(Collectors.toMap(JobRequisition::getId, Function.identity()));
        Map<Long, String> userNames = new HashMap<>();
        for (AppUser u : appUserRepository.findAll()) {
            userNames.put(u.getId(), u.getFullName() != null ? u.getFullName() : u.getUsername());
        }

        Map<Long, ScreeningResult> screening = screeningResultRepository.findByCandidateId(id).stream()
                .collect(Collectors.toMap(ScreeningResult::getRequisitionId, Function.identity(), (a, b) -> a));
        Map<Long, SourcingMatch> matches = sourcingMatchRepository.findByCandidateId(id).stream()
                .collect(Collectors.toMap(SourcingMatch::getRequisitionId, Function.identity(), (a, b) -> a));

        List<ApplicationView> applications = pipelineStageRepository.findByCandidateId(id).stream()
                .sorted(Comparator.comparing(PipelineStage::getEnteredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(s -> {
                    JobRequisition role = roles.get(s.getRequisitionId());
                    ScreeningResult sr = screening.get(s.getRequisitionId());
                    SourcingMatch m = matches.get(s.getRequisitionId());
                    return new ApplicationView(s.getRequisitionId(), title(role), role != null ? role.getDepartment() : null,
                            s.getStage(), s.getEnteredAt(), s.getNotes(),
                            sr != null ? sr.getOverallScore() : null,
                            sr != null ? sr.getRecommendation() : null,
                            sr != null ? sr.getReviewerDecision() : null,
                            sr != null ? sr.getReviewerNotes() : null,
                            sr != null ? sr.getRationale() : null,
                            sr != null ? sr.getStrengths() : null,
                            sr != null ? sr.getGaps() : null,
                            m != null ? m.getMatchScore() : null);
                })
                .toList();

        List<InterviewView> interviews = interviewScheduleRepository.findByCandidateId(id).stream()
                .sorted(Comparator.comparing(InterviewSchedule::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(i -> new InterviewView(i.getId(), i.getRequisitionId(), title(roles.get(i.getRequisitionId())), i.getInterviewType(),
                        i.getStatus(), i.getInterviewerId() != null ? userNames.get(i.getInterviewerId()) : null,
                        i.getConfirmedSlot(), i.getNotes(),
                        scorecardRepository.findByInterviewId(i.getId()).map(this::toScorecard).orElse(null)))
                .toList();

        List<OfferView> offers = offerRepository.findByCandidateId(id).stream()
                .sorted(Comparator.comparing(Offer::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(o -> new OfferView(o.getId(), title(roles.get(o.getRequisitionId())), o.getSalaryAmount(),
                        o.getCurrency(), o.getStartDate(), o.getExpiryDate(), o.getStatus(), o.getCreatedAt()))
                .toList();

        List<EmailView> emails = notificationLogRepository.findByCandidateIdOrderByCreatedAtDesc(id).stream()
                .map(n -> new EmailView(n.getId(), title(roles.get(n.getRequisitionId())), n.getNotificationType(),
                        n.getRecipientEmail(), n.getSubject(), n.getBody(), n.getStatus(), n.getCreatedAt()))
                .toList();

        List<SharedEmail> shared = c.getEmail() == null ? List.of()
                : candidateRepository.findAllByEmailIgnoreCaseOrderByIdAsc(c.getEmail()).stream()
                        .filter(o -> !o.getId().equals(id))
                        .map(o -> new SharedEmail(o.getId(), o.getFullName()))
                        .toList();

        List<AccessEntry> recentViews = admin
                ? accessLogRepository.findTop10ByCandidateIdAndActionOrderByCreatedAtDesc(id, "VIEW").stream()
                        .map(a -> new AccessEntry(a.getUsername(), a.getCreatedAt()))
                        .toList()
                : null;

        log("VIEW", id, null, null, username);

        return new Profile(c.getId(), c.getFullName(), c.getEmail(), c.getPhone(), c.getSourceChannel(),
                c.getHeadline(), c.getSkills(), c.getYearsExperience(), c.getProfileUrl(), c.getResumeFilename(),
                c.getResumeText(), c.getCreatedAt(), shared, applications, interviews, offers, emails, recentViews);
    }

    private SearchHit toHit(Candidate c, String needle) {
        List<PipelineStage> stages = pipelineStageRepository.findByCandidateId(c.getId());
        PipelineStage latest = stages.stream()
                .max(Comparator.comparing(PipelineStage::getEnteredAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        String role = null;
        if (latest != null && latest.getRequisitionId() != null) {
            role = requisitionRepository.findById(latest.getRequisitionId()).map(JobRequisition::getTitle).orElse(null);
        }
        return new SearchHit(c.getId(), c.getFullName(), c.getEmail(), c.getPhone(), c.getHeadline(),
                matchedOn(c, needle), stages.size(), role, latest != null ? latest.getStage() : null);
    }

    /** Higher is a better match: exact email first, then email, name, phone, then skills/headline. */
    static int rank(Candidate c, String needle) {
        String email = nullSafe(c.getEmail()).toLowerCase();
        String name = nullSafe(c.getFullName()).toLowerCase();
        if (email.equals(needle)) return 100;
        if (email.startsWith(needle)) return 80;
        if (email.contains(needle)) return 70;
        if (name.equals(needle)) return 65;
        if (name.startsWith(needle)) return 60;
        if (name.contains(needle)) return 50;
        if (nullSafe(c.getPhone()).toLowerCase().contains(needle)) return 40;
        return 20;
    }

    static String matchedOn(Candidate c, String needle) {
        if (nullSafe(c.getEmail()).toLowerCase().contains(needle)) return "email";
        if (nullSafe(c.getFullName()).toLowerCase().contains(needle)) return "name";
        if (nullSafe(c.getPhone()).toLowerCase().contains(needle)) return "phone";
        if (nullSafe(c.getSkills()).toLowerCase().contains(needle)) return "skills";
        return "headline";
    }

    /** Escapes LIKE wildcards so a search for "100%" or "a_b" is matched literally (escape char is a backslash). */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private ScorecardView toScorecard(InterviewScorecard s) {
        return new ScorecardView(s.getVerdict(), s.getRatings(), s.getNotes(), s.getNoticePeriod(),
                s.getExpectedSalary(), s.getSubmittedAt());
    }

    private void log(String action, Long candidateId, String text, Integer count, String username) {
        accessLogRepository.save(CandidateAccessLog.builder()
                .username(username != null ? username : "unknown")
                .action(action).candidateId(candidateId).searchText(text).resultCount(count).build());
    }

    private static String title(JobRequisition r) {
        return r != null ? r.getTitle() : null;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
