package com.talentai.orchestrator.agent;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A single trace entry in an agent run: a thought, tool call, tool result, or decision. */
@Entity
@Table(name = "agent_step")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "step_no", nullable = false)
    private Integer stepNo;

    /** START, TOOL_CALL, AI_CALL, APPROVAL_REQUEST, APPROVAL_DECISION, FINAL, ERROR */
    @Column(name = "step_type", nullable = false)
    private String stepType;

    @Column(name = "tool_name")
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** The step that triggered this one — null for a step owned directly by the run. */
    @Column(name = "parent_step_no")
    private Integer parentStepNo;

    /** 0 for a run-level step, 1 for a tool call, 2 for an LLM call made inside a tool. */
    @Column(name = "depth", nullable = false)
    @Builder.Default
    private Integer depth = 0;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** Tool arguments, or a short summary of what the call returned. */
    @Column(name = "detail", length = 2000)
    private String detail;

    // ── set on AI_CALL steps only ────────────────────────────────────────────
    @Column(name = "model", length = 80)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "estimated_cost", precision = 12, scale = 6)
    private java.math.BigDecimal estimatedCost;
}
