package com.talentai.orchestrator.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Recruiter-facing API for agent runs: traces, pending approvals, approve/reject. */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentRunController {

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final RecruitingOrchestratorService orchestratorService;

    /** All agent runs for a requisition, newest first. */
    @GetMapping("/requisition/{requisitionId}/runs")
    public ResponseEntity<List<AgentRun>> runsForRequisition(@PathVariable Long requisitionId) {
        return ResponseEntity.ok(runRepository.findByRequisitionIdOrderByCreatedAtDesc(requisitionId));
    }

    /** Full step-by-step trace of one run. */
    @GetMapping("/runs/{runId}/steps")
    public ResponseEntity<List<AgentStep>> steps(@PathVariable Long runId) {
        return ResponseEntity.ok(stepRepository.findByRunIdOrderByStepNoAsc(runId));
    }

    /** All runs currently waiting for recruiter approval. */
    @GetMapping("/pending-approvals")
    public ResponseEntity<List<AgentRun>> pendingApprovals() {
        return ResponseEntity.ok(runRepository.findByStatusOrderByCreatedAtDesc("PAUSED_FOR_APPROVAL"));
    }

    /** Approve the paused action — the agent executes it and resumes. */
    @PostMapping("/runs/{runId}/approve")
    public ResponseEntity<Map<String, String>> approve(@PathVariable Long runId) {
        orchestratorService.approve(runId);
        return ResponseEntity.ok(Map.of("status", "approved"));
    }

    /** Reject the paused action — the agent is told and adapts. */
    @PostMapping("/runs/{runId}/reject")
    public ResponseEntity<Map<String, String>> reject(@PathVariable Long runId,
                                                      @RequestBody(required = false) Map<String, String> body) {
        orchestratorService.reject(runId, body == null ? null : body.get("reason"));
        return ResponseEntity.ok(Map.of("status", "rejected"));
    }
}
