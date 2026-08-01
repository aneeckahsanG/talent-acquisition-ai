package com.talentai.common.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_stage")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PipelineStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "requisition_id", nullable = false)
    private Long requisitionId;

    @Builder.Default
    private String stage = "SOURCED";
    // SOURCED, SCREENED, SHORTLISTED, REFERRAL_MATCHED, INTERVIEW_SCHEDULED, OFFER, HIRED, REJECTED

    @Column(name = "entered_at")
    private LocalDateTime enteredAt;

    @Column(name = "updated_by_agent")
    private String updatedByAgent; // SOURCING, SCREENING, REFERRAL, ADMIN, ORCHESTRATOR, HUMAN

    @Column(columnDefinition = "TEXT")
    private String notes;

    @PrePersist
    protected void onCreate() {
        if (enteredAt == null) {
            enteredAt = LocalDateTime.now();
        }
        if (stage == null) {
            stage = "SOURCED";
        }
    }
}
