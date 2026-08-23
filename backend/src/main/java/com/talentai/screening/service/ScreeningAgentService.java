package com.talentai.screening.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.common.client.ClaudeApiClient;
import com.talentai.common.entity.AgentActivityLog;
import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.entity.PipelineStage;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.common.repository.PipelineStageRepository;
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

    private final ClaudeApiClient claudeApiClient;
    private final PdfTextExtractor pdfTextExtractor;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final com.talentai.common.service.EmailService emailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are the Screening Agent within an Agentic AI Talent Sourcing platform.

            Your task is to evaluate a candidate's resume against a job requisition using a
            STANDARDIZED RUBRIC so that every candidate is assessed consistently, regardless
            of which recruiter or system triggers the evaluation.

            Score the candidate on three dimensions (each 0-100):
            1. skillsScore - alignment between candidate's demonstrated skills and the
               requisition's required skills.
            2. experienceScore - relevance and depth of work experience versus the
               requisition's experience level and responsibilities.
            3. cultureFitScore - signals of collaboration, communication, growth mindset,
               and alignment with general professional best practices (based only on
               resume content - do not invent information).

            Then compute an overallScore (0-100) as a holistic weighted assessment.

            Provide:
            - strengths: 2-4 concise bullet points (as a single string with newline separators)
              highlighting the candidate's strongest matches.
            - gaps: 2-4 concise bullet points (as a single string with newline separators)
              describing gaps versus the requisition. Each bullet in strengths and gaps
              must be a complete, self-contained sentence.
            - rationale: a short paragraph explaining the overall assessment.
            - recommendation: one of "ADVANCE", "REJECT", or "REVIEW".
              Use "REVIEW" for edge cases - e.g., borderline scores (roughly 45-65 overall),
              unusual or non-traditional career paths, conflicting signals between
              dimensions, or any case where you are not confident a simple
              advance/reject is appropriate. Err on the side of REVIEW when uncertain;
              a human recruiter will make the final call on REVIEW cases.
            - isEdgeCase: true if recommendation is "REVIEW", otherwise false.

            IMPORTANT: Respond with ONLY a single JSON object, no markdown fences, no
            preamble, no commentary. The JSON must exactly match this shape:

            {
              "overallScore": number,
              "skillsScore": number,
              "experienceScore": number,
              "cultureFitScore": number,
              "strengths": "string",
              "gaps": "string",
              "rationale": "string",
              "recommendation": "ADVANCE" | "REJECT" | "REVIEW",
              "isEdgeCase": boolean
            }
            """;

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
        // Try regex first for speed
        String email = null;
        java.util.regex.Matcher emailMatcher = java.util.regex.Pattern
                .compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
                .matcher(resumeText);
        if (emailMatcher.find()) email = emailMatcher.group();

        // Use Claude to extract name (and email fallback)
        String prompt = "Extract the candidate's full name and email address from the following resume text. " +
                "Reply ONLY as JSON with keys \"name\" and \"email\". If a field is not found, use an empty string.\n\nResume:\n" +
                resumeText.substring(0, Math.min(resumeText.length(), 2000));
        try {
            String json = claudeApiClient.sendPrompt("You extract structured data from resumes. Return only valid JSON.", prompt);
            // strip markdown code fences if present
            json = json.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = mapper.readValue(json, java.util.Map.class);
            String parsedName = String.valueOf(map.getOrDefault("name", ""));
            String parsedEmail = String.valueOf(map.getOrDefault("email", ""));
            return ParsedResumeResponse.builder()
                    .name(parsedName.isBlank() ? "" : parsedName)
                    .email(parsedEmail.isBlank() ? (email != null ? email : "") : parsedEmail)
                    .resumeText(resumeText)
                    .build();
        } catch (Exception e) {
            return ParsedResumeResponse.builder().name("").email(email != null ? email : "").resumeText(resumeText).build();
        }
    }

    /**
     * Screens a candidate from an uploaded resume file (PDF or text) against a requisition.
     */
    @Transactional
    public ScreeningResultResponse screenCandidateFromFile(
            String candidateName, String candidateEmail, Long requisitionId, MultipartFile resumeFile) throws IOException {

        JobRequisition requisition = jobRequisitionRepository.findById(requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + requisitionId));

        // Block duplicate screening for same candidate + requisition
        if (candidateEmail != null && !candidateEmail.isBlank()) {
            var existingCandidate = candidateRepository.findByEmail(candidateEmail).orElse(null);
            if (existingCandidate != null &&
                    screeningResultRepository.findByCandidateIdAndRequisitionId(existingCandidate.getId(), requisitionId).isPresent()) {
                throw new IllegalArgumentException(
                        "This candidate has already been screened for this role. Duplicate upload is not allowed.");
            }
        }

        String resumeText = pdfTextExtractor.extractText(resumeFile);
        if (resumeText == null || resumeText.isBlank()) {
            throw new IllegalArgumentException("Could not extract any text from the uploaded resume file.");
        }

        Candidate candidate = findOrCreateCandidate(
                candidateName, candidateEmail, resumeText, resumeFile.getOriginalFilename(), "UPLOAD");

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

    /** Called by SourcingAgentService to auto-screen after a match is created.
     *  Never auto-rejects — caps pipeline at SCREENED so a human makes the final call. */
    @Transactional
    public void runScreeningInternal(Candidate candidate, JobRequisition requisition) {
        if (screeningResultRepository.findByCandidateIdAndRequisitionId(candidate.getId(), requisition.getId()).isPresent()) {
            return;
        }
        runScreening(candidate, requisition, false);
    }

    private ScreeningResult runScreening(Candidate candidate, JobRequisition requisition) {
        return runScreening(candidate, requisition, true);
    }

    private ScreeningResult runScreening(Candidate candidate, JobRequisition requisition, boolean allowReject) {
        String userPrompt = buildUserPrompt(candidate, requisition);

        String rawResponse = claudeApiClient.sendPrompt(SYSTEM_PROMPT, userPrompt);
        String json = claudeApiClient.stripJsonFences(rawResponse);

        ClaudeScreeningResponse parsed;
        try {
            parsed = objectMapper.readValue(json, ClaudeScreeningResponse.class);
        } catch (Exception e) {
            log.error("Failed to parse Screening Agent response for candidate {}: {}", candidate.getId(), json, e);
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

        // Always cap at SCREENED — human must confirm any advance or reject
        String newStage = "SCREENED";

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
        stage.setNotes("Human reviewer decision: " + decision
                + (request.getNotes() != null ? " - " + request.getNotes() : ""));
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

        // SHORTLISTED is a silent internal signal (see OrchestratorService);
        // only reject notifies the candidate, same template used everywhere else.
        if (decision.equals("REJECT") && candidate.getEmail() != null && !candidate.getEmail().isBlank()) {
            emailService.sendRejectionEmail(candidate.getEmail(), candidate.getFullName(), requisition.getTitle(), false);
        }

        return toResponse(result, candidate, requisition);
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
        String pipelineStage = pipelineStageRepository
                .findByCandidateIdAndRequisitionId(result.getCandidateId(), result.getRequisitionId())
                .map(com.talentai.common.entity.PipelineStage::getStage)
                .orElse(null);

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
                .createdAt(result.getCreatedAt() != null ? result.getCreatedAt() : LocalDateTime.now())
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
