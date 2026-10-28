package com.talentai.debrief;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.admin.entity.InterviewSchedule;
import com.talentai.admin.entity.InterviewScorecard;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.InterviewScorecardRepository;
import com.talentai.aiusage.AiBudgetExceededException;
import com.talentai.common.entity.AgentActivityLog;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.service.PromptLoader;
import com.talentai.orchestrator.service.ScorecardCriteria;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads every interview scorecard a candidate has for one role and asks the AI for a debrief: where the
 * interviewers agree and differ, the concerns, and an advisory next step. The recommendation never moves
 * the candidate; a recruiter still decides. Fixed rules (see {@link #guard}) stop it contradicting hard
 * facts such as unfinished required rounds or an interviewer's "No".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewDebriefService {

    static final Set<String> RECOMMENDATIONS = Set.of("ADVANCE_TO_OFFER", "ADD_ROUND", "HOLD_FOR_DISCUSSION", "DO_NOT_PROCEED");
    private static final Set<String> CONFIDENCES = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Map<String, String> VERDICT_LABELS = Map.of(
            "STRONG_YES", "Strong yes", "YES", "Yes", "NO", "No", "STRONG_NO", "Strong no");
    private static final String AGENT_NAME = "ORCHESTRATOR";
    private static final int MAX_POINTS = 4;
    private static final int MAX_POINT_LENGTH = 300;
    private static final int MAX_TEXT_LENGTH = 1500;

    private final InterviewDebriefRepository debriefRepository;
    private final InterviewScheduleRepository scheduleRepository;
    private final InterviewScorecardRepository scorecardRepository;
    private final JobRequisitionRepository requisitionRepository;
    private final AppUserRepository appUserRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    /** What the model returns. */
    public record AiOutput(String recommendation, String confidence, String summary, List<String> agreements,
                           List<String> disagreements, List<String> concerns, String suggestedNextStep) {}

    /** What the API returns. Before the first debrief only the counts are filled in. */
    public record DebriefView(boolean available, boolean generated, boolean stale, int scorecardCount,
                              int completedRounds, int requiredRounds, String recommendation, String confidence,
                              String summary, List<String> agreements, List<String> disagreements,
                              List<String> concerns, String suggestedNextStep, String guardrailNote,
                              String generatedBy, LocalDateTime generatedAt) {}

    record Guarded(String recommendation, String note) {}

    private record Round(int number, InterviewSchedule interview, InterviewScorecard scorecard, String interviewer) {}

    private record Inputs(JobRequisition requisition, List<Round> rounds, int completed, int required) {}

    public DebriefView get(Long candidateId, Long requisitionId) {
        Inputs in = load(candidateId, requisitionId);
        return debriefRepository.findByCandidateIdAndRequisitionId(candidateId, requisitionId)
                .map(d -> view(d, in, !d.getFingerprint().equals(fingerprint(in))))
                .orElseGet(() -> new DebriefView(!in.rounds.isEmpty(), false, false, in.rounds.size(), in.completed,
                        in.required, null, null, null, List.of(), List.of(), List.of(), null, null, null, null));
    }

    public DebriefView generate(Long candidateId, Long requisitionId, String username) {
        Inputs in = load(candidateId, requisitionId);
        if (in.rounds.isEmpty()) {
            throw new IllegalStateException("There are no completed interviews with a scorecard to debrief yet.");
        }

        AiOutput out;
        try {
            out = chatClient.prompt()
                    .system(promptLoader.load("interview-debrief"))
                    .user(prompt(in))
                    .call()
                    .entity(AiOutput.class);
        } catch (AiBudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            log.error("Interview debrief failed for candidate {} role {}", candidateId, requisitionId, e);
            throw new IllegalStateException("The AI debrief is temporarily unavailable. Please try again.");
        }
        if (out == null || out.recommendation() == null || !RECOMMENDATIONS.contains(out.recommendation().trim().toUpperCase())
                || out.summary() == null || out.summary().isBlank()) {
            throw new IllegalStateException("The AI returned an unusable answer. Please try again.");
        }

        List<String> verdicts = in.rounds.stream().map(r -> r.scorecard.getVerdict()).toList();
        String confidence = out.confidence() != null && CONFIDENCES.contains(out.confidence().trim().toUpperCase())
                ? out.confidence().trim().toUpperCase() : "MEDIUM";
        Guarded guarded = guard(out.recommendation().trim().toUpperCase(), confidence, in.completed, in.required, verdicts);

        Map<String, List<String>> details = new TreeMap<>();
        details.put("agreements", points(out.agreements()));
        details.put("disagreements", points(out.disagreements()));
        details.put("concerns", points(out.concerns()));

        InterviewDebrief d = debriefRepository.findByCandidateIdAndRequisitionId(candidateId, requisitionId)
                .orElseGet(() -> InterviewDebrief.builder().candidateId(candidateId).requisitionId(requisitionId).build());
        d.setFingerprint(fingerprint(in));
        d.setRecommendation(guarded.recommendation());
        d.setConfidence(confidence);
        d.setSummary(clip(out.summary().trim(), MAX_TEXT_LENGTH));
        d.setDetailsJson(toJson(details));
        d.setSuggestedNextStep(out.suggestedNextStep() == null || out.suggestedNextStep().isBlank()
                ? null : clip(out.suggestedNextStep().trim(), MAX_POINT_LENGTH));
        d.setGuardrailNote(guarded.note());
        d.setGeneratedBy(username);
        d.setGeneratedAt(LocalDateTime.now());
        d = debriefRepository.save(d);

        activityLogRepository.save(AgentActivityLog.builder().agentName(AGENT_NAME)
                .candidateId(candidateId).requisitionId(requisitionId).action("INTERVIEW_DEBRIEF_GENERATED")
                .details("AI debrief of " + in.rounds.size() + " scorecard(s): " + d.getRecommendation().toLowerCase().replace('_', ' ')
                        + " (" + confidence.toLowerCase() + " confidence)"
                        + (guarded.note() != null ? " – adjusted by a rule" : ""))
                .build());
        return view(d, in, false);
    }

    /**
     * Fixed rules applied after the AI answers, so the advice never contradicts hard facts. The AI can
     * still be more cautious than these rules, never less.
     */
    static Guarded guard(String recommendation, String confidence, int completedRounds, int requiredRounds, Collection<String> verdicts) {
        boolean anyNo = verdicts.stream().anyMatch(v -> "NO".equals(v) || "STRONG_NO".equals(v));
        boolean allPositive = !verdicts.isEmpty() && verdicts.stream().allMatch(v -> "YES".equals(v) || "STRONG_YES".equals(v));
        if ("ADVANCE_TO_OFFER".equals(recommendation) && completedRounds < requiredRounds) {
            return new Guarded("ADD_ROUND", "Only " + completedRounds + " of " + requiredRounds
                    + " required interview rounds are completed, so the offer stage is not open yet.");
        }
        if ("ADVANCE_TO_OFFER".equals(recommendation) && anyNo) {
            return new Guarded("HOLD_FOR_DISCUSSION",
                    "At least one interviewer's verdict is No, so the panel should talk it through before an offer.");
        }
        if ("DO_NOT_PROCEED".equals(recommendation) && allPositive) {
            return new Guarded("HOLD_FOR_DISCUSSION",
                    "Every interviewer's verdict is positive, so a recommendation not to proceed needs a person to review it first.");
        }
        if ("DO_NOT_PROCEED".equals(recommendation) && "LOW".equals(confidence) && !verdicts.contains("STRONG_NO")) {
            return new Guarded("HOLD_FOR_DISCUSSION",
                    "The AI has low confidence in this evidence, so a recommendation not to proceed needs a person to review it first.");
        }
        return new Guarded(recommendation, null);
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private Inputs load(Long candidateId, Long requisitionId) {
        JobRequisition requisition = requisitionRepository.findById(requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + requisitionId));
        List<InterviewSchedule> interviews = scheduleRepository
                .findByCandidateIdAndRequisitionIdOrderByCreatedAtAsc(candidateId, requisitionId);
        List<Round> rounds = new ArrayList<>();
        int completed = 0;
        for (int i = 0; i < interviews.size(); i++) {
            InterviewSchedule iv = interviews.get(i);
            if (!"COMPLETED".equals(iv.getStatus())) continue;
            completed++;
            final int number = i + 1;
            scorecardRepository.findByInterviewId(iv.getId()).ifPresent(sc -> rounds.add(
                    new Round(number, iv, sc, interviewerName(iv, sc))));
        }
        int required = requisition.getRequiredInterviewRounds() != null ? requisition.getRequiredInterviewRounds() : 2;
        return new Inputs(requisition, rounds, completed, required);
    }

    private String interviewerName(InterviewSchedule iv, InterviewScorecard sc) {
        Long id = iv.getInterviewerId() != null ? iv.getInterviewerId() : sc.getSubmittedBy();
        return id == null ? "Interviewer" : appUserRepository.findById(id).map(u -> u.getFullName()).orElse("Interviewer");
    }

    /** The candidate is deliberately not identified: the debrief should rest on the scorecards alone. */
    private String prompt(Inputs in) {
        JobRequisition r = in.requisition;
        StringBuilder sb = new StringBuilder();
        sb.append("Role: ").append(r.getTitle());
        if (r.getDepartment() != null) sb.append(" (").append(r.getDepartment()).append(")");
        sb.append("\nExperience level: ").append(r.getExperienceLevel() != null ? r.getExperienceLevel() : "not stated");
        sb.append("\nRequired skills: ").append(r.getRequiredSkills() != null ? r.getRequiredSkills() : "not stated");
        if (r.getDescription() != null && !r.getDescription().isBlank()) {
            sb.append("\nRole description: ").append(clip(r.getDescription().trim(), 1200));
        }
        sb.append("\nInterview rounds required before an offer: ").append(in.required)
                .append(" (completed: ").append(in.completed).append(")");
        if (in.rounds.size() < in.completed) {
            sb.append("\nOnly ").append(in.rounds.size()).append(" of the ").append(in.completed)
                    .append(" completed rounds have a scorecard; the others cannot be assessed.");
        }
        sb.append("\n\nScorecards (ratings are 1-5):\n");
        for (Round round : in.rounds) {
            InterviewScorecard sc = round.scorecard;
            sb.append("\nRound ").append(round.number).append(" (")
                    .append(round.interview.getInterviewType().replace('_', ' ').toLowerCase())
                    .append(") - interviewer: ").append(round.interviewer)
                    .append("\n  Verdict: ").append(VERDICT_LABELS.getOrDefault(sc.getVerdict(), sc.getVerdict()))
                    .append("\n  Ratings: ");
            List<String> parts = new ArrayList<>();
            new TreeMap<>(sc.getRatings()).forEach((k, v) -> parts.add(ScorecardCriteria.LABELS.getOrDefault(k, k) + " " + v + "/5"));
            sb.append(String.join("; ", parts));
            if (sc.getNoticePeriod() != null) sb.append("\n  Notice period: ").append(sc.getNoticePeriod());
            if (sc.getExpectedSalary() != null) sb.append("\n  Expected salary: ").append(sc.getExpectedSalary());
            sb.append("\n  Notes: ").append(sc.getNotes() == null ? "(none)" : clip(sc.getNotes(), MAX_TEXT_LENGTH));
        }
        return sb.toString();
    }

    private DebriefView view(InterviewDebrief d, Inputs in, boolean stale) {
        Map<String, List<String>> details = fromJson(d.getDetailsJson());
        return new DebriefView(true, true, stale, in.rounds.size(), in.completed, in.required,
                d.getRecommendation(), d.getConfidence(), d.getSummary(),
                details.getOrDefault("agreements", List.of()), details.getOrDefault("disagreements", List.of()),
                details.getOrDefault("concerns", List.of()), d.getSuggestedNextStep(), d.getGuardrailNote(),
                d.getGeneratedBy(), d.getGeneratedAt());
    }

    /** A hash of everything the debrief depends on, so a new or changed scorecard shows up as a different value. */
    private String fingerprint(Inputs in) {
        StringBuilder sb = new StringBuilder().append(in.required).append('|').append(in.completed);
        for (Round r : in.rounds) {
            InterviewScorecard sc = r.scorecard;
            sb.append('|').append(r.interview.getId()).append(':').append(sc.getVerdict())
                    .append(':').append(new TreeMap<>(sc.getRatings()))
                    .append(':').append(sc.getNotes()).append(':').append(sc.getNoticePeriod())
                    .append(':').append(sc.getExpectedSalary()).append(':').append(sc.getSubmittedAt());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> points(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream().filter(s -> s != null && !s.isBlank()).map(s -> clip(s.trim(), MAX_POINT_LENGTH)).limit(MAX_POINTS).toList();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, List<String>> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
