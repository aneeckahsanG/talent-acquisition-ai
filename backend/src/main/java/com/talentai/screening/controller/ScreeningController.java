package com.talentai.screening.controller;

import com.talentai.auth.security.JwtService;
import com.talentai.common.entity.AppUser;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.screening.dto.ScreeningDtos.*;
import com.talentai.screening.service.ScreeningAgentService;
import com.talentai.screening.service.ScreeningProgressTracker;
import com.talentai.screening.service.UploadScreeningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/screening")
@RequiredArgsConstructor
public class ScreeningController {

    private final ScreeningAgentService screeningAgentService;
    private final AppUserRepository appUserRepository;
    private final ScreeningProgressTracker progressTracker;
    private final UploadScreeningService uploadScreeningService;

    /** Screen a candidate by providing resume text directly (e.g., pasted text). */
    @PostMapping("/evaluate")
    public ResponseEntity<ScreeningResultResponse> screenCandidate(@RequestBody ScreenByTextRequest request) {
        return ResponseEntity.ok(screeningAgentService.screenCandidate(request));
    }

    /** Parse a resume file and return the candidate's name and email. */
    @PostMapping(value = "/parse-resume", consumes = "multipart/form-data")
    public ResponseEntity<ParsedResumeResponse> parseResume(
            @RequestParam("resume") MultipartFile resume
    ) throws IOException {
        return ResponseEntity.ok(screeningAgentService.parseResume(resume));
    }

    /**
     * Screen a candidate by uploading a resume file (PDF or text). If
     * requisitionId is omitted (the "All Roles" case), screens against every
     * currently open requisition instead of one specific role.
     * <p>
     * Answers 202 Accepted as soon as the request is checked (a bad file, an unknown role or a
     * duplicate is still a 4xx straight away). The AI screening then runs in the background and
     * shows under {@link #inProgress()} until its result exists, or until it fails.
     */
    @PostMapping(value = "/evaluate-upload", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> screenCandidateFromFile(
            @RequestParam("candidateName") String candidateName,
            @RequestParam(value = "candidateEmail", required = false) String candidateEmail,
            @RequestParam(value = "requisitionId", required = false) Long requisitionId,
            @RequestParam("resume") MultipartFile resume
    ) throws IOException {
        // The request is checked now; the slow AI screening runs in the background (see /in-progress).
        int started = uploadScreeningService.start(candidateName, candidateEmail, requisitionId, resume);
        return ResponseEntity.accepted().body(Map.of("status", "ACCEPTED", "screenings", started));
    }

    /** Applications currently being matched and screened (no result row exists yet). */
    @GetMapping("/in-progress")
    public List<ScreeningProgressTracker.Entry> inProgress() {
        return progressTracker.list();
    }

    /** Get a single screening result by ID. */
    @GetMapping("/results/{id}")
    public ResponseEntity<ScreeningResultResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(screeningAgentService.getResultById(id));
    }

    /** Get all screening results for a requisition (recruiter pipeline view). */
    @GetMapping("/requisition/{requisitionId}")
    public ResponseEntity<List<ScreeningResultResponse>> getResultsForRequisition(@PathVariable Long requisitionId) {
        return ResponseEntity.ok(screeningAgentService.getResultsForRequisition(requisitionId));
    }

    /** Get edge cases flagged for human review, optionally filtered by requisition. */
    @GetMapping("/edge-cases")
    public ResponseEntity<List<ScreeningResultResponse>> getEdgeCases(
            @RequestParam(value = "requisitionId", required = false) Long requisitionId) {
        return ResponseEntity.ok(screeningAgentService.getEdgeCases(requisitionId));
    }

    /** Recruiter manually sends a rejection email to the candidate. */
    @PostMapping("/{screeningResultId}/send-rejection-email")
    public ResponseEntity<Void> sendRejectionEmail(@PathVariable Long screeningResultId) {
        screeningAgentService.sendRejectionEmail(screeningResultId);
        return ResponseEntity.noContent().build();
    }

    /** Human-in-the-loop: record a recruiter's final ADVANCE/REJECT decision on an edge case. */
    @PostMapping("/{screeningResultId}/review")
    public ResponseEntity<ScreeningResultResponse> recordReviewDecision(
            @PathVariable Long screeningResultId,
            @Valid @RequestBody ReviewDecisionRequest request,
            Authentication authentication
    ) {
        Long reviewerUserId = appUserRepository.findByUsername(authentication.getName())
                .map(AppUser::getId)
                .orElse(null);

        return ResponseEntity.ok(screeningAgentService.recordReviewerDecision(screeningResultId, request, reviewerUserId));
    }
}
