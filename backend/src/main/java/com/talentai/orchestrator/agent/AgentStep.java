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

    /** THOUGHT, TOOL_CALL, TOOL_RESULT, APPROVAL_REQUEST, APPROVAL_DECISION, FINAL, ERROR */
    @Column(name = "step_type", nullable = false)
    private String stepType;

    @Column(name = "tool_name")
    private String toolName;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
