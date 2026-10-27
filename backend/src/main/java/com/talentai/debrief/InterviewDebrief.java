package com.talentai.debrief;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** The latest AI debrief of a candidate's interview scorecards for one role. */
@Entity
@Table(name = "interview_debrief")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewDebrief {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "requisition_id", nullable = false)
    private Long requisitionId;

    /** Hash of the scorecards this was based on; a different hash now means the debrief is out of date. */
    @Column(nullable = false)
    private String fingerprint;

    /** ADVANCE_TO_OFFER, ADD_ROUND, HOLD_FOR_DISCUSSION, DO_NOT_PROCEED */
    @Column(nullable = false)
    private String recommendation;

    /** HIGH, MEDIUM, LOW */
    @Column(nullable = false)
    private String confidence;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    /** JSON object with the agreements, disagreements and concerns lists. */
    @Column(name = "details_json", nullable = false, columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "suggested_next_step", columnDefinition = "TEXT")
    private String suggestedNextStep;

    /** Set when a rule changed the AI's recommendation; explains why. */
    @Column(name = "guardrail_note", columnDefinition = "TEXT")
    private String guardrailNote;

    @Column(name = "generated_by")
    private String generatedBy;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;
}
