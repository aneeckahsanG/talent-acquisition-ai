package com.talentai.referral.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "referral")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Referral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "requisition_id")
    private Long requisitionId; // optional target role

    @Column(name = "referred_by")
    private Long referredBy;

    @Column(name = "referrer_name")
    private String referrerName;

    @Column(name = "referrer_email")
    private String referrerEmail;

    @Column(name = "referrer_position")
    private String referrerPosition;

    @Column(name = "relationship_notes", columnDefinition = "TEXT")
    private String relationshipNotes;

    @Column(name = "match_score")
    private BigDecimal matchScore;

    @Column(name = "matched_requisition_id")
    private Long matchedRequisitionId;

    @Builder.Default
    private String status = "SUBMITTED";
    // SUBMITTED, MATCHED, SCREENING, ADVANCED, REJECTED, HIRED

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (submittedAt == null) submittedAt = now;
        lastUpdatedAt = now;
        if (status == null) status = "SUBMITTED";
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdatedAt = LocalDateTime.now();
    }
}
