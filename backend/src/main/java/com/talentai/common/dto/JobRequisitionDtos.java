package com.talentai.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class JobRequisitionDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParseJdRequest {
        private String text;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequisitionRequest {
        private String title;
        private String department;
        private String location;
        private String description;
        private String requiredSkills;
        private String experienceLevel;
        private Integer requiredInterviewRounds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequisitionResponse {
        private Long id;
        private String title;
        private String department;
        private String location;
        private String description;
        private String requiredSkills;
        private String experienceLevel;
        private String status;
        private Integer requiredInterviewRounds;
        private LocalDateTime createdAt;
    }
}
