package com.talentai.admin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A reminder or chaser already sent for an interview; the unique key stops it going out twice. */
@Entity
@Table(name = "interview_reminder")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_id", nullable = false)
    private Long interviewId;

    /** BOOKING_CHASE_1, BOOKING_CHASE_2, BOOKING_STALLED, NO_SLOTS_ALERT, REMINDER_CANDIDATE, REMINDER_INTERVIEWER, SCORECARD_NUDGE, SCORECARD_ESCALATION */
    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    @Builder.Default
    private String ref = "";

    @Column(name = "recipient_email")
    private String recipientEmail;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
}
