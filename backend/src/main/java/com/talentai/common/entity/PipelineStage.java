package com.talentai.common.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import com.talentai.common.service.PipelineStageHistoryListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "pipeline_stage")
@EntityListeners(PipelineStageHistoryListener.class)
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

    /** The stage as last loaded or saved, so the history listener can tell a stage move from a note edit. Not stored. */
    @Transient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private String loadedStage;

    /** The note as last loaded or saved: a note left over from an earlier step is not the note of a later one. Not stored. */
    @Transient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private String loadedNotes;

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
