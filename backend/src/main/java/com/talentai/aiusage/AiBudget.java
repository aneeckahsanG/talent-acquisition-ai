package com.talentai.aiusage;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ai_budget")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiBudget {

    @Id
    private Long id;

    @Column(name = "daily_limit_usd", precision = 12, scale = 4)
    private BigDecimal dailyLimitUsd;

    @Column(name = "monthly_limit_usd", precision = 12, scale = 4)
    private BigDecimal monthlyLimitUsd;

    @Column(name = "warn_percent", nullable = false)
    private int warnPercent;

    @Column(name = "hard_stop", nullable = false)
    private boolean hardStop;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
