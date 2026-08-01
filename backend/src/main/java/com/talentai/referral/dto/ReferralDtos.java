package com.talentai.referral.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ReferralDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubmitReferralRequest {
        private String candidateName;
        private String candidateEmail;
        private String resumeText;
        private Long requisitionId;
        private String referrerName;
        private String referrerEmail;
        private String referrerPosition;
        private String relationshipNotes;
    }

    /** Structure Claude returns when matching a referral against open roles. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaudeReferralMatchResponse {
        private Long bestRequisitionId;
        private BigDecimal matchScore;
        private String rationale;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReferralResponse {
        private Long id;
        private Long candidateId;
        private String candidateName;
        private Long requisitionId;
        private String requisitionTitle;
        private Long matchedRequisitionId;
        private String matchedRequisitionTitle;
        private BigDecimal matchScore;
        private String referredByUsername;
        private String referrerName;
        private String referrerEmail;
        private String referrerPosition;
        private String relationshipNotes;
        private String status;
        private LocalDateTime submittedAt;
        private LocalDateTime lastUpdatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateStatusRequest {
        private String status;
    }
}
