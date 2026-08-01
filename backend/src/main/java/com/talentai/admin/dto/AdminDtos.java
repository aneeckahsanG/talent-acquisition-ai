package com.talentai.admin.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

public class AdminDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProposeInterviewRequest {
        private Long candidateId;
        private Long requisitionId;
        private String interviewType; // SCREENING, TECHNICAL, FINAL
        // Optional: recruiter-provided availability windows (ISO datetime strings).
        // If omitted, the agent generates default business-hour slots over the next 5 business days.
        private List<String> availabilityWindow;
    }

    /** Structure Claude returns for proposed interview slots + candidate message. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClaudeSchedulingResponse {
        @JsonDeserialize(as = java.util.ArrayList.class, contentAs = String.class)
        private List<String> proposedSlots; // ISO datetime strings
        private String candidateMessage;     // suggested outreach message (for recruiter approval)
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterviewScheduleResponse {
        private Long id;
        private Long candidateId;
        private String candidateName;
        private Long requisitionId;
        private String requisitionTitle;
        private String interviewType;
        private List<String> proposedSlots;
        private LocalDateTime confirmedSlot;
        private String status;
        private String notes;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfirmSlotRequest {
        private LocalDateTime confirmedSlot;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusUpdateResponse {
        private Long id;
        private Long candidateId;
        private String candidateName;
        private String message;
        private String channel;
        private LocalDateTime sentAt;
    }
}
