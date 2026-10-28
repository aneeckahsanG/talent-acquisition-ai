package com.talentai.cvinbox.source;

import java.util.List;

/**
 * Where the Resume Drop Zone gets CVs from: a local folder, an SFTP server, a Google Drive folder or a
 * SharePoint folder. Processing is the same whichever it is; a source only knows how to hand over the
 * waiting files and how to file a processed one away.
 */
public interface CvSource {

    /** Short name for logs, e.g. "Google Drive". */
    String label();

    /** Every CV currently waiting, downloaded. Throws if the inbox itself cannot be read. */
    List<InboxFile> fetchAll() throws Exception;

    /** Files a processed CV into the "considered" or "rejected" sub-folder. Best effort: it must not throw,
     *  so one stuck file never stops the rest of the batch. */
    void fileAway(InboxFile file, boolean considered);
}
