package com.talentai.common.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One stage change of one candidate on one role. Written by {@code PipelineStageHistoryListener}, never by hand. */
@Entity
@Table(name = "pipeline_stage_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PipelineStageHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "requisition_id", nullable = false)
    private Long requisitionId;

    /** Null for the first stage a candidate had on the role. */
    @Column(name = "from_stage")
    private String fromStage;

    @Column(name = "to_stage", nullable = false)
    private String toStage;

    @Column(name = "changed_by")
    private String changedBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
}
