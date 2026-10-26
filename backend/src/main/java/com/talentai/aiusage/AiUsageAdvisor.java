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
            return response;
        } catch (RuntimeException e) {
            recorder.record(feature, null, 0, 0, false, e.getClass().getSimpleName() + ": " + e.getMessage(), System.currentTimeMillis() - started);
            throw e;
        }
    }

    /** First application frame on the stack that is not this package: the code that asked for the AI call. */
    private static String callerOf() {
        Optional<String> caller = StackWalker.getInstance().walk(frames -> frames
                .filter(f -> f.getClassName().startsWith("com.talentai") && !f.getClassName().startsWith("com.talentai.aiusage")
                        && !f.getClassName().startsWith("com.talentai.common.config"))
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1).replaceAll("\\$.*", "") + "." + f.getMethodName())
                .findFirst());
        return caller.orElse("Unknown");
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
