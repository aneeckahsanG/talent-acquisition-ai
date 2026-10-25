package com.talentai.admin.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured feedback an interviewer records when an interview round is completed. */
@Entity
@Table(name = "interview_scorecard")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewScorecard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_id", nullable = false, unique = true)
    private Long interviewId;

    /** 1-5 rating per criterion; which criteria apply depends on the interview type. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "interview_scorecard_rating", joinColumns = @JoinColumn(name = "scorecard_id"))
    @MapKeyColumn(name = "criterion")
    @Column(name = "rating")
    @Builder.Default
    private Map<String, Integer> ratings = new LinkedHashMap<>();

    @Column(nullable = false)
    private String verdict; // STRONG_YES, YES, NO, STRONG_NO

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "notice_period")
    private String noticePeriod;

    @Column(name = "expected_salary")
    private String expectedSalary;

    @Column(name = "submitted_by")
    private Long submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    public double averageRating() {
        if (ratings == null || ratings.isEmpty()) return 0;
        double avg = ratings.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        return Math.round(avg * 10) / 10.0;
    }

    @PrePersist
    protected void onCreate() {
        if (submittedAt == null) submittedAt = LocalDateTime.now();
    }
}
