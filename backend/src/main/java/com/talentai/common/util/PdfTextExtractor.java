package com.talentai.common.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Extracts plain text content from uploaded resume files (PDF or plain text).
 * Used by the Screening Agent and Sourcing Agent to obtain resume text
 * for Claude-based analysis.
 */
@Component
@Slf4j
public class PdfTextExtractor {

    public String extractText(MultipartFile file) throws IOException {
        return extractText(file.getOriginalFilename(), file.getBytes());
    }

    /** Same extraction logic as {@link #extractText(MultipartFile)}, for callers that only have
     *  raw file bytes (e.g. files read from disk rather than an HTTP upload). */
    public String extractText(String filename, byte[] content) throws IOException {
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            return extractFromPdf(filename, content);
        }

        // Fallback: treat as plain text (.txt, .md, etc.)
        return new String(content);
    }

    private String extractFromPdf(String filename, byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            log.error("Failed to extract text from PDF: {}", filename, e);
            throw new IOException("Could not parse PDF file: " + filename, e);
        }
    }
}
