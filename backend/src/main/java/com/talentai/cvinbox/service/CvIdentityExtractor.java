package com.talentai.cvinbox.service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out who a CV belongs to from its text. Kept free of Spring so it can be tested directly.
 *
 * The email is the key used to match a CV to an existing candidate, so it must be the candidate's own:
 * addresses near the top of the CV are preferred, and addresses that follow words like "referee" or
 * "reference" are ignored.
 */
public final class CvIdentityExtractor {

    private CvIdentityExtractor() {}

    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern REFERENCE_WORD = Pattern.compile("referee|references?|reference\\s+contact", Pattern.CASE_INSENSITIVE);
    private static final int HEADER_LINES = 15;
    private static final int REFERENCE_LOOKBACK = 120;

    /** Lines that are headings, not names. */
    private static final Set<String> NOT_A_NAME = Set.of(
            "curriculum vitae", "resume", "résumé", "cv", "my cv", "my resume", "profile", "personal profile",
            "contact", "contact details", "personal details", "personal information", "career summary",
            "professional summary", "summary", "curriculum vitae of", "work experience", "curriculum");

    /** The candidate's own email (lower case), or null when none can be trusted. */
    public static String extractEmail(String resumeText) {
        if (resumeText == null) return null;
        String header = firstLines(resumeText, HEADER_LINES);
        String fromHeader = firstUsableEmail(header, resumeText.substring(0, Math.min(resumeText.length(), header.length())));
        if (fromHeader != null) return fromHeader;
        return firstUsableEmail(resumeText, resumeText);
    }

    private static String firstUsableEmail(String scanText, String contextText) {
        Matcher m = EMAIL.matcher(scanText);
        while (m.find()) {
            int from = Math.max(0, m.start() - REFERENCE_LOOKBACK);
            String before = contextText.substring(from, Math.min(m.start(), contextText.length()));
            // only the text on the same or previous line counts as context for "referee"
            int lineBreak = before.lastIndexOf('\n');
            String sameLine = lineBreak >= 0 ? before.substring(lineBreak + 1) : before;
            String prevLine = "";
            if (lineBreak > 0) {
                int prevBreak = before.lastIndexOf('\n', lineBreak - 1);
                prevLine = before.substring(prevBreak + 1, lineBreak);
            }
            // The line above only counts when it is a short heading such as "References", not a line
            // that carries a referee's address of its own (which says nothing about this one).
            boolean headingAbove = REFERENCE_WORD.matcher(prevLine).find() && !EMAIL.matcher(prevLine).find()
                    && prevLine.trim().length() <= 30;
            if (REFERENCE_WORD.matcher(sameLine).find() || headingAbove) continue;
            return m.group().toLowerCase();
        }
        return null;
    }

    /** First line that looks like a person's name (2 to 5 words, letters only, not a heading), title-cased. */
    public static String extractName(String resumeText) {
        if (resumeText == null) return null;
        for (String line : resumeText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            if (NOT_A_NAME.contains(trimmed.toLowerCase())) continue;
            String[] words = trimmed.split("\\s+");
            if (words.length >= 2 && words.length <= 5 && trimmed.matches("[A-Za-z .'-]+")) {
                StringBuilder sb = new StringBuilder();
                for (String word : words) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
                }
                return sb.toString();
            }
        }
        return null;
    }

    private static String firstLines(String text, int n) {
        int idx = 0;
        for (int i = 0; i < n; i++) {
            int next = text.indexOf('\n', idx);
            if (next < 0) return text;
            idx = next + 1;
        }
        return text.substring(0, idx);
    }
}
