package com.talentai.sourcing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
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
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.dto.SourcingDtos.*;
import com.talentai.sourcing.entity.SourcingMatch;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import com.talentai.common.service.RequisitionService.RequisitionCreatedEvent;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SOURCING AGENT
 *
 * Responsibilities:
 *  - Maintain a talent pool of candidates (sourced proactively from
 *    channels such as LinkedIn/JobStreet - represented here via the
 *    talent pool upload endpoint, since live platform APIs require
 *    partner access not available in this prototype).
 *  - Continuously match talent pool candidates against open requisitions
 *    using Claude to assess fit, producing a match score and rationale.
 *  - Flag high-fit candidates so recruiters can proactively reach out.
 *
 * Human-in-the-loop: recruiters review and approve outreach to
 * proactively sourced candidates rather than the agent contacting
 * candidates directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SourcingAgentService {

    private static final String AGENT_NAME = "SOURCING";
    private static final BigDecimal HIGH_FIT_THRESHOLD = new BigDecimal("70");

    private final ClaudeApiClient claudeApiClient;
    private final PdfTextExtractor pdfTextExtractor;
    private final CandidateRepository candidateRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final SourcingMatchRepository sourcingMatchRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final AgentActivityLogRepository activityLogRepository;
    private final ScreeningAgentService screeningAgentService;
    private final com.talentai.common.service.EmailService emailService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are the Sourcing Agent within an Agentic AI Talent Sourcing platform.

            Your task is to assess how well a candidate profile fits an open job
            requisition, to support PROACTIVE talent discovery - identifying strong
            candidates for a role even when they haven't applied.

            Provide:
            - matchScore: 0-100, reflecting overall fit based on skills, experience
              level, and role relevance.
            - rationale: a concise 1-2 sentence explanation of why this candidate is
              (or isn't) a good fit, highlighting the most relevant matching points.

            IMPORTANT: Respond with ONLY a single JSON object, no markdown fences, no
            preamble, no commentary. The JSON must exactly match this shape:

            {
              "matchScore": number,
              "rationale": "string"
            }
            """;

    /**
     * Adds a candidate to the talent pool (simulating proactive sourcing
     * from external channels) and immediately evaluates fit against all
     * currently open requisitions.
     */
    @Transactional
    public List<SourcingMatchResponse> addCandidateAndMatch(AddTalentPoolCandidateRequest request) {
        // Reuse existing candidate record if same email already exists
        Candidate existing = (request.getEmail() != null && !request.getEmail().isBlank())
                ? candidateRepository.findByEmail(request.getEmail()).orElse(null)
                : null;

        if (existing != null) {
            List<JobRequisition> openReqs = jobRequisitionRepository.findByStatus("OPEN");
            boolean alreadyMatched = openReqs.stream().anyMatch(req ->
                    sourcingMatchRepository.findByCandidateIdAndRequisitionId(existing.getId(), req.getId()).isPresent());
            if (alreadyMatched) {
                throw new IllegalArgumentException("Candidate with email " + request.getEmail()
                        + " has already been uploaded. Duplicate upload is not allowed.");
            }
        }

        final Candidate candidate;
        if (existing != null) {
            existing.setFullName(request.getFullName());
            if (request.getResumeText() != null) existing.setResumeText(request.getResumeText());
            if (request.getHeadline() != null) existing.setHeadline(request.getHeadline());
            if (request.getSkills() != null) existing.setSkills(request.getSkills());
            candidate = candidateRepository.save(existing);
        } else {
            candidate = candidateRepository.save(Candidate.builder()
                    .fullName(request.getFullName())
                    .email(request.getEmail())
                    .headline(request.getHeadline())
                    .resumeText(request.getResumeText())
                    .skills(request.getSkills())
                    .yearsExperience(request.getYearsExperience())
                    .profileUrl(request.getProfileUrl())
                    .sourceChannel(request.getSourceChannel() != null ? request.getSourceChannel() : "MANUAL")
                    .build());
        }

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidate.getId())
                .action("ADDED_TO_TALENT_POOL")
                .details("Source channel: " + candidate.getSourceChannel())
                .build());

        List<JobRequisition> openRequisitions = jobRequisitionRepository.findByStatus("OPEN");

        boolean autoScreen = !Boolean.FALSE.equals(request.getHasResume());
        return openRequisitions.stream()
                .map(req -> matchCandidateToRequisition(candidate, req, autoScreen))
                .toList();
    }

    /**
     * Triggered after a new requisition's transaction commits.
     * Scans the entire talent pool and matches every candidate against the new role.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void scanTalentPoolForNewRequisition(RequisitionCreatedEvent event) {
        Long requisitionId = event.requisitionId();
        JobRequisition requisition = jobRequisitionRepository.findById(requisitionId).orElse(null);
        if (requisition == null) return;

        List<Candidate> allCandidates = candidateRepository.findAll();
        log.info("Proactive scan: matching {} candidates against new requisition '{}'",
                allCandidates.size(), requisition.getTitle());

        int matched = 0;
        for (Candidate candidate : allCandidates) {
            try {
                boolean hasResume = candidate.getResumeText() != null && !candidate.getResumeText().isBlank();
                matchCandidateToRequisition(candidate, requisition, hasResume);
                matched++;
            } catch (Exception e) {
                log.warn("Proactive scan: failed to match candidate {} against requisition {}: {}",
                        candidate.getId(), requisitionId, e.getMessage());
            }
        }

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .requisitionId(requisitionId)
                .action("PROACTIVE_SCAN_COMPLETE")
                .details("Scanned " + allCandidates.size() + " candidates; " + matched + " matched against '" + requisition.getTitle() + "'")
                .build());
    }

    /**
     * Re-runs matching for an existing candidate against all open requisitions.
     * Useful when new requisitions open after a candidate was sourced.
     */
    @Transactional
    public List<SourcingMatchResponse> matchExistingCandidateToOpenRoles(Long candidateId) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + candidateId));

        List<JobRequisition> openRequisitions = jobRequisitionRepository.findByStatus("OPEN");

        return openRequisitions.stream()
                .map(req -> matchCandidateToRequisition(candidate, req, true))
                .toList();
    }

    private SourcingMatchResponse matchCandidateToRequisition(Candidate candidate, JobRequisition requisition, boolean autoScreen) {
        String userPrompt = """
                ## Job Requisition
                Title: %s
                Required Skills: %s
                Experience Level: %s

                Description:
                %s

                ## Candidate Profile
                Name: %s
                Headline: %s
                Skills: %s
                Years of Experience: %s
                Profile Summary:
                %s
                """.formatted(
                requisition.getTitle(),
                nullToDash(requisition.getRequiredSkills()),
                nullToDash(requisition.getExperienceLevel()),
                requisition.getDescription(),
                candidate.getFullName(),
                nullToDash(candidate.getHeadline()),
                nullToDash(candidate.getSkills()),
                candidate.getYearsExperience() != null ? candidate.getYearsExperience().toString() : "Not specified",
                nullToDash(candidate.getResumeText())
        );

        String rawResponse = claudeApiClient.sendPrompt(SYSTEM_PROMPT, userPrompt);
        String json = claudeApiClient.stripJsonFences(rawResponse);

        ClaudeMatchResponse parsed;
        try {
            parsed = objectMapper.readValue(json, ClaudeMatchResponse.class);
        } catch (Exception e) {
            log.error("Failed to parse Sourcing Agent response for candidate {} / requisition {}: {}",
                    candidate.getId(), requisition.getId(), json, e);
            throw new IllegalStateException("Sourcing Agent returned an unexpected response format.");
        }

        final SourcingMatch match = sourcingMatchRepository
                .findByCandidateIdAndRequisitionId(candidate.getId(), requisition.getId())
                .orElse(SourcingMatch.builder()
                        .candidateId(candidate.getId())
                        .requisitionId(requisition.getId())
                        .build());

        match.setMatchScore(parsed.getMatchScore() != null ? parsed.getMatchScore() : BigDecimal.ZERO);
        match.setMatchRationale(parsed.getRationale());
        match.setIsProactive(true);
        if (match.getStatus() == null) match.setStatus("NEW");

        sourcingMatchRepository.save(match);

        // If high-fit, ensure candidate has a SOURCED pipeline entry for visibility
        if (match.getMatchScore().compareTo(HIGH_FIT_THRESHOLD) >= 0) {
            pipelineStageRepository.findByCandidateIdAndRequisitionId(candidate.getId(), requisition.getId())
                    .orElseGet(() -> pipelineStageRepository.save(PipelineStage.builder()
                            .candidateId(candidate.getId())
                            .requisitionId(requisition.getId())
                            .stage("SOURCED")
                            .updatedByAgent(AGENT_NAME)
                            .notes("High-fit match identified by Sourcing Agent (score: " + match.getMatchScore() + ")")
                            .build()));
        }

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidate.getId())
                .requisitionId(requisition.getId())
                .action("MATCHED_CANDIDATE")
                .details("Match score: " + match.getMatchScore()
                        + (match.getMatchScore().compareTo(HIGH_FIT_THRESHOLD) >= 0 ? " [HIGH FIT]" : ""))
                .build());

        // Auto-screen only when a real resume was provided (not just a LinkedIn profile summary)
        if (autoScreen && candidate.getResumeText() != null && !candidate.getResumeText().isBlank()) {
            try {
                screeningAgentService.runScreeningInternal(candidate, requisition);
            } catch (Exception e) {
                log.warn("Auto-screening failed for candidate {} / requisition {}: {}",
                        candidate.getId(), requisition.getId(), e.getMessage());
            }
        }

        return toResponse(match, candidate, requisition);
    }

    private static final String OUTREACH_SYSTEM_PROMPT = """
            You are a recruiter writing a personalized outreach message to a proactively sourced candidate.

            Write a short, warm, professional message suitable for LinkedIn or email (3-4 sentences).

            Rules:
            - Address the candidate by first name only
            - Reference 1-2 specific details from their background that stand out
            - Mention the role name and one genuinely exciting aspect of it
            - End with a soft, low-pressure call-to-action
            - Tone: human and direct, not salesy or overly corporate
            - Do NOT include a subject line, greeting label, sign-off, or your name
            - Return ONLY the message body as plain text, no markdown, no quotes
            """;

    /**
     * Returns the match, candidate, and requisition needed to trigger internal screening.
     * Called by SourcingController which then delegates to ScreeningAgentService.
     */
    public record MatchScreeningContext(SourcingMatch match, Candidate candidate, JobRequisition requisition) {}

    public MatchScreeningContext getScreeningContext(Long matchId) {
        SourcingMatch match = sourcingMatchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Sourcing match not found: " + matchId));
        Candidate candidate = candidateRepository.findById(match.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + match.getCandidateId()));
        JobRequisition requisition = jobRequisitionRepository.findById(match.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found: " + match.getRequisitionId()));
        if (candidate.getResumeText() == null || candidate.getResumeText().isBlank()) {
            throw new IllegalStateException("Candidate has no resume text to screen. Please upload a resume file.");
        }
        return new MatchScreeningContext(match, candidate, requisition);
    }

    /**
     * Generates a personalized outreach draft for a sourcing match using Claude.
     * Also marks the match as REVIEWED so the recruiter's action is tracked.
     */
    @Transactional
    public OutreachDraftResponse draftOutreach(Long matchId) {
        SourcingMatch match = sourcingMatchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Sourcing match not found: " + matchId));

        Candidate candidate = candidateRepository.findById(match.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found: " + match.getCandidateId()));

        JobRequisition requisition = jobRequisitionRepository.findById(match.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found: " + match.getRequisitionId()));

        String userPrompt = """
                ## Candidate
                Name: %s
                Headline: %s
                Skills: %s
                Years of Experience: %s
                Background: %s

                ## Role they matched (match score: %s/100)
                Title: %s
                Department: %s
                Description: %s
                Required Skills: %s

                ## Why they matched
                %s
                """.formatted(
                candidate.getFullName(),
                nullToDash(candidate.getHeadline()),
                nullToDash(candidate.getSkills()),
                candidate.getYearsExperience() != null ? candidate.getYearsExperience() : "Not specified",
                nullToDash(candidate.getResumeText()),
                match.getMatchScore(),
                requisition.getTitle(),
                nullToDash(requisition.getDepartment()),
                requisition.getDescription(),
                nullToDash(requisition.getRequiredSkills()),
                nullToDash(match.getMatchRationale())
        );

        String draft = claudeApiClient.sendPrompt(OUTREACH_SYSTEM_PROMPT, userPrompt);

        if ("NEW".equals(match.getStatus())) {
            match.setStatus("REVIEWED");
            sourcingMatchRepository.save(match);
        }

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidate.getId())
                .requisitionId(requisition.getId())
                .action("OUTREACH_DRAFTED")
                .details("Recruiter drafted outreach for " + candidate.getFullName())
                .build());

        return OutreachDraftResponse.builder()
                .matchId(matchId)
                .candidateName(candidate.getFullName())
                .candidateEmail(candidate.getEmail())
                .requisitionTitle(requisition.getTitle())
                .draft(draft.trim())
                .build();
    }

    /** Sends the (possibly edited) outreach email to the candidate. */
    public void sendOutreach(Long matchId, String emailBody) {
        SourcingMatch match = sourcingMatchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Match not found: " + matchId));
        Candidate candidate = candidateRepository.findById(match.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        JobRequisition requisition = jobRequisitionRepository.findById(match.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found"));

        if (candidate.getEmail() == null || candidate.getEmail().isBlank()) {
            throw new IllegalStateException("Candidate has no email address on file");
        }

        String subject = "Exciting Opportunity – " + requisition.getTitle();
        emailService.sendHtml(candidate.getEmail(), subject,
                emailService.wrapInTemplate(emailBody, "TA"));

        match.setStatus("REVIEWED");
        sourcingMatchRepository.save(match);

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName("SOURCING")
                .candidateId(candidate.getId())
                .requisitionId(requisition.getId())
                .action("OUTREACH_SENT")
                .details("Outreach email sent to " + candidate.getEmail())
                .build());
    }

    /**
     * Extracts candidate name and email from a resume file using Claude.
     * Used to auto-fill the upload form before submitting.
     */
    public ResumeParseResponse parseResumeInfo(MultipartFile file) throws IOException {
        String text = pdfTextExtractor.extractText(file);
        if (text.isBlank()) return ResumeParseResponse.builder().build();

        String systemPrompt = """
                You are a resume parser. Extract the candidate's full name and email address.
                Respond with ONLY a JSON object, no markdown fences, no explanation:
                {"name": "string or null", "email": "string or null"}
                """;
        String userPrompt = "Resume:\n" + text.substring(0, Math.min(text.length(), 2000));

        try {
            String raw = claudeApiClient.sendPrompt(systemPrompt, userPrompt);
            String json = claudeApiClient.stripJsonFences(raw);
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            return ResumeParseResponse.builder()
                    .name(node.path("name").isNull() ? null : node.path("name").asText(null))
                    .email(node.path("email").isNull() ? null : node.path("email").asText(null))
                    .build();
        } catch (Exception e) {
            log.warn("Could not parse resume info from file: {}", e.getMessage());
            return ResumeParseResponse.builder().build();
        }
    }

    /**
     * Adds a candidate by uploading a resume file (PDF or plain text).
     * Extracts text from the file, then runs the same matching flow as manual entry.
     */
    @Transactional
    public List<SourcingMatchResponse> addCandidateFromFileAndMatch(
            String fullName, String email, String sourceChannel, MultipartFile file) throws IOException {
        String resumeText = pdfTextExtractor.extractText(file);
        AddTalentPoolCandidateRequest req = new AddTalentPoolCandidateRequest();
        req.setFullName(fullName);
        req.setEmail(email);
        req.setResumeText(resumeText);
        req.setSourceChannel(sourceChannel != null && !sourceChannel.isBlank() ? sourceChannel : "MANUAL");
        return addCandidateAndMatch(req);
    }

    /**
     * Bulk-imports candidates from a CSV file.
     * Each row is processed independently — a single row failure does not abort the rest.
     * Expected header (case-insensitive): fullName, email, headline, skills,
     *   yearsExperience, profileUrl, sourceChannel
     */
    public CsvImportResponse importCandidatesFromCsv(MultipartFile file) throws IOException {
        int success = 0, failure = 0;
        List<String> errors = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            String[] header = reader.readNext();
            if (header == null) {
                return CsvImportResponse.builder().totalRows(0).successCount(0).failureCount(0).errors(errors).build();
            }

            String[] row;
            int rowNum = 1;
            while ((row = reader.readNext()) != null) {
                rowNum++;
                try {
                    AddTalentPoolCandidateRequest req = parseCsvRow(header, row);
                    addCandidateAndMatch(req);
                    success++;
                } catch (Exception e) {
                    failure++;
                    errors.add("Row " + rowNum + ": " + e.getMessage());
                    log.warn("CSV import failed on row {}: {}", rowNum, e.getMessage());
                }
            }
        } catch (CsvValidationException e) {
            throw new IOException("Invalid CSV format: " + e.getMessage(), e);
        }

        return CsvImportResponse.builder()
                .totalRows(success + failure)
                .successCount(success)
                .failureCount(failure)
                .errors(errors)
                .build();
    }

    private AddTalentPoolCandidateRequest parseCsvRow(String[] headers, String[] values) {
        Map<String, String> row = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            row.put(headers[i].trim().toLowerCase().replaceAll("\\s+", ""), i < values.length ? values[i].trim() : "");
        }

        String fullName = row.getOrDefault("fullname", "");
        if (fullName.isBlank()) throw new IllegalArgumentException("fullName is required");

        AddTalentPoolCandidateRequest req = new AddTalentPoolCandidateRequest();
        req.setFullName(fullName);
        req.setEmail(nullIfBlank(row.get("email")));
        req.setHeadline(nullIfBlank(row.get("headline")));
        req.setSkills(nullIfBlank(row.get("skills")));
        req.setProfileUrl(nullIfBlank(row.get("profileurl")));
        String sc = row.getOrDefault("sourcechannel", "CSV");
        req.setSourceChannel(sc.isBlank() ? "CSV" : sc.toUpperCase());
        String ye = row.getOrDefault("yearsexperience", "");
        if (!ye.isBlank()) {
            try { req.setYearsExperience(new BigDecimal(ye)); } catch (NumberFormatException ignored) {}
        }
        // resumeText column is optional — maps to profile summary
        req.setResumeText(nullIfBlank(row.get("resumetext")));
        return req;
    }

    private String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    public List<SourcingMatchResponse> getMatchesForRequisition(Long requisitionId) {
        JobRequisition requisition = jobRequisitionRepository.findById(requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Job requisition not found: " + requisitionId));

        return sourcingMatchRepository.findByRequisitionIdOrderByMatchScoreDesc(requisitionId).stream()
                .map(m -> {
                    Candidate candidate = candidateRepository.findById(m.getCandidateId()).orElse(null);
                    return toResponse(m, candidate, requisition);
                })
                .toList();
    }

    @Transactional
    public SourcingMatchResponse updateMatchStatus(Long matchId, String status) {
        SourcingMatch match = sourcingMatchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Sourcing match not found: " + matchId));

        String normalized = status.toUpperCase();
        if (!List.of("NEW", "REVIEWED", "DISMISSED", "ADVANCED").contains(normalized)) {
            throw new IllegalArgumentException("Status must be one of NEW, REVIEWED, DISMISSED, ADVANCED");
        }
        match.setStatus(normalized);
        match = sourcingMatchRepository.save(match);

        Candidate candidate = candidateRepository.findById(match.getCandidateId()).orElse(null);
        JobRequisition requisition = jobRequisitionRepository.findById(match.getRequisitionId()).orElse(null);

        if ("DISMISSED".equals(normalized)) {
            pipelineStageRepository.findByCandidateIdAndRequisitionId(match.getCandidateId(), match.getRequisitionId())
                    .ifPresent(pipelineStageRepository::delete);
            screeningResultRepository.findByCandidateIdAndRequisitionId(match.getCandidateId(), match.getRequisitionId())
                    .ifPresent(screeningResultRepository::delete);
        }

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(match.getCandidateId())
                .requisitionId(match.getRequisitionId())
                .action("MATCH_STATUS_UPDATED")
                .details("Status set to " + normalized + " by recruiter")
                .build());

        return toResponse(match, candidate, requisition);
    }

    private SourcingMatchResponse toResponse(SourcingMatch match, Candidate candidate, JobRequisition requisition) {
        var builder = SourcingMatchResponse.builder()
                .id(match.getId())
                .candidateId(match.getCandidateId())
                .candidateName(candidate != null ? candidate.getFullName() : null)
                .candidateHeadline(candidate != null ? candidate.getHeadline() : null)
                .sourceChannel(candidate != null ? candidate.getSourceChannel() : null)
                .requisitionId(match.getRequisitionId())
                .requisitionTitle(requisition != null ? requisition.getTitle() : null)
                .matchScore(match.getMatchScore())
                .matchRationale(match.getMatchRationale())
                .isProactive(match.getIsProactive())
                .status(match.getStatus())
                .createdAt(match.getCreatedAt());

        screeningResultRepository
                .findByCandidateIdAndRequisitionId(match.getCandidateId(), match.getRequisitionId())
                .ifPresent(sr -> builder
                        .screeningResultId(sr.getId())
                        .screeningRecommendation(sr.getRecommendation())
                        .screeningScore(sr.getOverallScore()));

        pipelineStageRepository
                .findByCandidateIdAndRequisitionId(match.getCandidateId(), match.getRequisitionId())
                .ifPresent(ps -> builder.pipelineStage(ps.getStage()));

        return builder.build();
    }

    // ── Direct Application (Public Careers Page) ─────────────────────────────

    /**
     * Handles a direct application from the public careers page.
     * Creates/finds the candidate, scores fit against the role, runs screening,
     * but does NOT auto-create a pipeline stage — recruiter must shortlist manually.
     */
    @Transactional
    public DirectApplyResponse directApply(Long requisitionId, DirectApplyRequest request) {
        JobRequisition requisition = jobRequisitionRepository.findById(requisitionId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found or no longer open."));
        if (!"OPEN".equals(requisition.getStatus())) {
            throw new IllegalStateException("This role is no longer accepting applications.");
        }

        String email = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : null;
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required.");

        // Find or create candidate
        Candidate candidate = candidateRepository.findFirstByEmailOrderByIdAsc(email).orElseGet(() ->
                candidateRepository.save(Candidate.builder()
                        .fullName(request.getFullName())
                        .email(email)
                        .headline(request.getHeadline())
                        .skills(request.getSkills())
                        .yearsExperience(request.getYearsExperience())
                        .resumeText(buildResumeText(request))
                        .sourceChannel("DIRECT_APPLY")
                        .build()));

        // Update candidate info if they already exist
        if (candidate.getId() != null && candidate.getSourceChannel() != null && !"DIRECT_APPLY".equals(candidate.getSourceChannel())) {
            // Candidate exists from another channel — update resume if provided
            if (request.getResumeText() != null && !request.getResumeText().isBlank()) {
                candidate.setResumeText(buildResumeText(request));
                candidate = candidateRepository.save(candidate);
            }
        }

        // Duplicate application guard
        final Long candidateId = candidate.getId();
        if (sourcingMatchRepository.findByCandidateIdAndRequisitionIdAndIsProactiveFalse(candidateId, requisitionId).isPresent()) {
            throw new IllegalStateException("You have already applied for this role.");
        }

        // AI match scoring
        String userPrompt = buildMatchPrompt(candidate, requisition);
        ClaudeMatchResponse scored;
        try {
            String raw = claudeApiClient.sendPrompt(SYSTEM_PROMPT, userPrompt);
            scored = objectMapper.readValue(claudeApiClient.stripJsonFences(raw), ClaudeMatchResponse.class);
        } catch (Exception e) {
            log.warn("Match scoring failed for direct apply candidate {} / req {}: {}", candidateId, requisitionId, e.getMessage());
            scored = new ClaudeMatchResponse(BigDecimal.valueOf(50), "AI scoring unavailable at this time.");
        }

        // Create the match — isProactive=false marks it as a direct application
        SourcingMatch match = sourcingMatchRepository.save(SourcingMatch.builder()
                .candidateId(candidateId)
                .requisitionId(requisitionId)
                .matchScore(scored.getMatchScore() != null ? scored.getMatchScore() : BigDecimal.ZERO)
                .matchRationale(scored.getRationale())
                .isProactive(false)
                .status("APPLIED")
                .build());

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidateId)
                .requisitionId(requisitionId)
                .action("DIRECT_APPLICATION_RECEIVED")
                .details(candidate.getFullName() + " applied via public careers page (score: " + match.getMatchScore() + ")")
                .build());

        // Run screening async — result stays as recommendation only, no auto-advance
        final Candidate finalCandidate = candidate;
        if (finalCandidate.getResumeText() != null && !finalCandidate.getResumeText().isBlank()) {
            try {
                screeningAgentService.runScreeningInternal(finalCandidate, requisition);
            } catch (Exception e) {
                log.warn("Screening failed for direct applicant {} / req {}: {}", candidateId, requisitionId, e.getMessage());
            }
        }

        return DirectApplyResponse.builder()
                .message("Application submitted successfully. We will be in touch.")
                .matchId(match.getId())
                .build();
    }

    /** Returns direct applicants for a requisition (isProactive=false), ordered by application date. */
    public List<SourcingMatchResponse> getApplicantsForRequisition(Long requisitionId) {
        JobRequisition requisition = jobRequisitionRepository.findById(requisitionId).orElse(null);
        return sourcingMatchRepository
                .findByRequisitionIdAndIsProactiveFalseOrderByCreatedAtDesc(requisitionId).stream()
                .map(m -> {
                    Candidate c = candidateRepository.findById(m.getCandidateId()).orElse(null);
                    return toResponse(m, c, requisition);
                }).toList();
    }

    /**
     * Recruiter shortlists a direct applicant — moves them into the pipeline at SCREENING stage.
     * Status becomes SHORTLISTED; a PipelineStage is created.
     */
    @Transactional
    public SourcingMatchResponse shortlistApplicant(Long matchId) {
        SourcingMatch match = sourcingMatchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + matchId));
        Candidate candidate = candidateRepository.findById(match.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        JobRequisition requisition = jobRequisitionRepository.findById(match.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found"));

        match.setStatus("SHORTLISTED");
        sourcingMatchRepository.save(match);

        pipelineStageRepository.findByCandidateIdAndRequisitionId(candidate.getId(), requisition.getId())
                .orElseGet(() -> pipelineStageRepository.save(PipelineStage.builder()
                        .candidateId(candidate.getId())
                        .requisitionId(requisition.getId())
                        .stage("SCREENING")
                        .updatedByAgent("RECRUITER")
                        .notes("Shortlisted from direct application by recruiter")
                        .build()));

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidate.getId())
                .requisitionId(requisition.getId())
                .action("APPLICANT_SHORTLISTED")
                .details("Recruiter shortlisted " + candidate.getFullName() + " from direct application")
                .build());

        return toResponse(match, candidate, requisition);
    }

    /**
     * Recruiter rejects a direct applicant — sends rejection email and sets status to REJECTED.
     */
    @Transactional
    public SourcingMatchResponse rejectApplicant(Long matchId) {
        SourcingMatch match = sourcingMatchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + matchId));
        Candidate candidate = candidateRepository.findById(match.getCandidateId())
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found"));
        JobRequisition requisition = jobRequisitionRepository.findById(match.getRequisitionId())
                .orElseThrow(() -> new IllegalArgumentException("Requisition not found"));

        match.setStatus("REJECTED");
        sourcingMatchRepository.save(match);

        if (candidate.getEmail() != null && !candidate.getEmail().isBlank()) {
            try {
                String body = "Thank you for your application for the <strong>" + requisition.getTitle()
                        + "</strong> position. After careful consideration, we have decided to move forward with other candidates at this time. "
                        + "We appreciate your interest and encourage you to apply for future openings.";
                emailService.sendHtml(candidate.getEmail(),
                        "Your Application for " + requisition.getTitle(),
                        emailService.wrapInTemplate(body, "Talent Acquisition Team"));
            } catch (Exception e) {
                log.warn("Rejection email failed for candidate {}: {}", candidate.getId(), e.getMessage());
            }
        }

        activityLogRepository.save(AgentActivityLog.builder()
                .agentName(AGENT_NAME)
                .candidateId(candidate.getId())
                .requisitionId(requisition.getId())
                .action("APPLICANT_REJECTED")
                .details("Recruiter rejected " + candidate.getFullName() + "'s direct application (rejection email sent)")
                .build());

        return toResponse(match, candidate, requisition);
    }

    private String buildResumeText(DirectApplyRequest r) {
        StringBuilder sb = new StringBuilder();
        if (r.getResumeText() != null && !r.getResumeText().isBlank()) sb.append(r.getResumeText());
        if (r.getCoverLetter() != null && !r.getCoverLetter().isBlank()) {
            if (sb.length() > 0) sb.append("\n\n--- Cover Letter ---\n");
            sb.append(r.getCoverLetter());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String buildMatchPrompt(Candidate candidate, JobRequisition requisition) {
        return """
                ## Job Requisition
                Title: %s
                Required Skills: %s
                Experience Level: %s
                Description:
                %s

                ## Candidate Profile
                Name: %s
                Headline: %s
                Skills: %s
                Years of Experience: %s
                Profile/Resume:
                %s
                """.formatted(
                requisition.getTitle(), nullToDash(requisition.getRequiredSkills()),
                nullToDash(requisition.getExperienceLevel()), requisition.getDescription(),
                candidate.getFullName(), nullToDash(candidate.getHeadline()),
                nullToDash(candidate.getSkills()),
                candidate.getYearsExperience() != null ? candidate.getYearsExperience() : "Not specified",
                nullToDash(candidate.getResumeText()));
    }

    private String nullToDash(String value) {
        return (value == null || value.isBlank()) ? "Not specified" : value;
    }
}
