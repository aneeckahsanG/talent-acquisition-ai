package com.talentai.cvinbox.service;

/** Which files the cloud sources (Google Drive, SharePoint) will read as CVs, shared so they cannot drift apart. */
public final class CvFileRules {

    private CvFileRules() {}

    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    public static final String PDF_MIME = "application/pdf";
    public static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** PDF, Word (.docx) and plain text. Anything else (images, spreadsheets, old .doc) is left alone. */
    public static boolean isSupported(String name, String mime) {
        String n = name == null ? "" : name.toLowerCase();
        String m = mime == null ? "" : mime;
        return PDF_MIME.equals(m) || DOCX_MIME.equals(m) || m.startsWith("text/")
                || n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".txt") || n.endsWith(".md");
    }

    /** Text extraction picks its reader from the extension, so make sure the name carries the right one. */
    public static String withExtension(String name, String mime) {
        String n = name.toLowerCase();
        if (PDF_MIME.equals(mime) && !n.endsWith(".pdf")) return name + ".pdf";
        if (DOCX_MIME.equals(mime) && !n.endsWith(".docx")) return name + ".docx";
        return name;
    }

    public static boolean withinSizeLimit(long sizeBytes) {
        return sizeBytes <= MAX_FILE_BYTES;
    }
}
