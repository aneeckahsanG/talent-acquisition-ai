package com.talentai.common;

import com.talentai.common.util.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PdfTextExtractorDocxTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    private static byte[] zipWith(String entryName, String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void readsParagraphsTabsAndEntitiesFromAWordFile() throws Exception {
        String xml = "<?xml version=\"1.0\"?><w:document><w:body>"
                + "<w:p><w:r><w:t>Aisha Rahman</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t xml:space=\"preserve\">Skills: Java </w:t></w:r><w:r><w:tab/><w:t>&amp; Spring</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>aisha@example.com</w:t></w:r></w:p>"
                + "</w:body></w:document>";

        String text = extractor.extractText("cv.docx", zipWith("word/document.xml", xml));

        assertEquals("Aisha Rahman\nSkills: Java \t& Spring\naisha@example.com", text);
    }

    @Test
    void extensionMatchIsCaseInsensitive() throws Exception {
        byte[] docx = zipWith("word/document.xml", "<w:p><w:t>Hello</w:t></w:p>");
        assertEquals("Hello", extractor.extractText("CV.DOCX", docx));
    }

    @Test
    void aZipWithoutWordContentIsRejected() throws Exception {
        byte[] notWord = zipWith("other.txt", "hi");
        assertThrows(IOException.class, () -> extractor.extractText("cv.docx", notWord));
    }

    @Test
    void plainTextIsStillReadAsText() throws Exception {
        assertEquals("just text", extractor.extractText("cv.txt", "just text".getBytes(StandardCharsets.UTF_8)));
    }
}
