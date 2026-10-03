package com.talentai.orchestrator.agent;

import com.talentai.agent.service.HumanApprovalGate;
import com.talentai.agent.tool.RecruitingTools;
import com.talentai.common.service.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * Async agentic orchestrator — starts a run for an inbound application,
 * drives the Spring AI tool-calling loop, and persists every run + its
 * final summary to the database.
 *
 * HITL: high-risk tools in RecruitingTools call HumanApprovalGate.
 * When approval is pending the tool returns a PENDING_APPROVAL message;
 * the recruiter approves via /api/agent/approvals/{id}/approve and
 * re-submits the run through AgentRunController.
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

    /** Start a new agent run asynchronously for an inbound application. */
    public AgentRun startRunAsync(Long matchId, Long candidateId, Long requisitionId, String candidateName) {
        String goal = "A new application from '%s' (candidateId=%d) arrived for requisition %d (matchId=%d). Handle it."
                .formatted(candidateName, candidateId, requisitionId, matchId);

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

    /** Called by AgentRunController — approve the paused action and resume the run. */
    public void approve(Long runId) {
        resume(runId);
    }

    /** Called by AgentRunController — reject the paused action and record the reason. */
    public void reject(Long runId, String reason) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent run not found: " + runId));
        recordStep(runId, "APPROVAL_DECISION", null, "Recruiter rejected action. Reason: " + reason);
        failRun(runId, "Recruiter rejected action: " + reason);
    }

    /** Re-submit a run after the recruiter approves a pending HITL gate. */
    public void resume(Long runId) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent run not found: " + runId));
        if (!"PAUSED_FOR_APPROVAL".equals(run.getStatus())) {
            throw new IllegalStateException("Agent run " + runId + " is not awaiting approval");
        }
        run.setStatus("RUNNING");
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);

        recordStep(runId, "APPROVAL_DECISION", null, "Recruiter approved — resuming run");
        CompletableFuture.runAsync(() -> runLoop(run.getId(), run.getGoal()));
    }

    private void runLoop(Long runId, String goal) {
        try {
            recordStep(runId, "START", null, "Goal: " + goal);

            String result = chatClient.prompt()
                    .system(promptLoader.load("orchestrator-agent"))
                    .user(goal)
                    .tools(recruitingTools)
                    .call()
                    .content();

            // Check if a HITL gate fired inside a tool — tools return PENDING_APPROVAL in their text
            if (result != null && result.contains("PENDING_APPROVAL")) {
                recordStep(runId, "APPROVAL_REQUEST", null, result);
                AgentRun run = runRepository.findById(runId).orElseThrow();
                run.setStatus("PAUSED_FOR_APPROVAL");
                run.setUpdatedAt(LocalDateTime.now());
                runRepository.save(run);
                log.info("Agent run {} paused — awaiting recruiter approval", runId);
            } else {
                completeRun(runId, result);
            }
        } catch (Exception e) {
            log.error("Agent run {} failed: {}", runId, e.getMessage(), e);
            failRun(runId, "Run failed: " + e.getMessage());
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
