package com.talentai.aiusage;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One AI call: who asked for it (the code that called it), the model, tokens used and the estimated cost. */
@Entity
@Table(name = "ai_usage_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The class and method that made the call, e.g. ScreeningAgentService.runScreening. */
    @Column(nullable = false, length = 120)
    private String feature;

    private String model;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "estimated_cost", nullable = false, precision = 12, scale = 6)
    private BigDecimal estimatedCost;

    @Column(nullable = false)
    private boolean success;

    private String error;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
