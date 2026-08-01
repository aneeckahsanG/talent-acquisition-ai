package com.talentai.screening.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ScreeningDtos {

    /**
     * Structure that Claude is prompted to return as JSON for screening evaluation.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaudeScreeningResponse {
        private BigDecimal overallScore;
        private BigDecimal skillsScore;
        private BigDecimal experienceScore;
        private BigDecimal cultureFitScore;
        private String strengths;
        private String gaps;
        private String rationale;
        private String recommendation; // ADVANCE, REJECT, REVIEW
        private Boolean isEdgeCase;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScreeningResultResponse {
        private Long id;
        private Long candidateId;
        private String candidateName;
        private Long requisitionId;
        private String requisitionTitle;
        private BigDecimal overallScore;
        private BigDecimal skillsScore;
        private BigDecimal experienceScore;
        private BigDecimal cultureFitScore;
        private String strengths;
        private String gaps;
        private String rationale;
        private String recommendation;
        private Boolean isEdgeCase;
        private String reviewerDecision;
        private String reviewerNotes;
        private String pipelineStage;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReviewDecisionRequest {
        private String decision; // ADVANCE, REJECT
        private String notes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScreenByTextRequest {
        private String candidateName;
        private String candidateEmail;
        private String resumeText;
        private Long requisitionId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParsedResumeResponse {
        private String name;
        private String email;
        private String resumeText;
    }
}
