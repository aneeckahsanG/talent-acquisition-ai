package com.talentai.sourcing.controller;

import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.screening.dto.ScreeningDtos.ScreenByTextRequest;
import com.talentai.screening.dto.ScreeningDtos.ScreeningResultResponse;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.sourcing.dto.SourcingDtos.*;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import com.talentai.sourcing.service.LinkedInParseService;
import com.talentai.sourcing.service.SourcingAgentService;
import com.talentai.sourcing.service.SourcingAgentService.MatchScreeningContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sourcing")
@RequiredArgsConstructor
public class SourcingController {

    private final SourcingAgentService sourcingAgentService;
    private final ScreeningAgentService screeningAgentService;
    private final LinkedInParseService linkedInParseService;
    private final SourcingMatchRepository sourcingMatchRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final CandidateRepository candidateRepository;
    private final PipelineStageRepository pipelineStageRepository;

    /** Add a candidate to the talent pool and match against all open requisitions. */
    @PostMapping("/talent-pool")
    public ResponseEntity<List<SourcingMatchResponse>> addCandidate(@RequestBody AddTalentPoolCandidateRequest request) {
        return ResponseEntity.ok(sourcingAgentService.addCandidateAndMatch(request));
    }

    /** Internally screen a sourced candidate's resume before deciding on outreach. */
    @PostMapping("/matches/{matchId}/screen")
    public ResponseEntity<ScreeningResultResponse> screenFromMatch(@PathVariable Long matchId) {
        MatchScreeningContext ctx = sourcingAgentService.getScreeningContext(matchId);
        ScreenByTextRequest req = new ScreenByTextRequest(
                ctx.candidate().getFullName(),
                ctx.candidate().getEmail(),
                ctx.candidate().getResumeText(),
                ctx.requisition().getId()
        );
        return ResponseEntity.ok(screeningAgentService.screenCandidate(req));
    }

    /** Generate a personalized outreach draft for a sourcing match. */
    @PostMapping("/matches/{matchId}/draft-outreach")
    public ResponseEntity<OutreachDraftResponse> draftOutreach(@PathVariable Long matchId) {
        return ResponseEntity.ok(sourcingAgentService.draftOutreach(matchId));
    }

    /** Parse name and email from a resume file for form auto-fill. */
    @PostMapping(value = "/talent-pool/parse-resume", consumes = "multipart/form-data")
    public ResponseEntity<ResumeParseResponse> parseResume(@RequestParam("resume") MultipartFile resume) throws IOException {
        return ResponseEntity.ok(sourcingAgentService.parseResumeInfo(resume));
    }

    /** Add a candidate by uploading their resume file (PDF or plain text). */
    @PostMapping(value = "/talent-pool/upload", consumes = "multipart/form-data")
    public ResponseEntity<List<SourcingMatchResponse>> addCandidateFromFile(
            @RequestParam("candidateName") String candidateName,
            @RequestParam(value = "candidateEmail", required = false) String email,
            @RequestParam(value = "sourceChannel", defaultValue = "MANUAL") String sourceChannel,
            @RequestParam("resume") MultipartFile resume
    ) throws IOException {
        return ResponseEntity.ok(sourcingAgentService.addCandidateFromFileAndMatch(candidateName, email, sourceChannel, resume));
    }

    /** Bulk-import candidates from a CSV file. */
    @PostMapping(value = "/talent-pool/csv", consumes = "multipart/form-data")
    public ResponseEntity<CsvImportResponse> importFromCsv(@RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(sourcingAgentService.importCandidatesFromCsv(file));
    }

    /** Re-run matching for an existing candidate against currently open requisitions. */
    @PostMapping("/talent-pool/{candidateId}/rematch")
    public ResponseEntity<List<SourcingMatchResponse>> rematch(@PathVariable Long candidateId) {
        return ResponseEntity.ok(sourcingAgentService.matchExistingCandidateToOpenRoles(candidateId));
    }

    /** Get sourcing matches for a requisition, ranked by match score. */
    @GetMapping("/requisition/{requisitionId}/matches")
    public ResponseEntity<List<SourcingMatchResponse>> getMatches(@PathVariable Long requisitionId) {
        return ResponseEntity.ok(sourcingAgentService.getMatchesForRequisition(requisitionId));
    }

    /** Recruiter updates match status (e.g., REVIEWED, DISMISSED, ADVANCED). */
    @PatchMapping("/matches/{matchId}/status")
    public ResponseEntity<SourcingMatchResponse> updateStatus(@PathVariable Long matchId, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(sourcingAgentService.updateMatchStatus(matchId, body.get("status")));
    }

    /**
     * Parse a LinkedIn profile — tries to fetch by URL first, falls back to pasted text.
     * Returns structured candidate fields for auto-filling the Add to Talent Pool form.
     */
    @PostMapping("/linkedin/parse")
    public ResponseEntity<LinkedInParseResponse> parseLinkedIn(@RequestBody LinkedInParseRequest request) {
        String profileText = null;
        boolean fetchedFromUrl = false;
        String message = null;

        // Mode 1: Try fetching from URL
        if (request.getUrl() != null && !request.getUrl().isBlank()) {
            profileText = linkedInParseService.fetchProfileText(request.getUrl());
            if (profileText != null) {
                fetchedFromUrl = true;
            } else {
                message = "LinkedIn blocked access. Please paste the profile text below.";
            }
        }

        // Mode 2: Use pasted text
        if (profileText == null && request.getPasteText() != null && !request.getPasteText().isBlank()) {
            profileText = request.getPasteText();
        }

        if (profileText == null || profileText.isBlank()) {
            return ResponseEntity.ok(LinkedInParseResponse.builder()
                    .fetchedFromUrl(false)
                    .message(message != null ? message : "Please provide a LinkedIn URL or paste profile text.")
                    .build());
        }

        Map<String, String> parsed = linkedInParseService.parseProfileText(profileText);
        return ResponseEntity.ok(LinkedInParseResponse.builder()
                .fullName(parsed.getOrDefault("fullName", ""))
                .email(parsed.getOrDefault("email", ""))
                .headline(parsed.getOrDefault("headline", ""))
                .location(parsed.getOrDefault("location", ""))
                .skills(parsed.getOrDefault("skills", ""))
                .resumeText(parsed.getOrDefault("resumeText", ""))
                .fetchedFromUrl(fetchedFromUrl)
                .message(message)
                .build());
    }

    /** Send the (possibly edited) outreach email to the candidate. */
    @PostMapping("/matches/{matchId}/send-outreach")
    public ResponseEntity<Void> sendOutreach(@PathVariable Long matchId, @RequestBody Map<String, String> body) {
        sourcingAgentService.sendOutreach(matchId, body.get("emailBody"));
        return ResponseEntity.noContent().build();
    }

    /** Delete all applications, screening results, pipeline stages, and candidates. */
    @DeleteMapping("/all-applications")
    public ResponseEntity<Map<String, Object>> deleteAllApplications() {
        long matches = sourcingMatchRepository.count();
        long screenings = screeningResultRepository.count();
        long candidates = candidateRepository.count();
        pipelineStageRepository.deleteAll();
        screeningResultRepository.deleteAll();
        sourcingMatchRepository.deleteAll();
        candidateRepository.deleteAll();
        return ResponseEntity.ok(Map.of(
                "deleted", Map.of(
                        "matches", matches,
                        "screeningResults", screenings,
                        "candidates", candidates
                )
        ));
    }
}
