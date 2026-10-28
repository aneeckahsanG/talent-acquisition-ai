package com.talentai.screening.service;

import org.springframework.ai.chat.client.ChatClient;
import com.talentai.common.entity.AgentActivityLog;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.common.service.PromptLoader;
import com.talentai.common.util.PdfTextExtractor;
import com.talentai.screening.dto.ScreeningDtos.*;
import com.talentai.screening.entity.ScreeningResult;
import com.talentai.screening.repository.ScreeningResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SCREENING AGENT
 *
 * Responsibilities:
 *  - Apply a standardized rubric (skills, experience, culture-fit) to score
 *    a candidate's resume against a job requisition using Claude.
 *  - Flag edge cases for human review rather than auto-advance/auto-reject.
 *  - Update the candidate's pipeline stage upon completion.
 *
 * Human-in-the-loop: final ADVANCE/REJECT decisions for edge cases
 * (and ultimately all hiring decisions) rest with a human reviewer.
 * This agent provides a recommendation, not a final decision.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScreeningAgentService {

    private static final String AGENT_NAME = "SCREENING";

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final PdfTextExtractor pdfTextExtractor;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final com.talentai.common.service.EmailService emailService;
    private final com.talentai.common.repository.NotificationLogRepository notificationLogRepository;
    private final com.talentai.sourcing.repository.SourcingMatchRepository sourcingMatchRepository;

    public record ParsedNameEmail(String name, String email) {}

    /**
     * Screens a candidate (provided via resume text) against a requisition.
     * Creates/updates the Candidate record, persists the screening result,
     * and advances the pipeline stage.
     */
    @Transactional
    public ScreeningResultResponse screenCandidate(ScreenByTextRequest request) {
        JobRequisition requisition = jobRequisitionRepository.findById(request.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + request.getRequisitionId()));

        Candidate candidate = findOrCreateCandidate(
                request.getCandidateName(), request.getCandidateEmail(), request.getResumeText(), null, "UPLOAD");

        ScreeningResult result = runScreening(candidate, requisition);
        return toResponse(result, candidate, requisition);
    }

    /**
     * Parses a resume file and returns the candidate's name and email using Claude.
     */
    public ParsedResumeResponse parseResume(MultipartFile resumeFile) throws IOException {
        String resumeText = pdfTextExtractor.extractText(resumeFile);
        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("Could not extract any text from the uploaded resume file.");
        }
        ParsedNameEmail parsed = extractNameAndEmail(resumeText);
        return ParsedResumeResponse.builder()
                .name(parsed.name())
                .email(parsed.email())
                .resumeText(resumeText)
                .build();
    }

    /**
     * Extracts a candidate's name and email from raw resume text using Claude, falling back to
     * regex/heuristics (a simple email pattern, and the first line that looks like a person's
     * name) whenever the AI call fails or leaves a field blank — e.g. unusual resume layouts,
     * a header line the heuristic alone can't parse, or an AI outage. Shared by the manual
     * resume-upload flow and SFTP CV Inbox ingestion so both get the same, more robust extraction
     * instead of CV Inbox relying on the heuristic alone.
     */
    public ParsedNameEmail extractNameAndEmail(String resumeText) {
        String heuristicEmail = null;
        java.util.regex.Matcher emailMatcher = java.util.regex.Pattern
                .compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
                .matcher(resumeText);
        if (emailMatcher.find()) heuristicEmail = emailMatcher.group();

        // Heuristic name: first non-blank line that looks like a person's name
        // (2–5 words, no special chars except hyphens/apostrophes, title-cased or all-caps)
        String heuristicName = null;
        for (String line : resumeText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (trimmed.matches("[A-Za-z][A-Za-z'\\-]*(\\s+[A-Za-z][A-Za-z'\\-]*){1,4}")) {
                StringBuilder sb = new StringBuilder();
                for (String word : trimmed.split("\\s+")) {
                    if (!sb.isEmpty()) sb.append(" ");
                    sb.append(Character.toUpperCase(word.charAt(0)))
                      .append(word.substring(1).toLowerCase());
                }
                heuristicName = sb.toString();
                break;
            }
        }

        String prompt = "Extract the candidate's full name and email address from the following resume text. " +
                "Reply ONLY as JSON with keys \"name\" and \"email\". If a field is not found, use an empty string.\n\nResume:\n" +
                resumeText.substring(0, Math.min(resumeText.length(), 2000));
        try {
            ParsedNameEmail parsed = com.talentai.common.util.AiCallRetry.withRetry(
                    () -> {
                        var chatResponse = chatClient.prompt()
                                .system("You extract structured data from resumes. Return only valid JSON.")
                                .user(prompt)
                                .call()
                                .chatResponse();
                        String raw = com.talentai.common.util.AiJsonExtractor.extractText(chatResponse);
                        return com.talentai.common.util.AiJsonExtractor.parse(raw, ParsedNameEmail.class);
                    },
                    log, "Resume Name/Email Extraction", "resume text");
            String parsedName = parsed.name() != null ? parsed.name() : "";
            String parsedEmail = parsed.email() != null ? parsed.email() : "";
            String finalName = parsedName.isBlank() ? (heuristicName != null ? heuristicName : "") : parsedName;
            String finalEmail = parsedEmail.isBlank() ? (heuristicEmail != null ? heuristicEmail : "") : parsedEmail;
            return new ParsedNameEmail(titleCase(finalName), finalEmail);
        } catch (Exception e) {
            log.warn("AI name/email extraction failed, using heuristics only: {}", e.getMessage());
            return new ParsedNameEmail(
                    titleCase(heuristicName != null ? heuristicName : ""),
                    heuristicEmail != null ? heuristicEmail : "");
        }
    }

    /** "CHEN YU MING" / "chen yu ming" -> "Chen Yu Ming". Leaves already-mixed-case names
     *  (e.g. "McDonald", "O'Brien") alone rather than lowercasing interior capitals. */
    private static String titleCase(String name) {
        if (name == null || name.isBlank() || !name.equals(name.toUpperCase()) && !name.equals(name.toLowerCase())) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        for (String word : name.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) sb.append(word.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    /**
     * Screens a candidate from an uploaded resume file (PDF or text) against a requisition.
     */
    @Transactional
    public ScreeningResultResponse screenCandidateFromFile(
            String candidateName, String candidateEmail, Long requisitionId, MultipartFile resumeFile) throws IOException {

        JobRequisition requisition = jobRequisitionRepository.findById(requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + requisitionId));

        String resumeText = pdfTextExtractor.extractText(resumeFile);
        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("Could not extract any text from the uploaded resume file.");
        }

        Candidate candidate = findOrCreateCandidate(
                candidateName, candidateEmail, resumeText, resumeFile.getOriginalFilename(), "UPLOAD");

        // Block duplicate screening after we have the resolved candidate ID
        if (screeningResultRepository.findByCandidateIdAndRequisitionId(candidate.getId(), requisitionId).isPresent()) {
            throw new IllegalArgumentException(
                    "This candidate has already been screened for this role. Duplicate upload is not allowed.");
        }

        ScreeningResult result = runScreening(candidate, requisition);
        return toResponse(result, candidate, requisition);
    }

    private Candidate findOrCreateCandidate(String name, String email, String resumeText, String filename, String sourceChannel) {
        Candidate candidate = null;
        if (email != null && !email.isBlank()) {
            candidate = candidateRepository.findByEmail(email).orElse(null);
        }

        if (candidate == null) {
            candidate = Candidate.builder()
                    .fullName(name)
                    .email(email)
                    .sourceChannel(sourceChannel)
                    .resumeText(resumeText)
                    .resumeFilename(filename)
                    .build();
        } else {
            candidate.setResumeText(resumeText);
            if (filename != null) {
                candidate.setResumeFilename(filename);
            }
        }

        return candidateRepository.save(candidate);
    }

    /** Called by SourcingAgentService / ReferralAgentService to auto-screen after a match.
     *  Auto-rejects clear REJECT decisions; edge cases and REVIEW are held for human review. */
    @Transactional
    public void runScreeningInternal(Candidate candidate, JobRequisition requisition) {
        if (screeningResultRepository.findByCandidateIdAndRequisitionId(candidate.getId(), requisition.getId()).isPresent()) {
            return;
        }
        try {
            runScreening(candidate, requisition, true);
        } catch (IllegalStateException e) {
            // The AI call failed or was refused (outage, bad response, or the AI budget is used up).
            // Swallow it here, inside the transaction: letting it escape would mark the caller's whole
            // transaction rollback-only and lose the application. The candidate stays unscreened and is
            // picked up again later.
            log.warn("Screening skipped for candidate {} / requisition {}: {}", candidate.getId(), requisition.getId(), e.getMessage());
            activityLogRepository.save(AgentActivityLog.builder()
                    .agentName(AGENT_NAME)
                    .candidateId(candidate.getId())
                    .requisitionId(requisition.getId())
                    .action("SCREENING_FAILED")
                    .details(e.getMessage())
                    .build());
        }
    }

    private ScreeningResult runScreening(Candidate candidate, JobRequisition requisition) {
        return runScreening(candidate, requisition, true);
    }

    private ScreeningResult runScreening(Candidate candidate, JobRequisition requisition, boolean allowReject) {
        String userPrompt = buildUserPrompt(candidate, requisition);

        ClaudeScreeningResponse parsed;
        try {
            parsed = com.talentai.common.util.AiCallRetry.withRetry(
                    () -> {
                        var chatResponse = chatClient.prompt()
                                .system(promptLoader.load("screening-agent"))
                                .user(userPrompt)
                                .call()
                                .chatResponse();
                        String raw = com.talentai.common.util.AiJsonExtractor.extractText(chatResponse);
                        return com.talentai.common.util.AiJsonExtractor.parse(raw, ClaudeScreeningResponse.class);
                    },
                    log, "Screening Agent", "candidate " + candidate.getId() + " / requisition " + requisition.getId());
        } catch (Exception e) {
            log.error("Failed to get or parse Screening Agent response for candidate {}", candidate.getId(), e);
            throw new IllegalStateException("Screening Agent returned an unexpected response format.");
        }

        ScreeningResult result = screeningResultRepository
                .findByCandidateIdAndRequisitionId(candidate.getId(), requisition.getId())
                .orElse(ScreeningResult.builder()
                        .candidateId(candidate.getId())
                        .requisitionId(requisition.getId())
                        .build());

        result.setOverallScore(nullSafe(parsed.getOverallScore()));
        result.setSkillsScore(nullSafe(parsed.getSkillsScore()));
        result.setExperienceScore(nullSafe(parsed.getExperienceScore()));
        result.setCultureFitScore(nullSafe(parsed.getCultureFitScore()));
        result.setStrengths(parsed.getStrengths());
        result.setGaps(parsed.getGaps());
        result.setRationale(parsed.getRationale());
        result.setRecommendation(normalizeRecommendation(parsed.getRecommendation()));
        result.setIsEdgeCase(Boolean.TRUE.equals(parsed.getIsEdgeCase()) || "REVIEW".equals(result.getRecommendation()));

        result = screeningResultRepository.save(result);

        updatePipelineStage(candidate.getId(), requisition.getId(), result, allowReject);
        logActivity(candidate.getId(), requisition.getId(), result);


        return result;
    }

    private String buildUserPrompt(Candidate candidate, JobRequisition requisition) {
        return """
                ## Job Requisition
                Title: %s
                Department: %s
                Experience Level: %s
                Required Skills: %s

                Job Description:
                %s

                ## Candidate Resume
                Name: %s

                Resume Content:
                %s
                """.formatted(
                requisition.getTitle(),
                nullToDash(requisition.getDepartment()),
                nullToDash(requisition.getExperienceLevel()),
                nullToDash(requisition.getRequiredSkills()),
                requisition.getDescription(),
                candidate.getFullName(),
                candidate.getResumeText()
        );
    }

    private void updatePipelineStage(Long candidateId, Long requisitionId, ScreeningResult result, boolean allowReject) {
        PipelineStage stage = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(candidateId, requisitionId)
                .orElse(PipelineStage.builder()
                        .candidateId(candidateId)
                        .requisitionId(requisitionId)
                        .build());

        // Auto-reject clear REJECT decisions; edge cases and REVIEW stay at SCREENED for human review
        String newStage = (allowReject
                && "REJECT".equals(result.getRecommendation())
                && !Boolean.TRUE.equals(result.getIsEdgeCase()))
                ? "REJECTED" : "SCREENED";

        stage.setStage(newStage);
        stage.setUpdatedByAgent(AGENT_NAME);
        stage.setNotes("Screening recommendation: " + result.getRecommendation()
                + " (overall score: " + result.getOverallScore() + ")");
        pipelineStageRepository.save(stage);
    }

    private void logActivity(Long candidateId, Long requisitionId, ScreeningResult result) {
        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidateId)
                .requisitionId(requisitionId)
                .action("SCREENED_CANDIDATE")
                .details("Recommendation: " + result.getRecommendation()
                        + ", overall score: " + result.getOverallScore()
                        + (Boolean.TRUE.equals(result.getIsEdgeCase()) ? " [EDGE CASE - flagged for human review]" : ""))
                .build());
    }

    /**
     * Allows a human recruiter to make the final ADVANCE/REJECT decision
     * on a screening result, particularly for edge cases flagged as REVIEW.
     */
    @Transactional
    public ScreeningResultResponse recordReviewerDecision(Long screeningResultId, ReviewDecisionRequest request, Long reviewerUserId) {
        ScreeningResult result = screeningResultRepository.findById(screeningResultId)
                .orElseThrow(() -> new IllegalArgumentException("Screening result not found: " + screeningResultId));

        String decision = normalizeRecommendation(request.getDecision());
        if (!decision.equals("ADVANCE") && !decision.equals("REJECT")) {
            throw new IllegalArgumentException("Reviewer decision must be ADVANCE or REJECT");
        }

        result.setReviewerDecision(decision);
        result.setReviewerNotes(request.getNotes());
        result.setReviewedBy(reviewerUserId);
        result = screeningResultRepository.save(result);

        PipelineStage stage = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(result.getCandidateId(), result.getRequisitionId())
                .orElseThrow(() -> new IllegalStateException("Pipeline stage not found for candidate"));

        stage.setStage(decision.equals("ADVANCE") ? "SHORTLISTED" : "REJECTED");
        stage.setUpdatedByAgent("HUMAN");
        String reviewerNotes = request.getNotes();
        stage.setEnteredAt(java.time.LocalDateTime.now());
        // A rejection stores the recruiter's reason as-is so it reads cleanly on the rejected card
        stage.setNotes(decision.equals("REJECT") && reviewerNotes != null && !reviewerNotes.isBlank()
                ? reviewerNotes.trim()
                : "Human reviewer decision: " + decision + (reviewerNotes != null ? " - " + reviewerNotes : ""));
        pipelineStageRepository.save(stage);

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(result.getCandidateId())
                .requisitionId(result.getRequisitionId())
                .action("HUMAN_REVIEW_DECISION")
                .details("Decision: " + decision + (request.getNotes() != null ? " - " + request.getNotes() : ""))
                .build());

        Candidate candidate = candidateRepository.findById(result.getCandidateId())
                .orElseThrow(() -> new IllegalStateException("Candidate not found"));
        JobRequisition requisition = jobRequisitionRepository.findById(result.getRequisitionId())
                .orElseThrow(() -> new IllegalStateException("Requisition not found"));

        return toResponse(result, candidate, requisition);
    }

    /** Screen a candidate from a resume file across all open requisitions. */
    @Transactional
    public List<ScreeningResultResponse> screenCandidateFromFileAllOpenRoles(
            String candidateName, String candidateEmail, MultipartFile resumeFile) throws IOException {

        List<JobRequisition> openRoles = jobRequisitionRepository.findAll().stream()
                .filter(r -> "OPEN".equalsIgnoreCase(r.getStatus()))
                .toList();

        if (openRoles.isEmpty()) {
            throw new IllegalStateException("No open requisitions found to screen against.");
        }

        String resumeText = pdfTextExtractor.extractText(resumeFile);
        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("Could not extract any text from the uploaded resume file.");
        }

        Candidate candidate = findOrCreateCandidate(
                candidateName, candidateEmail, resumeText, resumeFile.getOriginalFilename(), "UPLOAD");

        return openRoles.stream()
                .map(req -> {
                    try {
                        ScreeningResult r = runScreening(candidate, req);
                        return toResponse(r, candidate, req);
                    } catch (Exception e) {
                        log.warn("Screening failed for req {}: {}", req.getId(), e.getMessage());
                        return null;
                    }
                })
                .filter(r -> r != null)
                .toList();
    }

    public List<ScreeningResultResponse> getEdgeCases(Long requisitionId) {
        List<ScreeningResult> results = (requisitionId != null)
                ? screeningResultRepository.findByRequisitionIdAndIsEdgeCaseTrue(requisitionId)
                : screeningResultRepository.findByIsEdgeCaseTrue();

        return results.stream()
                .filter(r -> r.getReviewerDecision() == null) // not yet resolved by human
                .map(r -> {
                    Candidate candidate = candidateRepository.findById(r.getCandidateId()).orElse(null);
                    JobRequisition req = jobRequisitionRepository.findById(r.getRequisitionId()).orElse(null);
                    return toResponse(r, candidate, req);
                })
                .toList();
    }

    /** Recruiter-triggered rejection email for a specific screening result. */
    public void sendRejectionEmail(Long screeningResultId) {
        ScreeningResult result = screeningResultRepository.findById(screeningResultId)
                .orElseThrow(() -> new IllegalArgumentException("Screening result not found: " + screeningResultId));
        Candidate candidate = candidateRepository.findById(result.getCandidateId())
                .orElseThrow(() -> new IllegalStateException("Candidate not found"));
        JobRequisition requisition = jobRequisitionRepository.findById(result.getRequisitionId())
                .orElseThrow(() -> new IllegalStateException("Requisition not found"));

        if (candidate.getEmail() == null || candidate.getEmail().isBlank()) {
            throw new IllegalStateException("Candidate has no email address on file");
        }

        // Save the log record synchronously so the button flips immediately on reload,
        // even though the actual SMTP send is async.
        String subject = "Application Update – " + requisition.getTitle();
        notificationLogRepository.save(com.talentai.common.entity.NotificationLog.builder()
                .candidateId(candidate.getId())
                .requisitionId(requisition.getId())
                .notificationType("REJECTION")
                .recipientEmail(candidate.getEmail())
                .subject(subject)
                .status("SENT")
                .createdAt(java.time.LocalDateTime.now())
                .build());

        emailService.sendRejectionEmail(
                candidate.getId(), requisition.getId(),
                candidate.getEmail(), candidate.getFullName(),
                requisition.getTitle(), false);
    }

    public ScreeningResultResponse getResultById(Long id) {
        ScreeningResult r = screeningResultRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Screening result not found: " + id));
        Candidate candidate = candidateRepository.findById(r.getCandidateId()).orElse(null);
        JobRequisition req = jobRequisitionRepository.findById(r.getRequisitionId()).orElse(null);
        return toResponse(r, candidate, req);
    }

    public List<ScreeningResultResponse> getResultsForRequisition(Long requisitionId) {
        return screeningResultRepository.findByRequisitionId(requisitionId).stream()
                .map(r -> {
                    Candidate candidate = candidateRepository.findById(r.getCandidateId()).orElse(null);
                    JobRequisition req = jobRequisitionRepository.findById(r.getRequisitionId()).orElse(null);
                    return toResponse(r, candidate, req);
                })
                .toList();
    }

    private ScreeningResultResponse toResponse(ScreeningResult result, Candidate candidate, JobRequisition requisition) {
        var stageEntity = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(result.getCandidateId(), result.getRequisitionId())
                .orElse(null);
        String pipelineStage = stageEntity != null ? stageEntity.getStage() : null;
        String rejectionReason = stageEntity != null && "REJECTED".equals(stageEntity.getStage())
                ? stageEntity.getNotes() : null;

        boolean rejectionEmailSent = notificationLogRepository
                .existsByCandidateIdAndRequisitionIdAndNotificationTypeAndStatus(
                        result.getCandidateId(), result.getRequisitionId(), "REJECTION", "SENT");

        boolean outreachSent = sourcingMatchRepository
                .findByCandidateIdAndRequisitionId(result.getCandidateId(), result.getRequisitionId())
                .map(m -> "OUTREACH_SENT".equals(m.getStatus()))
                .orElse(false);

        return ScreeningResultResponse.builder()
                .id(result.getId())
                .candidateId(result.getCandidateId())
                .candidateName(candidate != null ? candidate.getFullName() : null)
                .requisitionId(result.getRequisitionId())
                .requisitionTitle(requisition != null ? requisition.getTitle() : null)
                .overallScore(result.getOverallScore())
                .skillsScore(result.getSkillsScore())
                .experienceScore(result.getExperienceScore())
                .cultureFitScore(result.getCultureFitScore())
                .strengths(result.getStrengths())
                .gaps(result.getGaps())
                .rationale(result.getRationale())
                .recommendation(result.getRecommendation())
                .isEdgeCase(result.getIsEdgeCase())
                .reviewerDecision(result.getReviewerDecision())
                .reviewerNotes(result.getReviewerNotes())
                .pipelineStage(pipelineStage)
                .rejectionReason(rejectionReason)
                .createdAt(result.getCreatedAt() != null ? result.getCreatedAt() : LocalDateTime.now())
                .rejectionEmailSent(rejectionEmailSent)
                .outreachSent(outreachSent)
                .build();
    }

    private String normalizeRecommendation(String value) {
        if (value == null) return "REVIEW";
        String upper = value.trim().toUpperCase();
        return switch (upper) {
            case "ADVANCE", "REJECT", "REVIEW" -> upper;
            default -> "REVIEW";
        };
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private String nullToDash(String value) {
        return (value == null || value.isBlank()) ? "Not specified" : value;
    }
}
