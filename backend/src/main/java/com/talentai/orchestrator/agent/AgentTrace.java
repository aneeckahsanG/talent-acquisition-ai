package com.talentai.orchestrator.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Records what an agent run actually did, as a call chain rather than a flat list.
 *
 * The orchestrator opens a trace for the thread running the loop; Spring AI then executes tool
 * calls on that same thread, so a tool can attach itself to the run without being handed the run
 * id, and an LLM call made inside a tool attaches to the tool. The result is:
 *
 * <pre>
 *   run
 *    ├─ TOOL_CALL  screenCandidate
 *    │   └─ AI_CALL  ScreeningAgentService.runScreening   (model, tokens, cost)
 *    └─ TOOL_CALL  getPipelineBoard
 * </pre>
 *
 * Tracing must never affect the run: every method here swallows its own failures, and when no
 * trace is open the calls are no-ops.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentTrace {

    private static final ThreadLocal<Ctx> CTX = new ThreadLocal<>();

    private final AgentStepRepository stepRepository;

    private static final class Ctx {
        final Long runId;
        /** Step numbers of calls still executing — the innermost is the parent of anything new. */
        final Deque<Integer> open = new ArrayDeque<>();
        Ctx(Long runId) { this.runId = runId; }
    }

    /** An open call. Closing it stamps the duration and, optionally, what it returned. */
    public interface Span extends AutoCloseable {
        void result(String summary);
        @Override void close();
    }

    private static final Span NO_OP = new Span() {
        public void result(String summary) { }
        public void close() { }
    };

    public void begin(Long runId) { CTX.set(new Ctx(runId)); }

    public void end() { CTX.remove(); }

    public boolean active() { return CTX.get() != null; }

    /** Opens a tool call as a child of whatever is currently executing. */
    public Span tool(String toolName, String arguments) {
        Ctx ctx = CTX.get();
        if (ctx == null) return NO_OP;
        try {
            AgentStep step = write(ctx, "TOOL_CALL", toolName, null, truncate(arguments, 2000), null);
            ctx.open.push(step.getStepNo());
            long startedAt = System.currentTimeMillis();
            return new Span() {
                private boolean closed;
                @Override public void result(String summary) {
                    step.setContent(truncate(summary, 4000));
                }
                @Override public void close() {
                    if (closed) return;
                    closed = true;
                    ctx.open.remove(step.getStepNo());
                    step.setDurationMs(System.currentTimeMillis() - startedAt);
                    save(step);
                }
            };
        } catch (Exception e) {
            log.debug("Could not trace tool {}: {}", toolName, e.getMessage());
            return NO_OP;
        }
    }

    /** Records one finished LLM call under whatever is currently executing. */
    public void aiCall(String feature, String model, int inputTokens, int outputTokens,
                       BigDecimal cost, long durationMs, boolean success, String error) {
        Ctx ctx = CTX.get();
        if (ctx == null) return;
        try {
            // model, tokens and cost are shown from their own columns, so detail stays empty here
            AgentStep step = write(ctx, "AI_CALL", feature, success ? null : error, null, null);
            step.setModel(model);
            step.setInputTokens(inputTokens);
            step.setOutputTokens(outputTokens);
            step.setEstimatedCost(cost);
            step.setDurationMs(durationMs);
            save(step);
        } catch (Exception e) {
            log.debug("Could not trace AI call {}: {}", feature, e.getMessage());
        }
    }

    /** Records a run-level step (START, FINAL, APPROVAL_*, ERROR) at depth 0. */
    public AgentStep runStep(Long runId, String type, String toolName, String content) {
        Ctx ctx = CTX.get();
        Ctx effective = (ctx != null && ctx.runId.equals(runId)) ? ctx : new Ctx(runId);
        AgentStep step = write(effective, type, toolName, content, null, 0);
        return save(step);
    }

    // ── internals ───────────────────────────────────────────────────────────

    private AgentStep write(Ctx ctx, String type, String toolName, String content,
                            String detail, Integer forcedDepth) {
        Integer parent = ctx.open.peek();
        int depth = forcedDepth != null ? forcedDepth : ctx.open.size();
        AgentStep step = AgentStep.builder()
                .runId(ctx.runId)
                .stepNo(nextStepNo(ctx.runId))
                .stepType(type)
                .toolName(toolName)
                .content(truncate(content, 4000))
                .detail(detail)
                .parentStepNo(forcedDepth != null && forcedDepth == 0 ? null : parent)
                .depth(depth)
                .createdAt(LocalDateTime.now())
                .build();
        return save(step);
    }

    private AgentStep save(AgentStep step) {
        try {
            return stepRepository.save(step);
        } catch (Exception e) {
            log.debug("Could not save agent step: {}", e.getMessage());
            return step;
        }
    }

    /** Step numbers are per run and must not collide across the loop's nested calls. */
    private synchronized int nextStepNo(Long runId) {
        return stepRepository.findByRunIdOrderByStepNoAsc(runId).size() + 1;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
