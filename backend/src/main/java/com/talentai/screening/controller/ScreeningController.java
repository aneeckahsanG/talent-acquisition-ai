package com.talentai.screening.controller;

import com.talentai.auth.security.JwtService;
import com.talentai.common.entity.AppUser;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.screening.dto.ScreeningDtos.*;
import com.talentai.screening.service.ScreeningAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/screening")
@RequiredArgsConstructor
public class ScreeningController {

    private final ScreeningAgentService screeningAgentService;
    private final AppUserRepository appUserRepository;

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

    /** Screen a candidate by uploading a resume file (PDF or text) against a requisition. */
    @PostMapping(value = "/evaluate-upload", consumes = "multipart/form-data")
    public ResponseEntity<ScreeningResultResponse> screenCandidateFromFile(
            @RequestParam("candidateName") String candidateName,
            @RequestParam(value = "candidateEmail", required = false) String candidateEmail,
            @RequestParam("requisitionId") Long requisitionId,
            @RequestParam("resume") MultipartFile resume
    ) throws IOException {
        return ResponseEntity.ok(screeningAgentService.screenCandidateFromFile(candidateName, candidateEmail, requisitionId, resume));
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
