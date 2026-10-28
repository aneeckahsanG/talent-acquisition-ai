package com.talentai.cvinbox.source;

import com.talentai.cvinbox.entity.CvInboxSettings;
import com.talentai.cvinbox.service.ExternalSftpClientService;
import com.talentai.cvinbox.service.GoogleDriveClientService;
import com.talentai.cvinbox.service.SharePointClientService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Builds the {@link CvSource} that the saved settings point at. */
@Component
public class CvSourceFactory {

    private final ExternalSftpClientService sftpClient;
    private final GoogleDriveClientService driveClient;
    private final SharePointClientService sharePointClient;
    private final String embeddedRootPath;

    public CvSourceFactory(ExternalSftpClientService sftpClient,
                           GoogleDriveClientService driveClient,
                           SharePointClientService sharePointClient,
                           @Value("${app.cv-inbox.root-path}") String embeddedRootPath) {
        this.sftpClient = sftpClient;
        this.driveClient = driveClient;
        this.sharePointClient = sharePointClient;
        this.embeddedRootPath = embeddedRootPath;
    }

    /** No saved settings, or the embedded mode, means the local folder the embedded SFTP server writes to. */
    public CvSource forSettings(CvInboxSettings settings) {
        String mode = settings == null ? "EMBEDDED" : settings.getMode();
        return switch (mode == null ? "EMBEDDED" : mode) {
            case "EXTERNAL" -> new SftpSource(settings, sftpClient);
            case "GOOGLE_DRIVE" -> new GoogleDriveSource(settings.getGoogleServiceAccountJson(), settings.getGoogleFolderId(), driveClient);
            case "SHAREPOINT" -> new SharePointSource(settings.getSharepointTenantId(), settings.getSharepointClientId(),
                    settings.getSharepointClientSecret(), settings.getSharepointFolderLink(), sharePointClient);
            default -> new EmbeddedFolderSource(embeddedRootPath);
        };
    }
}
