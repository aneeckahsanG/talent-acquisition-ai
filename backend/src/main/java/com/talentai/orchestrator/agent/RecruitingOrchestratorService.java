package com.talentai.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.agent.service.HumanApprovalGate;
import com.talentai.agent.service.HumanApprovalGate.PendingApproval;
import com.talentai.agent.tool.RecruitingTools;
import com.talentai.common.service.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Async agentic orchestrator — starts a run for an inbound application,
 * drives the Spring AI tool-calling loop, and persists every run + its
 * final summary to the database.
 *
 * HITL: high-risk tools in RecruitingTools call HumanApprovalGate, tagged with the run that
 * is calling them. When approval is pending the tool returns a PENDING_APPROVAL message and
 * the run is saved as PAUSED_FOR_APPROVAL, with the pending actions (tool, description and the
 * exact email text) kept on the run so the recruiter can see what they are approving even after
 * a restart. Approving the run (AgentRunController) grants those actions and re-runs the loop,
 * which then carries them out.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecruitingOrchestratorService {

    private static final int MAX_STEPS = 20;

    private final ChatClient chatClient;
    private final RecruitingTools recruitingTools;
    private final HumanApprovalGate approvalGate;
    private final PromptLoader promptLoader;
    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final ObjectMapper objectMapper;

    /** One action a paused run is waiting on, as saved on the run. */
    private record SavedApproval(String actionKey, String tool, String description, String payload) {}

    /** Start a new agent run asynchronously for an inbound application. */
    public AgentRun startRunAsync(Long matchId, Long candidateId, Long requisitionId, String candidateName) {
        String goal = "A new application from '%s' (candidateId=%d) arrived for requisition %d (matchId=%d). Handle it."
                .formatted(candidateName, candidateId, requisitionId, matchId);
        return startRun(matchId, candidateId, requisitionId, goal);
    }

    /** Start a run for a candidate a recruiter has just shortlisted; the agent decides what happens next. */
    public AgentRun startShortlistRun(Long candidateId, Long requisitionId, String candidateName) {
        String goal = "A recruiter shortlisted '%s' (candidateId=%d) for requisition %d. Decide the next step."
                .formatted(candidateName, candidateId, requisitionId);
        return startRun(null, candidateId, requisitionId, goal);
    }

    private AgentRun startRun(Long matchId, Long candidateId, Long requisitionId, String goal) {
        AgentRun run = runRepository.save(AgentRun.builder()
                .matchId(matchId)
                .candidateId(candidateId)
                .requisitionId(requisitionId)
                .goal(goal)
                .status("RUNNING")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        CompletableFuture.runAsync(() -> runLoop(run.getId(), goal));
        return run;
    }

    /**
     * Called by AgentRunController — approve what the run is waiting on and resume it.
     * Throws if nothing is waiting, so the recruiter is told rather than the run pausing again.
     */
    public void approve(Long runId) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent run not found: " + runId));
        if (!"PAUSED_FOR_APPROVAL".equals(run.getStatus())) {
            throw new IllegalStateException("Agent run " + runId + " is not awaiting approval");
        }

        int approvedLive = approvalGate.approveRun(runId);
        List<SavedApproval> saved = savedApprovals(run);
        // The gate is in memory: after a restart it has forgotten what the run saved. Grant those again.
        for (SavedApproval s : saved) {
            if (!approvalGate.isApproved(s.actionKey())) {
                approvalGate.grant(s.actionKey(), s.description(), s.tool(), s.payload(), runId);
            }
        }
        if (approvedLive == 0 && saved.isEmpty()) {
            throw new IllegalStateException("Nothing on this run is waiting for approval any more. "
                    + "Decline it and run the application again.");
        }

        String approvedText = saved.stream().map(SavedApproval::description).collect(Collectors.joining("; "));
        run.setStatus("RUNNING");
        run.setPendingTool(null);
        run.setPendingInput(null);
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);

        recordStep(runId, "APPROVAL_DECISION", null, "Recruiter approved" + (approvedText.isBlank() ? "" : ": " + approvedText));
        String goal = run.getGoal() + (approvedText.isBlank() ? "" :
                "\n\nThe recruiter has reviewed and approved: " + approvedText + ". Call the tool for each approved "
                        + "action again now, with the same arguments, then finish with a short summary.");
        CompletableFuture.runAsync(() -> runLoop(runId, goal));
    }

    /** Called by AgentRunController — reject the paused action and record the reason. */
    public void reject(Long runId, String reason) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent run not found: " + runId));
        approvalGate.denyRun(runId);
        run.setPendingTool(null);
        run.setPendingInput(null);
        runRepository.save(run);
        recordStep(runId, "APPROVAL_DECISION", null, "Recruiter rejected action. Reason: " + reason);
        failRun(runId, "Recruiter rejected action: " + reason);
    }

    private void runLoop(Long runId, String goal) {
        try {
            recordStep(runId, "START", null, "Goal: " + goal);

            String result = chatClient.prompt()
                    .system(promptLoader.load("orchestrator-agent"))
                    .user(goal)
                    .tools(recruitingTools)
                    .toolContext(Map.of(RecruitingTools.RUN_ID_KEY, runId))
                    .call()
                    .content();

            // A gated tool asked the gate for approval during this run, so it is waiting on a recruiter
            // whatever the model chose to say in its final answer. The text check covers a tool that
            // reported a pending approval without the gate knowing which run it was.
            List<PendingApproval> waiting = approvalGate.pendingForRun(runId);
            if (!waiting.isEmpty() || (result != null && result.contains("PENDING_APPROVAL"))) {
                pauseForApproval(runId, result, waiting);
            } else {
                completeRun(runId, result);
            }
        } catch (Exception e) {
            log.error("Agent run {} failed: {}", runId, e.getMessage(), e);
            failRun(runId, "Run failed: " + e.getMessage());
        }
    }

    private void pauseForApproval(Long runId, String agentText, List<PendingApproval> waiting) {
        if (waiting.isEmpty()) {
            log.warn("Agent run {} reported a pending approval but the gate holds none for it", runId);
        }
        recordStep(runId, "APPROVAL_REQUEST", null, agentText);
        AgentRun run = runRepository.findById(runId).orElseThrow();
        run.setStatus("PAUSED_FOR_APPROVAL");
        run.setPendingTool(waiting.stream().map(PendingApproval::toolName).filter(t -> t != null)
                .distinct().collect(Collectors.joining(",")));
        run.setPendingInput(waiting.isEmpty() ? null : approvalsJson(waiting));
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
        log.info("Agent run {} paused — awaiting recruiter approval", runId);
    }

    private String approvalsJson(List<PendingApproval> waiting) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (PendingApproval a : waiting) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("actionKey", a.actionKey());
            item.put("tool", a.toolName());
            item.put("description", a.description());
            item.put("payload", a.payload());
            items.add(item);
        }
        try {
            return objectMapper.writeValueAsString(Map.of("approvals", items));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not save the pending approvals", e);
        }
    }

    private List<SavedApproval> savedApprovals(AgentRun run) {
        if (run.getPendingInput() == null || run.getPendingInput().isBlank()) return List.of();
        try {
            List<SavedApproval> out = new ArrayList<>();
            for (JsonNode n : objectMapper.readTree(run.getPendingInput()).path("approvals")) {
                out.add(new SavedApproval(n.path("actionKey").asText(null), n.path("tool").asText(null),
                        n.path("description").asText(""), n.path("payload").isNull() ? null : n.path("payload").asText(null)));
            }
            return out.stream().filter(s -> s.actionKey() != null).toList();
        } catch (JsonProcessingException e) {
            log.warn("Could not read the saved approvals of run {}: {}", run.getId(), e.getMessage());
            return List.of();
        }
    }

    private void completeRun(Long runId, String summary) {
        recordStep(runId, "FINAL", null, summary);
        AgentRun run = runRepository.findById(runId).orElseThrow();
        run.setStatus("COMPLETED");
        run.setResultSummary(summary);
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
        log.info("Agent run {} completed", runId);
    }

    private void failRun(Long runId, String message) {
        recordStep(runId, "ERROR", null, message);
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus("FAILED");
            run.setResultSummary(message);
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
        });
    }

    private synchronized void recordStep(Long runId, String type, String toolName, String content) {
        int next = stepRepository.findByRunIdOrderByStepNoAsc(runId).size() + 1;
        stepRepository.save(AgentStep.builder()
                .runId(runId)
                .stepNo(next)
                .stepType(type)
                .toolName(toolName)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
