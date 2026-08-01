package com.talentai.admin.controller;

import com.talentai.admin.dto.AdminDtos.*;
import com.talentai.admin.service.AdminAgentService;
import com.talentai.common.repository.AgentActivityLogRepository;
import com.talentai.common.repository.PipelineStageRepository;
import com.talentai.referral.repository.ReferralRepository;
import com.talentai.screening.repository.ScreeningResultRepository;
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

    /** Delete all sourcing, screening, referral, pipeline, and activity log records. */
    @DeleteMapping("/cleanup")
    public ResponseEntity<Map<String, String>> cleanup() {
        sourcingMatchRepository.deleteAll();
        screeningResultRepository.deleteAll();
        referralRepository.deleteAll();
        pipelineStageRepository.deleteAll();
        agentActivityLogRepository.deleteAll();
        return ResponseEntity.ok(Map.of("status", "cleaned"));
    }
}
