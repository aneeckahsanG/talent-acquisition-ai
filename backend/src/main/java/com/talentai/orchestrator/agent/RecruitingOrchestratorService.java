package com.talentai.orchestrator.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentai.common.client.ClaudeApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * The agentic orchestrator: given a goal ("handle this new application"),
 * runs a Claude tool-use loop — Claude decides which tool to call next,
 * Java executes it, and the result is fed back until Claude finishes.
 *
 * Side-effectful tools (send email, reject) pause the run for human approval.
 * Every thought, tool call, and result is persisted as an AgentStep trace.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecruitingOrchestratorService {

    private static final int MAX_STEPS = 20; // hard budget so a run can never loop forever

    private static final String SYSTEM_PROMPT = """
            You are a recruiting orchestrator agent for TalentAcquisition AI.
            Your job: handle a new inbound job application end-to-end using the tools provided.

            Standard playbook (adapt as needed):
            1. Get the job description and the candidate profile to understand the context.
            2. Screen the candidate against the requisition.
            3. Based on the screening recommendation:
               - ADVANCE (score >= 75): move candidate to SHORTLISTED stage, draft an outreach
                 email, then send it (sending requires recruiter approval).
               - REVIEW (borderline): move candidate to SCREENED stage and finish — explain in
                 your final answer that a recruiter should review this candidate manually. Do NOT
                 reject or advance borderline candidates yourself.
               - REJECT (clearly unqualified): call reject_candidate (requires recruiter approval).
            4. Check the pipeline before advancing to mention how crowded the stage already is.

            Rules:
            - Never send an email or reject a candidate without calling the corresponding tool
              (they are gated behind human approval — the recruiter has the final say).
            - If a tool returns an ERROR, adapt: try an alternative or finish with an explanation.
            - Keep your final answer to a short paragraph summarizing what you did and why.
            """;

    private final ClaudeApiClient claudeApiClient;
    private final AgentToolExecutor toolExecutor;
    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final ObjectMapper mapper = new ObjectMapper();

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

        ArrayNode messages = mapper.createArrayNode();
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", goal);
        messages.add(userMsg);

        CompletableFuture.runAsync(() -> runLoop(run.getId(), messages));
        return run;
    }

    /** Recruiter approves the pending gated tool — execute it and resume the loop. */
    public void approve(Long runId) {
        AgentRun run = loadPaused(runId);
        recordStep(runId, "APPROVAL_DECISION", run.getPendingTool(), "Recruiter APPROVED " + run.getPendingTool());

        JsonNode input = readJson(run.getPendingInput());
        String result = toolExecutor.execute(run.getPendingTool(), input.path("input"));
        recordStep(runId, "TOOL_RESULT", run.getPendingTool(), result);

        resumeWithToolResult(run, input.path("toolUseId").asText(), result);
    }

    /** Recruiter rejects the pending gated tool — tell the agent and let it adapt. */
    public void reject(Long runId, String reason) {
        AgentRun run = loadPaused(runId);
        recordStep(runId, "APPROVAL_DECISION", run.getPendingTool(), "Recruiter REJECTED " + run.getPendingTool()
                + (reason == null || reason.isBlank() ? "" : " — " + reason));

        JsonNode input = readJson(run.getPendingInput());
        String result = "DECLINED: the recruiter declined this action."
                + (reason == null || reason.isBlank() ? "" : " Reason: " + reason)
                + " Do not retry it. Adapt and finish.";
        resumeWithToolResult(run, input.path("toolUseId").asText(), result);
    }

    // ---- the loop ----

    private void resumeWithToolResult(AgentRun run, String toolUseId, String result) {
        ArrayNode messages = (ArrayNode) readJson(run.getConversation());
        messages.add(toolResultMessage(toolUseId, result));

        run.setStatus("RUNNING");
        run.setPendingTool(null);
        run.setPendingInput(null);
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);

        CompletableFuture.runAsync(() -> runLoop(run.getId(), messages));
    }

    private void runLoop(Long runId, ArrayNode messages) {
        try {
            for (int i = 0; i < MAX_STEPS; i++) {
                JsonNode response = claudeApiClient.sendWithTools(SYSTEM_PROMPT, messages, toolExecutor.toolDefinitions());
                JsonNode content = response.path("content");
                String stopReason = response.path("stop_reason").asText();

                // Record the agent's reasoning text
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText()) && !block.path("text").asText().isBlank()) {
                        recordStep(runId, "tool_use".equals(stopReason) ? "THOUGHT" : "FINAL",
                                null, block.path("text").asText());
                    }
                }

                // Add assistant turn to conversation
                ObjectNode assistantMsg = mapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                assistantMsg.set("content", content);
                messages.add(assistantMsg);

                if (!"tool_use".equals(stopReason)) {
                    completeRun(runId, extractText(content));
                    return;
                }

                // Handle tool calls (usually one per turn)
                ArrayNode toolResults = mapper.createArrayNode();
                for (JsonNode block : content) {
                    if (!"tool_use".equals(block.path("type").asText())) continue;
                    String toolName = block.path("name").asText();
                    String toolUseId = block.path("id").asText();
                    JsonNode input = block.path("input");

                    recordStep(runId, "TOOL_CALL", toolName, input.toPrettyString());

                    if (toolExecutor.requiresApproval(toolName)) {
                        pauseForApproval(runId, messages, toolName, toolUseId, input);
                        return;
                    }

                    String result = toolExecutor.execute(toolName, input);
                    recordStep(runId, "TOOL_RESULT", toolName, result);
                    toolResults.add(toolResultBlock(toolUseId, result));
                }

                ObjectNode userMsg = mapper.createObjectNode();
                userMsg.put("role", "user");
                userMsg.set("content", toolResults);
                messages.add(userMsg);
            }
            failRun(runId, "Step budget exhausted (" + MAX_STEPS + " turns) — run stopped as a safety measure.");
        } catch (Exception e) {
            log.error("Agent run {} failed: {}", runId, e.getMessage(), e);
            failRun(runId, "Run failed: " + e.getMessage());
        }
    }

    private void pauseForApproval(Long runId, ArrayNode messages, String toolName, String toolUseId, JsonNode input) {
        ObjectNode pending = mapper.createObjectNode();
        pending.put("toolUseId", toolUseId);
        pending.set("input", input);

        AgentRun run = runRepository.findById(runId).orElseThrow();
        run.setStatus("PAUSED_FOR_APPROVAL");
        run.setPendingTool(toolName);
        run.setPendingInput(pending.toString());
        run.setConversation(messages.toString());
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);

        recordStep(runId, "APPROVAL_REQUEST", toolName,
                "Agent wants to call '" + toolName + "' and is waiting for recruiter approval.\nInput: "
                        + input.toPrettyString());
        log.info("Agent run {} paused — awaiting approval for {}", runId, toolName);
    }

    private void completeRun(Long runId, String summary) {
        AgentRun run = runRepository.findById(runId).orElseThrow();
        run.setStatus("COMPLETED");
        run.setResultSummary(summary);
        run.setConversation(null); // no longer needed
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
        log.info("Agent run {} completed", runId);
    }

    private void failRun(Long runId, String message) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus("FAILED");
            run.setResultSummary(message);
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
        });
        recordStep(runId, "ERROR", null, message);
    }

    // ---- helpers ----

    private AgentRun loadPaused(Long runId) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent run not found: " + runId));
        if (!"PAUSED_FOR_APPROVAL".equals(run.getStatus())) {
            throw new IllegalStateException("Agent run " + runId + " is not awaiting approval (status: " + run.getStatus() + ")");
        }
        return run;
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

    private ObjectNode toolResultBlock(String toolUseId, String result) {
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", result);
        return block;
    }

    private ObjectNode toolResultMessage(String toolUseId, String result) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        content.add(toolResultBlock(toolUseId, result));
        return msg;
    }

    private String extractText(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
        }
        return sb.toString();
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt agent state JSON", e);
        }
    }
}
