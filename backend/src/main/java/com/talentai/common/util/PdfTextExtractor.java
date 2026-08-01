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
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();

        if (filename != null && filename.toLowerCase().endsWith(".pdf")
                || "application/pdf".equals(contentType)) {
            return extractFromPdf(file);
        }

        // Fallback: treat as plain text (.txt, .md, etc.)
        return new String(file.getBytes());
    }

    private String extractFromPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return text == null ? "" : text.trim();
        } catch (Exception e) {
            log.error("Failed to extract text from PDF: {}", file.getOriginalFilename(), e);
            throw new IOException("Could not parse PDF file: " + file.getOriginalFilename(), e);
        }
    }
}
