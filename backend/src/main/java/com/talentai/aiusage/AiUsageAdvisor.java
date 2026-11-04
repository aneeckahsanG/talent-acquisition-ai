package com.talentai.aiusage;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * Wraps every call made through the shared ChatClient: refuses it when the AI budget is used up,
 * and otherwise records its token use and estimated cost. The feature is the application class and
 * method that made the call, so no call site needs changing.
 */
@RequiredArgsConstructor
public class AiUsageAdvisor implements CallAdvisor {

    private final AiUsageRecorder recorder;
    private final AiBudgetGuard budgetGuard;
    /** Attaches this call to the agent run that triggered it, when one is in progress. */
    private final com.talentai.orchestrator.agent.AgentTrace agentTrace;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String feature = callerOf();
        long started = System.currentTimeMillis();
        try {
            budgetGuard.checkAllowed();
            ChatClientResponse response = chain.nextCall(request);
            long took = System.currentTimeMillis() - started;
            int in = 0, out = 0;
            String model = null;
            try {
                var resp = response.chatResponse();
                if (resp != null && resp.getMetadata() != null) {
                    model = resp.getMetadata().getModel();
                    Usage usage = resp.getMetadata().getUsage();
                    if (usage != null) {
                        in = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
                        out = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
                    }
                }
            } catch (Exception ignored) {
                // usage is best-effort: never let bookkeeping break the call
            }
            try {
                budgetGuard.addSpend(recorder.estimateCost(in, out).doubleValue());
            } catch (Exception ignored) {
                // the running total is best-effort too
            }
            recorder.record(feature, model, in, out, true, null, took);
            agentTrace.aiCall(feature, model, in, out, recorder.estimateCost(in, out), took, true, null);
            return response;
        } catch (RuntimeException e) {
            long took = System.currentTimeMillis() - started;
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            recorder.record(feature, null, 0, 0, false, message, took);
            agentTrace.aiCall(feature, null, 0, 0, java.math.BigDecimal.ZERO, took, false, message);
            throw e;
        }
    }

    /** First application frame on the stack that is not this package: the code that asked for the AI call. */
    private static String callerOf() {
        Optional<String> caller = StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().startsWith("com.talentai") && !f.getClassName().startsWith("com.talentai.aiusage")
                        && !f.getClassName().startsWith("com.talentai.common.config")
                        && !f.getClassName().startsWith("com.talentai.common.util"))
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1).replaceAll("\\$.*", "")
                        + "." + unwrapLambda(f.getMethodName()))
                .findFirst());
        return caller.orElse("Unknown");
    }

    /**
     * javac compiles a lambda body into a synthetic {@code lambda$enclosingMethod$N} method, and N is a
     * counter over the whole class — so it shifts whenever any lambda is added or reordered. Reporting it
     * raw would split one feature across several rows and mislabel older ones, so the real method name is
     * recovered here.
     */
    private static String unwrapLambda(String methodName) {
        return methodName.replaceAll("^lambda\\$(.+)\\$\\d+$", "$1");
    }

    @Override
    public String getName() {
        return "AiUsageAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
