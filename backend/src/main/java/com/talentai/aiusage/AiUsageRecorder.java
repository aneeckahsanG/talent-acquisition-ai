package com.talentai.aiusage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Saves a usage row on its own thread and transaction, so a slow or failing insert can never delay or
 * break the AI call, and a caller's rolled-back transaction cannot erase the record of money already spent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiUsageRecorder {

    private final AiUsageLogRepository repository;

    /** Prices in US dollars per million tokens. Defaults are Sonnet-class list prices: set your own contract rates. */
    @Value("${app.ai.price-input-per-million-usd:3.0}")
    private double inputPricePerMillion;

    @Value("${app.ai.price-output-per-million-usd:15.0}")
    private double outputPricePerMillion;

    public BigDecimal estimateCost(int inputTokens, int outputTokens) {
        double cost = inputTokens * inputPricePerMillion / 1_000_000.0 + outputTokens * outputPricePerMillion / 1_000_000.0;
        return BigDecimal.valueOf(cost).setScale(6, RoundingMode.HALF_UP);
    }

    @Async
    public void record(String feature, String model, int inputTokens, int outputTokens, boolean success, String error, long durationMs) {
        try {
            repository.save(AiUsageLog.builder()
                    .feature(feature.length() > 120 ? feature.substring(0, 120) : feature)
                    .model(model)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .estimatedCost(estimateCost(inputTokens, outputTokens))
                    .success(success)
                    .error(error != null && error.length() > 255 ? error.substring(0, 255) : error)
                    .durationMs(durationMs)
                    .build());
        } catch (Exception e) {
            log.warn("Could not record AI usage for {}: {}", feature, e.getMessage());
        }
    }
}
