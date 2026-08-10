package com.talentai.orchestrator.agent;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One end-to-end run of the recruiting orchestrator agent for a single application. */
@Entity
@Table(name = "agent_run")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requisition_id")
    private Long requisitionId;

    @Column(name = "candidate_id")
    private Long candidateId;

    @Column(name = "match_id")
    private Long matchId;

    private String goal;

    /** RUNNING, PAUSED_FOR_APPROVAL, COMPLETED, FAILED, CANCELLED */
    @Column(nullable = false)
    private String status;

    @Column(name = "pending_tool")
    private String pendingTool;

    @Column(name = "pending_input", columnDefinition = "TEXT")
    private String pendingInput;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    /** Serialized Claude messages array — lets a paused run resume after approval. */
    @Column(columnDefinition = "TEXT")
    private String conversation;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
