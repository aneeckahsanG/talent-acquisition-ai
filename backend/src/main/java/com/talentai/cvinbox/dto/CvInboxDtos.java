package com.talentai.cvinbox.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class CvInboxDtos {

    /** One row in the Considered/Excluded table. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CvInboxItemResponse {
        private Long id;
        private String fileName;
        private Long candidateId;
        private String candidateName;
        private String candidateEmail;
        private String decision;
        private String status;
        private Long bestRequisitionId;
        private String bestRequisitionTitle;
        private BigDecimal bestMatchScore;
        private String bestRationale;
        private String errorMessage;
        private List<RoleMatchDetail> roleMatches;
        private LocalDateTime processedAt;
    }

    /** One open role this CV was scored against, for the detail modal. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleMatchDetail {
        private Long requisitionId;
        private String requisitionTitle;
        private BigDecimal matchScore;
        private String rationale;
        private String pipelineStage; // non-null if this role's match placed the candidate in the pipeline
    }

    /** Result of a "Process now" run. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessNowResponse {
        private int filesFound;
        private int processed;
        private int considered;
        private int excluded;
        private int failed;
    }

    /** Where HR should point their SFTP client. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SftpConnectionInfo {
        private String host;
        private int port;
        private String username;
    }

    /** Request body for archiving one or more items (individual select or "select all"). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ArchiveRequest {
        private List<Long> ids;
    }

    /** Current CV Inbox SFTP configuration. Passwords are never echoed back — only whether one is set. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CvInboxSettingsResponse {
        private String mode;
        private Integer embeddedPort;
        private String embeddedUsername;
        private boolean embeddedPasswordSet;
        private String externalHost;
        private Integer externalPort;
        private String externalUsername;
        private boolean externalPasswordSet;
        private String externalRootPath;
        private boolean proxyEnabled;
        private String proxyHost;
        private Integer proxyPort;
        private boolean embeddedRunning;
        private String embeddedStatus;
    }

    /** Save request. A blank password field means "leave the stored password unchanged". */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SaveSettingsRequest {
        private String mode;
        private Integer embeddedPort;
        private String embeddedUsername;
        private String embeddedPassword;
        private String externalHost;
        private Integer externalPort;
        private String externalUsername;
        private String externalPassword;
        private String externalRootPath;
        private boolean proxyEnabled;
        private String proxyHost;
        private Integer proxyPort;
    }

    /** Test-connection request — mirrors whatever the settings form currently holds, saved or not. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestConnectionRequest {
        private String mode;
        private Integer embeddedPort;
        private String embeddedUsername;
        private String embeddedPassword;
        private String externalHost;
        private Integer externalPort;
        private String externalUsername;
        private String externalPassword;
        private boolean proxyEnabled;
        private String proxyHost;
        private Integer proxyPort;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestConnectionResponse {
        private boolean success;
        private String message;
    }
}
