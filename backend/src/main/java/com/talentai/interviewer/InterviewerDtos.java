package com.talentai.interviewer;

import com.talentai.orchestrator.dto.OrchestratorDtos.ScorecardSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What an interviewer is allowed to see about an interview assigned to them. Deliberately leaves out the
 * candidate's email, phone, screening score and other interviewers' feedback, so the interview is judged
 * on what the candidate says and shows, not on what others have already concluded.
 */
public class InterviewerDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterviewSummary {
        private Long id;
        private String interviewType;
        private String status;
        private LocalDateTime confirmedSlot;
        private int roundNumber;
        private String candidateName;
        private String roleTitle;
        private boolean scorecardSubmitted;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Criterion {
        private String key;
        private String label;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterviewDetail {
        private Long id;
        private String interviewType;
        private String status;
        private LocalDateTime confirmedSlot;
        private int roundNumber;

        private String candidateName;
        private String candidateHeadline;
        private String candidateSkills;
        private BigDecimal candidateYearsExperience;
        private String resumeText;

        private String roleTitle;
        private String department;
        private String location;
        private String experienceLevel;
        private String requiredSkills;
        private String roleDescription;

        /** What this interview type is scored on, in display order. */
        private List<Criterion> criteria;
        /** The caller's own scorecard, once submitted. */
        private ScorecardSummary submittedScorecard;
    }
}
