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

    /** Structure Claude returns when choosing which open role a referral should go to. No score: Screening scores. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaudeReferralRoleResponse {
        private Long bestRequisitionId;
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
        /** The Screening Agent's score for the matched role, once the referral has been screened. */
        private BigDecimal screeningScore;
        private String screeningRecommendation;
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
