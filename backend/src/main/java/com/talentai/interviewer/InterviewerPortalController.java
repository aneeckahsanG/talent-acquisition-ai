package com.talentai.interviewer;

import com.talentai.interviewer.InterviewerDtos.InterviewDetail;
import com.talentai.interviewer.InterviewerDtos.InterviewSummary;
import com.talentai.orchestrator.dto.OrchestratorDtos.ScorecardRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/** The only part of the API an INTERVIEWER account can use (besides its own account endpoints). */
@RestController
@RequestMapping("/api/interviewer")
@RequiredArgsConstructor
public class InterviewerPortalController {

    private final InterviewerPortalService portal;

    @GetMapping("/interviews")
    public List<InterviewSummary> myInterviews(Principal principal) {
        return portal.myInterviews(principal.getName());
    }

    @GetMapping("/interviews/{id}")
    public InterviewDetail myInterview(@PathVariable Long id, Principal principal) {
        return portal.myInterview(id, principal.getName());
    }

    @PostMapping("/interviews/{id}/scorecard")
    public ResponseEntity<InterviewDetail> submitScorecard(@PathVariable Long id, @RequestBody ScorecardRequest request,
                                                           Principal principal) {
        return ResponseEntity.ok(portal.submitScorecard(id, request, principal.getName()));
    }
}
