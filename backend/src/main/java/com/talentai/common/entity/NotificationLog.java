package com.talentai.common.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Records the outcome of a candidate-facing notification email. A failed
 * send here does NOT roll back whatever pipeline/status change triggered
 * it — that change already happened — this row is what lets a recruiter
 * notice the candidate was never actually told, and resend it.
 */
@Entity
@Table(name = "notification_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id")
    private Long candidateId;

    @Column(name = "requisition_id")
    private Long requisitionId;

    @Column(name = "notification_type", nullable = false)
    private String notificationType; // REJECTION, etc.

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private String status; // SENT, FAILED

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
