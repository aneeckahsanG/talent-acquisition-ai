package com.talentai.cvinbox.source;

import com.talentai.cvinbox.service.SharePointClientService;

import java.util.List;

/** A SharePoint folder, reached through an Entra ID app registration. */
public class SharePointSource implements CvSource {

    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String folderLink;
    private final SharePointClientService client;

    public SharePointSource(String tenantId, String clientId, String clientSecret, String folderLink, SharePointClientService client) {
        this.tenantId = tenantId;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.folderLink = folderLink;
        this.client = client;
    }

    @Override
    public String label() {
        return "SharePoint folder " + folderLink;
    }

    @Override
    public List<InboxFile> fetchAll() throws Exception {
        return client.listAndFetch(tenantId, clientId, clientSecret, folderLink).stream()
                .map(f -> new InboxFile(f.getItemId(), f.getFileName(), f.getContent()))
                .toList();
    }

    @Override
    public void fileAway(InboxFile file, boolean considered) {
        client.moveFile(tenantId, clientId, clientSecret, folderLink, file.ref(), considered);
    }
}
