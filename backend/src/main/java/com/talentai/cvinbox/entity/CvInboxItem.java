package com.talentai.cvinbox.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cv_inbox_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CvInboxItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "candidate_id")
    private Long candidateId;

    // Snapshot of the candidate's name/email AT PROCESSING TIME — deliberately not joined
    // live from the Candidate table. This is an audit log of what this specific file was
    // judged as; if a later file happens to share the same email (candidate dedup can merge
    // unrelated people when sample/test data reuses a placeholder email) and overwrites the
    // shared Candidate row's name, older CV Inbox rows must still show what was true when
    // THEY were processed, not whatever the Candidate row currently says.
    @Column(name = "candidate_name")
    private String candidateName;

    @Column(name = "candidate_email")
    private String candidateEmail;

    @Column(nullable = false)
    private String decision; // CONSIDERED, EXCLUDED

    @Column(nullable = false)
    private String status; // PROCESSED, FAILED

    @Column(name = "best_requisition_id")
    private Long bestRequisitionId;

    @Column(name = "best_requisition_title")
    private String bestRequisitionTitle;

    @Column(name = "best_match_score")
    private BigDecimal bestMatchScore;

    @Column(name = "best_rationale", columnDefinition = "TEXT")
    private String bestRationale;

    // JSON array of {requisitionId, requisitionTitle, matchScore, rationale, pipelineStage}
    // for every open role this CV was scored against — not just the best one.
    @Column(name = "match_details_json", columnDefinition = "TEXT")
    private String matchDetailsJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    @Column(nullable = false)
    private Boolean archived = false;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (processedAt == null) processedAt = now;
    }
}
