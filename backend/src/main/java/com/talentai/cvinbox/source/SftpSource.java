package com.talentai.cvinbox.source;

import com.talentai.cvinbox.entity.CvInboxSettings;
import com.talentai.cvinbox.service.ExternalSftpClientService;

import java.util.List;

/** An SFTP server HR already has; the app connects out to it. */
public class SftpSource implements CvSource {

    private final CvInboxSettings settings;
    private final ExternalSftpClientService client;

    public SftpSource(CvInboxSettings settings, ExternalSftpClientService client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public String label() {
        return "external SFTP " + settings.getExternalHost() + ":" + settings.getExternalPort();
    }

    @Override
    public List<InboxFile> fetchAll() throws Exception {
        return client.listAndFetch(settings).stream()
                .map(f -> new InboxFile(f.getFileName(), f.getFileName(), f.getContent()))
                .toList();
    }

    @Override
    public void fileAway(InboxFile file, boolean considered) {
        client.moveRemoteFile(settings, file.ref(), considered);
    }
}
