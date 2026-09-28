package com.talentai.agent.controller;

import com.talentai.agent.service.AgentLoopService;
import com.talentai.agent.service.HumanApprovalGate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentLoopController {

    private final AgentLoopService agentLoopService;
    private final HumanApprovalGate approvalGate;

    /**
     * POST /api/agent/run
     * Body: { "goal": "...", "context": "..." }
     *
     * Runs the agentic loop for the given recruiter goal.
     * If the agent hits a HITL gate, the response will contain a PENDING_APPROVAL
     * message with an Approval ID. Approve it at /api/agent/approvals/{id}/approve
     * then re-submit the same request.
     */
    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ResponseEntity<Map<String, String>> run(@RequestBody Map<String, String> body) {
        String goal    = body.getOrDefault("goal", "").trim();
        String context = body.getOrDefault("context", "");
        if (goal.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "'goal' is required"));
        }
        String result = agentLoopService.run(goal, context);
        return ResponseEntity.ok(Map.of("result", result));
    }

    // ── HITL approval endpoints ───────────────────────────────────────────────

    /** List all pending approvals awaiting recruiter action. */
    @GetMapping("/approvals")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ResponseEntity<List<HumanApprovalGate.PendingApproval>> listPendingApprovals() {
        return ResponseEntity.ok(approvalGate.listPending());
    }

    /** Approve a queued action so the next loop invocation can proceed. */
    @PostMapping("/approvals/{id}/approve")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id) {
        boolean ok = approvalGate.approve(id);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("approvalId", id, "status", "APPROVED"));
    }

    /** Deny a queued action — the tool will not execute on the next loop run. */
    @PostMapping("/approvals/{id}/deny")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ResponseEntity<Map<String, Object>> deny(@PathVariable String id) {
        boolean ok = approvalGate.deny(id);
        if (!ok) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("approvalId", id, "status", "DENIED"));
    }
}
