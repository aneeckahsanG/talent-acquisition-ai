package com.talentai.screening.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "screening_result")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScreeningResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "requisition_id", nullable = false)
    private Long requisitionId;

    @Column(name = "overall_score", nullable = false)
    private BigDecimal overallScore;

    @Column(name = "skills_score")
    private BigDecimal skillsScore;

    @Column(name = "experience_score")
    private BigDecimal experienceScore;

    @Column(name = "culture_fit_score")
    private BigDecimal cultureFitScore;

    @Column(columnDefinition = "TEXT")
    private String strengths;

    @Column(columnDefinition = "TEXT")
    private String gaps;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(nullable = false)
    private String recommendation; // ADVANCE, REJECT, REVIEW

    @Column(name = "is_edge_case", nullable = false)
    @Builder.Default
    private Boolean isEdgeCase = false;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewer_decision")
    private String reviewerDecision; // ADVANCE, REJECT

    @Column(name = "reviewer_notes", columnDefinition = "TEXT")
    private String reviewerNotes;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (isEdgeCase == null) {
            isEdgeCase = false;
        }
    }
}
