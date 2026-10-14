package com.talentai.admin.controller;

import com.talentai.admin.dto.AdminDtos.*;
import com.talentai.admin.repository.InterviewScheduleRepository;
import com.talentai.admin.repository.OfferNegotiationHistoryRepository;
import com.talentai.admin.repository.OfferRepository;
import com.talentai.admin.repository.CandidateStatusUpdateRepository;
import com.talentai.admin.service.AdminAgentService;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.NotificationLogRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.orchestrator.agent.AgentRunRepository;
import com.talentai.orchestrator.agent.AgentStepRepository;
import com.talentai.referral.repository.ReferralRepository;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.sourcing.repository.MockApplicationRepository;
import com.talentai.sourcing.repository.SourcingMatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminAgentService adminAgentService;
    private final SourcingMatchRepository sourcingMatchRepository;
    private final ScreeningResultRepository screeningResultRepository;
    private final ReferralRepository referralRepository;
    private final PipelineStageRepository pipelineStageRepository;
    private final AgentActivityLogRepository agentActivityLogRepository;
    private final InterviewScheduleRepository interviewScheduleRepository;
    private final OfferNegotiationHistoryRepository offerNegotiationHistoryRepository;
    private final OfferRepository offerRepository;
    private final CandidateStatusUpdateRepository candidateStatusUpdateRepository;
    private final CandidateRepository candidateRepository;
    private final NotificationLogRepository notificationLogRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentStepRepository agentStepRepository;
    private final MockApplicationRepository mockApplicationRepository;

    /** Propose interview slots + a draft outreach message for a candidate. */
    @PostMapping("/interviews/propose")
    public ResponseEntity<InterviewScheduleResponse> proposeInterview(@RequestBody ProposeInterviewRequest request) {
        return ResponseEntity.ok(adminAgentService.proposeInterview(request));
    }

    /** Recruiter confirms a specific interview slot. */
    @PatchMapping("/interviews/{scheduleId}/confirm")
    public ResponseEntity<InterviewScheduleResponse> confirmSlot(@PathVariable Long scheduleId, @RequestBody ConfirmSlotRequest request) {
        return ResponseEntity.ok(adminAgentService.confirmSlot(scheduleId, request));
    }

    /** Get all interview schedules for a requisition. */
    @GetMapping("/interviews/requisition/{requisitionId}")
    public ResponseEntity<List<InterviewScheduleResponse>> getSchedules(@PathVariable Long requisitionId) {
        return ResponseEntity.ok(adminAgentService.getSchedulesForRequisition(requisitionId));
    }

    /** Send (log) a status update notification to a candidate. */
    @PostMapping("/candidates/{candidateId}/status-update")
    public ResponseEntity<StatusUpdateResponse> sendStatusUpdate(
            @PathVariable Long candidateId, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(adminAgentService.sendStatusUpdate(candidateId, body.get("message"), body.get("channel")));
    }

    /** Get status update history for a candidate. */
    @GetMapping("/candidates/{candidateId}/status-updates")
    public ResponseEntity<List<StatusUpdateResponse>> getStatusUpdates(@PathVariable Long candidateId) {
        return ResponseEntity.ok(adminAgentService.getStatusUpdatesForCandidate(candidateId));
    }

    /** Delete all records except job requisitions, resume sources, mock job roles, and users. */
    @DeleteMapping("/cleanup")
    public ResponseEntity<Map<String, String>> cleanup() {
        agentStepRepository.deleteAll();
        agentRunRepository.deleteAll();
        interviewScheduleRepository.deleteAll();
        offerNegotiationHistoryRepository.deleteAll();
        offerRepository.deleteAll();
        sourcingMatchRepository.deleteAll();
        screeningResultRepository.deleteAll();
        referralRepository.deleteAll();
        pipelineStageRepository.deleteAll();
        candidateStatusUpdateRepository.deleteAll();
        agentActivityLogRepository.deleteAll();
        notificationLogRepository.deleteAll();
        mockApplicationRepository.deleteAll();
        candidateRepository.deleteAll();
        return ResponseEntity.ok(Map.of("status", "cleaned"));
    }
}
