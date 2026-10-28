package com.talentai.common.util;

import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * Retries a Claude chat-client call that failed to parse — most commonly because the model
 * returned an empty body (Jackson's "No content to map due to end-of-input") under load, which
 * both the Sourcing and Screening agents observed intermittently after switching to a cheaper,
 * higher-throughput model.
 */
public final class AiCallRetry {

    private static final int MAX_ATTEMPTS = 2;

    private AiCallRetry() {
    }

    public static <T> T withRetry(Supplier<T> call, Logger log, String agentName, String context) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                last = e;
                log.warn("{} call failed for {} (attempt {}/{}): {}",
                        agentName, context, attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        throw last;
    }
}
