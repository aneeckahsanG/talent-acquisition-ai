package com.talentai.orchestrator.controller;

import com.talentai.orchestrator.dto.OrchestratorDtos.*;
import com.talentai.orchestrator.service.OrchestratorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/orchestrator")
@RequiredArgsConstructor
public class OrchestratorController {

    private final OrchestratorService orchestratorService;

    /** Kanban-style pipeline board for a requisition, showing candidates by stage. */
    @GetMapping("/pipeline/{requisitionId}")
    public ResponseEntity<PipelineBoardResponse> getPipelineBoard(@PathVariable Long requisitionId) {
        return ResponseEntity.ok(orchestratorService.getPipelineBoard(requisitionId));
    }

    /** Move a candidate to a new pipeline stage for a given requisition. */
    @PatchMapping("/pipeline/{requisitionId}/candidates/{candidateId}/stage")
    public ResponseEntity<PipelineCandidateResponse> moveStage(
            @PathVariable Long requisitionId,
            @PathVariable Long candidateId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(orchestratorService.moveStage(requisitionId, candidateId, body.get("stage")));
    }

    /** Schedule an interview and advance the candidate to INTERVIEW_SCHEDULED stage. */
    @PostMapping("/pipeline/{requisitionId}/candidates/{candidateId}/schedule-interview")
    public ResponseEntity<InterviewScheduledResponse> scheduleInterview(
            @PathVariable Long requisitionId,
            @PathVariable Long candidateId,
            @RequestBody ScheduleInterviewRequest body) {
        return ResponseEntity.ok(orchestratorService.scheduleInterview(requisitionId, candidateId, body));
    }

    /** Create an offer and advance candidate to OFFER stage. */
    @PostMapping("/pipeline/{requisitionId}/candidates/{candidateId}/offer")
    public ResponseEntity<OfferResponse> createOffer(
            @PathVariable Long requisitionId,
            @PathVariable Long candidateId,
            @RequestBody CreateOfferRequest body) {
        return ResponseEntity.ok(orchestratorService.createOffer(requisitionId, candidateId, body));
    }

    /** Update offer terms during negotiation (records history entry). */
    @PatchMapping("/offers/{offerId}/terms")
    public ResponseEntity<OfferResponse> updateTerms(
            @PathVariable Long offerId,
            @RequestBody UpdateOfferTermsRequest body) {
        return ResponseEntity.ok(orchestratorService.updateOfferTerms(offerId, body));
    }

    /** Generate an offer letter for the given offer using Claude. */
    @GetMapping("/offers/{offerId}/letter")
    public ResponseEntity<OfferLetterResponse> generateLetter(@PathVariable Long offerId) {
        return ResponseEntity.ok(orchestratorService.generateOfferLetter(offerId));
    }

    /** Send the offer letter email to the candidate. */
    @PostMapping("/offers/{offerId}/send-email")
    public ResponseEntity<Void> sendOfferEmail(
            @PathVariable Long offerId,
            @RequestBody Map<String, String> body) {
        orchestratorService.sendOfferEmail(offerId, body.get("letterText"));
        return ResponseEntity.noContent().build();
    }

    /** Update offer status (SENT, ACCEPTED, DECLINED, NEGOTIATING). */
    @PatchMapping("/offers/{offerId}/status")
    public ResponseEntity<OfferResponse> updateOfferStatus(
            @PathVariable Long offerId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(orchestratorService.updateOfferStatus(offerId, body.get("status")));
    }

    /** Mark an interview round as completed. */
    @PatchMapping("/interviews/{interviewId}/complete")
    public ResponseEntity<InterviewRoundResponse> completeRound(@PathVariable Long interviewId) {
        return ResponseEntity.ok(orchestratorService.completeInterviewRound(interviewId));
    }

    /** Simulate a candidate replying to their interview invitation. */
    @PostMapping("/interviews/{interviewId}/simulate-response")
    public ResponseEntity<InterviewRoundResponse> simulateResponse(@PathVariable Long interviewId) {
        return ResponseEntity.ok(orchestratorService.simulateCandidateResponse(interviewId));
    }

    /** Send the invitation email to the candidate (recruiter clicks Send after reviewing draft). */
    @PostMapping("/interviews/{interviewId}/send-invitation")
    public ResponseEntity<Void> sendInvitation(@PathVariable Long interviewId) {
        orchestratorService.sendInvitationEmail(interviewId);
        return ResponseEntity.noContent().build();
    }

    /** Update the invitation email text (recruiter edits before sending). */
    @PatchMapping("/interviews/{interviewId}/invitation-email")
    public ResponseEntity<Void> updateInvitationEmail(
            @PathVariable Long interviewId,
            @RequestBody Map<String, String> body) {
        orchestratorService.updateInvitationEmail(interviewId, body.get("invitationEmail"));
        return ResponseEntity.noContent().build();
    }

    /** Record an actual candidate reply entered by the recruiter. */
    @PatchMapping("/interviews/{interviewId}/record-response")
    public ResponseEntity<InterviewRoundResponse> recordResponse(
            @PathVariable Long interviewId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(orchestratorService.recordCandidateResponse(interviewId, body.get("reply")));
    }

    /** Reschedule an interview to a new date/time and regenerate the invitation email. */
    @PatchMapping("/interviews/{interviewId}/reschedule")
    public ResponseEntity<InterviewScheduledResponse> reschedule(
            @PathVariable Long interviewId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(orchestratorService.rescheduleInterview(interviewId, body.get("confirmedSlot")));
    }

    /** Cross-agent activity feed, optionally filtered by agent. */
    @GetMapping("/activity")
    public ResponseEntity<List<ActivityLogEntryResponse>> getActivity(
            @RequestParam(required = false) String agent,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(orchestratorService.getRecentActivity(agent, limit));
    }

    /** Dashboard-level KPI summary. */
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardSummaryResponse> getDashboard() {
        return ResponseEntity.ok(orchestratorService.getDashboardSummary());
    }
}
