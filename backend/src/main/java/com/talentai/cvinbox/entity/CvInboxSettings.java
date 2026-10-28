package com.talentai.cvinbox.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Singleton row (id is always 1) holding the live, UI-editable CV Inbox SFTP configuration. */
@Entity
@Table(name = "cv_inbox_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CvInboxSettings {

    @Id
    private Long id;

    @Column(nullable = false)
    private String mode; // EMBEDDED, EXTERNAL, GOOGLE_DRIVE, SHAREPOINT

    @Column(name = "embedded_port")
    private Integer embeddedPort;

    @Column(name = "embedded_username")
    private String embeddedUsername;

    @Column(name = "embedded_password")
    private String embeddedPassword;

    @Column(name = "external_host")
    private String externalHost;

    @Column(name = "external_port")
    private Integer externalPort;

    @Column(name = "external_username")
    private String externalUsername;

    @Column(name = "external_password")
    private String externalPassword;

    @Column(name = "external_root_path")
    private String externalRootPath;

    @Column(name = "proxy_enabled")
    private Boolean proxyEnabled;

    @Column(name = "proxy_host")
    private String proxyHost;

    @Column(name = "proxy_port")
    private Integer proxyPort;

    @Column(name = "google_folder_id")
    private String googleFolderId;

    /** The service account key file, as downloaded from Google Cloud. Never returned by the API. */
    @Column(name = "google_service_account_json")
    private String googleServiceAccountJson;

    @Column(name = "sharepoint_tenant_id")
    private String sharepointTenantId;

    @Column(name = "sharepoint_client_id")
    private String sharepointClientId;

    /** The Entra ID app registration's client secret. Never returned by the API. */
    @Column(name = "sharepoint_client_secret")
    private String sharepointClientSecret;

    @Column(name = "sharepoint_folder_link")
    private String sharepointFolderLink;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    @PrePersist
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
