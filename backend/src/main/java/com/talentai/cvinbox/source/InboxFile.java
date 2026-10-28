package com.talentai.cvinbox.source;

/**
 * One CV waiting in an inbox, whichever source it came from.
 *
 * @param ref      what the source needs to file it away afterwards (a path, a remote name, a Drive or SharePoint item id)
 * @param fileName the file name shown to the recruiter, always with an extension that matches its content
 * @param content  the file bytes, or null if the file could not be read (it is then recorded as failed)
 */
public record InboxFile(String ref, String fileName, byte[] content) {}
