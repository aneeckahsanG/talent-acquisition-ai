package com.talentai.sourcing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class SourcingDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddTalentPoolCandidateRequest {
        private String fullName;
        private String email;
        private String headline;
        private String resumeText; // profile summary / resume content
        private String skills;     // comma-separated
        private BigDecimal yearsExperience;
        private String profileUrl;
        private String sourceChannel; // LINKEDIN, JOBSTREET, MANUAL
        private Boolean hasResume;    // false = LinkedIn profile only, skip auto-screening
    }

    /** Structure Claude returns for a single candidate match. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaudeMatchResponse {
        private BigDecimal matchScore;
        private String rationale;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutreachDraftResponse {
        private Long matchId;
        private String candidateName;
        private String candidateEmail;
        private String requisitionTitle;
        private String draft;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResumeParseResponse {
        private String name;
        private String email;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CsvImportResponse {
        private int totalRows;
        private int successCount;
        private int failureCount;
        private List<String> errors;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedInParseRequest {
        private String url;       // optional — try to fetch
        private String pasteText; // optional — user-pasted profile text
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkedInParseResponse {
        private String fullName;
        private String email;
        private String headline;
        private String location;
        private String skills;
        private String resumeText;
        private boolean fetchedFromUrl; // true if we got data from URL, false if fallback needed
        private String message;         // e.g. "LinkedIn blocked — please paste profile text"
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DirectApplyRequest {
        private String fullName;
        private String email;
        private String headline;
        private String skills;
        private java.math.BigDecimal yearsExperience;
        private String resumeText;
        private String coverLetter;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DirectApplyResponse {
        private String message;
        private Long matchId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PublicJobResponse {
        private Long id;
        private String title;
        private String department;
        private String location;
        private String description;
        private String requiredSkills;
        private String experienceLevel;
        private java.time.LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResumeSourceResponse {
        private Long id;
        private String name;
        private String url;
        private String sourceType;
        private Boolean active;
        private LocalDateTime lastSyncAt;
        private Integer lastSyncCount;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateResumeSourceRequest {
        private String name;
        private String url;
        private String sourceType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourcingMatchResponse {
        private Long id;
        private Long candidateId;
        private String candidateName;
        private String candidateHeadline;
        private String sourceChannel;
        private Long requisitionId;
        private String requisitionTitle;
        private BigDecimal matchScore;
        private String matchRationale;
        private Boolean isProactive;
        private String status;
        private LocalDateTime createdAt;
        // Screening result if the candidate has been internally screened
        private Long screeningResultId;
        private String screeningRecommendation; // ADVANCE, REJECT, REVIEW, or null
        private BigDecimal screeningScore;
        private String pipelineStage;
    }
}
