package com.talentai.common.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts plain text content from uploaded resume files (PDF, Word .docx or plain text).
 * Used by the Screening Agent and Sourcing Agent to obtain resume text
 * for Claude-based analysis.
 */
@Component
@Slf4j
public class PdfTextExtractor {

    private static final int MAX_DOCX_XML_BYTES = 20 * 1024 * 1024;

    public String extractText(MultipartFile file) throws IOException {
        return extractText(file.getOriginalFilename(), file.getBytes());
    }

    /** Same extraction logic as {@link #extractText(MultipartFile)}, for callers that only have
     *  raw file bytes (e.g. files read from disk rather than an HTTP upload). */
    public String extractText(String filename, byte[] content) throws IOException {
        if (filename != null && filename.toLowerCase().endsWith(".pdf")) {
            return extractFromPdf(filename, content);
        }

        if (filename != null && filename.toLowerCase().endsWith(".docx")) {
            return extractFromDocx(filename, content);
        }

        // Fallback: treat as plain text (.txt, .md, etc.)
        return new String(content);
    }

    /** A .docx is a zip whose body text lives in word/document.xml; reading that directly avoids a
     *  full Word library for what is only ever plain-text extraction. */
    private String extractFromDocx(String filename, byte[] content) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(entry.getName())) continue;
                String xml = new String(zip.readNBytes(MAX_DOCX_XML_BYTES + 1), StandardCharsets.UTF_8);
                if (xml.length() > MAX_DOCX_XML_BYTES) {
                    throw new IOException("Word document is too large to read: " + filename);
                }
                return xml
                        .replaceAll("</w:p>|<w:br[^>]*/>", "\n")
                        .replaceAll("<w:tab[^>]*/>", "\t")
                        .replaceAll("<[^>]+>", "")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&apos;", "'").replace("&amp;", "&")
                        .replaceAll("\n{3,}", "\n\n")
                        .trim();
            }
            throw new IOException("Not a valid Word document: " + filename);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to extract text from Word document: {}", filename, e);
            throw new IOException("Could not parse Word document: " + filename, e);
        }
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
