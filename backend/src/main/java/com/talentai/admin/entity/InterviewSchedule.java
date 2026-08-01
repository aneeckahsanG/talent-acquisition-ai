package com.talentai.admin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_schedule")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id", nullable = false)
    private Long candidateId;

    @Column(name = "requisition_id", nullable = false)
    private Long requisitionId;

    @Column(name = "interview_type", nullable = false)
    @Builder.Default
    private String interviewType = "SCREENING"; // SCREENING, TECHNICAL, FINAL

    @Column(name = "proposed_slots", columnDefinition = "TEXT")
    private String proposedSlots; // JSON array of ISO datetime strings

    @Column(name = "confirmed_slot")
    private LocalDateTime confirmedSlot;

    @Builder.Default
    private String status = "PROPOSED"; // PROPOSED, CONFIRMED, COMPLETED, CANCELLED

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "invitation_email", columnDefinition = "TEXT")
    private String invitationEmail;

    @Column(name = "candidate_reply", columnDefinition = "TEXT")
    private String candidateReply;

    @Column(name = "candidate_replied_at")
    private LocalDateTime candidateRepliedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) status = "PROPOSED";
        if (interviewType == null) interviewType = "SCREENING";
    }
}
