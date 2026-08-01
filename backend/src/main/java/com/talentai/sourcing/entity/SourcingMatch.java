package com.talentai.sourcing.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "sourcing_match")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourcingMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "requisition_id", nullable = false)
    private Long requisitionId;

    @Column(name = "match_score", nullable = false)
    private BigDecimal matchScore;

    @Column(name = "match_rationale", columnDefinition = "TEXT")
    private String matchRationale;

    @Column(name = "is_proactive", nullable = false)
    @Builder.Default
    private Boolean isProactive = true;

    @Builder.Default
    private String status = "NEW"; // NEW, REVIEWED, DISMISSED, ADVANCED

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) status = "NEW";
        if (isProactive == null) isProactive = true;
    }
}
