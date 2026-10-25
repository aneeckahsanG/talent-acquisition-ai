package com.talentai.admin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;

/** Small helpers for proving a letter was not altered, and for comparing typed names. */
public final class LetterFingerprint {

    private LetterFingerprint() {}

    /** SHA-256 of the letter text (line endings normalised so the same text always gives the same code). */
    public static String sha256(String text) {
        String normalised = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when both names have the same words, ignoring case, accents, punctuation, spacing and word order. */
    public static boolean sameName(String a, String b) {
        return !words(a).isEmpty() && words(a).equals(words(b));
    }

    private static List<String> words(String name) {
        if (name == null) return List.of();
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (plain.isEmpty()) return List.of();
        String[] parts = plain.split(" ");
        Arrays.sort(parts);
        return Arrays.asList(parts);
    }
}
