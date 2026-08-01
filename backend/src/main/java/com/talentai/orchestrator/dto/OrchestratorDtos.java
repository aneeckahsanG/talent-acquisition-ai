package com.talentai.orchestrator.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class OrchestratorDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineCandidateResponse {
        private Long candidateId;
        private String candidateName;
        private String candidateEmail;
        private String sourceChannel;
        private Long requisitionId;
        private String requisitionTitle;
        private String stage;
        private String updatedByAgent;
        private String notes;
        private LocalDateTime enteredAt;

        // Supplementary scores, populated where available
        private BigDecimal screeningScore;
        private String screeningRecommendation;
        private BigDecimal sourcingMatchScore;

        // Interview rounds (populated when candidate has been in INTERVIEW stage)
        private List<InterviewRoundResponse> interviewRounds;

        // Offer details (populated when stage = OFFER or HIRED)
        private OfferResponse offer;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineBoardResponse {
        private Long requisitionId;
        private String requisitionTitle;
        // stage name -> list of candidates in that stage
        private Map<String, List<PipelineCandidateResponse>> stages;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActivityLogEntryResponse {
        private Long id;
        private String agentName;
        private Long candidateId;
        private String candidateName;
        private Long requisitionId;
        private String requisitionTitle;
        private String action;
        private String details;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterviewRoundResponse {
        private Long id;
        private String interviewType; // PHONE_SCREEN, TECHNICAL, FINAL
        private LocalDateTime confirmedSlot;
        private String status; // PROPOSED, CONFIRMED, COMPLETED, CANCELLED
        private String notes;
        private int roundNumber;
        private String invitationEmail;
        private String candidateReply;
        private LocalDateTime candidateRepliedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NegotiationHistoryEntry {
        private int roundNumber;
        private BigDecimal salaryAmount;
        private String currency;
        private LocalDate startDate;
        private String notes;
        private String changedBy;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateOfferTermsRequest {
        private BigDecimal salaryAmount;
        private String currency;
        private String startDate;
        private String notes;
        private String changedBy; // RECRUITER or CANDIDATE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OfferLetterResponse {
        private String candidateName;
        private String candidateEmail;
        private String letterText;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateOfferRequest {
        private BigDecimal salaryAmount;
        private String currency;       // MYR, USD, SGD …
        private String startDate;      // ISO date yyyy-MM-dd
        private String expiryDate;
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OfferResponse {
        private Long id;
        private BigDecimal salaryAmount;
        private String currency;
        private LocalDate startDate;
        private LocalDate expiryDate;
        private String status;
        private String notes;
        private List<NegotiationHistoryEntry> history;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleInterviewRequest {
        private String interviewType; // SCREENING, TECHNICAL, FINAL
        private String confirmedSlot; // ISO-8601 datetime string
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterviewScheduledResponse {
        private Long interviewId;
        private String candidateName;
        private String candidateEmail;
        private String requisitionTitle;
        private String interviewType;
        private LocalDateTime confirmedSlot;
        private String status;
        private String pipelineStage;
        private String invitationEmail;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardSummaryResponse {
        private long openRequisitions;
        private long totalCandidates;
        private long candidatesScreenedToday;
        private long edgeCasesAwaitingReview;
        private long referralsInProgress;
        private long interviewsScheduled;
        private List<ActivityLogEntryResponse> recentActivity;
    }
}
