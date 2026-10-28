package com.talentai.cvinbox.source;

import com.talentai.cvinbox.service.GoogleDriveClientService;

import java.util.List;

/** A Google Drive folder shared with a service account. */
public class GoogleDriveSource implements CvSource {

    private final String keyJson;
    private final String folderId;
    private final GoogleDriveClientService client;

    public GoogleDriveSource(String keyJson, String folderId, GoogleDriveClientService client) {
        this.keyJson = keyJson;
        this.folderId = folderId;
        this.client = client;
    }

    @Override
    public String label() {
        return "Google Drive folder " + folderId;
    }

    @Override
    public List<InboxFile> fetchAll() throws Exception {
        return client.listAndFetch(keyJson, folderId).stream()
                .map(f -> new InboxFile(f.getFileId(), f.getFileName(), f.getContent()))
                .toList();
    }

    @Override
    public void fileAway(InboxFile file, boolean considered) {
        client.moveFile(keyJson, folderId, file.ref(), considered);
    }
}
