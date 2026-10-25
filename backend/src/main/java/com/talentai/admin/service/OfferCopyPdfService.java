package com.talentai.admin.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/** Renders the offer letter, plus an acceptance record, as a plain PDF copy. */
@Service
public class OfferCopyPdfService {

    private static final float MARGIN = 56f;
    private static final float FONT_SIZE = 10.5f;
    private static final float LEADING = 14.5f;

    public byte[] render(String title, String letterText, List<String> recordLines) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            Writer w = new Writer(doc);

            w.line(title, bold, 15f);
            w.gap();
            for (String paragraph : letterText.split("\\R", -1)) w.wrapped(paragraph, regular);

            if (recordLines != null && !recordLines.isEmpty()) {
                w.gap();
                w.rule();
                w.line("Electronic acceptance record", bold, 12f);
                w.gap();
                for (String l : recordLines) w.wrapped(l, regular);
            }
            w.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the PDF copy", e);
        }
    }

    /** Keeps only characters the standard PDF font can draw; anything else becomes '?'. */
    private static String printable(String s, PDFont font) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            try {
                font.encode(String.valueOf(c));
                sb.append(c);
            } catch (Exception e) {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private static final class Writer {
        private final PDDocument doc;
        private PDPageContentStream cs;
        private float y;
        private final float width = PDRectangle.A4.getWidth() - 2 * MARGIN;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        private void ensureRoom(float needed) throws IOException {
            if (y - needed < MARGIN) newPage();
        }

        void gap() throws IOException {
            ensureRoom(LEADING);
            y -= LEADING;
        }

        void rule() throws IOException {
            ensureRoom(LEADING);
            cs.moveTo(MARGIN, y);
            cs.lineTo(MARGIN + width, y);
            cs.stroke();
            y -= LEADING;
        }

        void line(String text, PDFont font, float size) throws IOException {
            ensureRoom(size + 4);
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(MARGIN, y);
            cs.showText(printable(text, font));
            cs.endText();
            y -= size + 4;
        }

        void wrapped(String text, PDFont font) throws IOException {
            if (text.isBlank()) { gap(); return; }
            String current = "";
            for (String word : text.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (font.getStringWidth(printable(candidate, font)) / 1000f * FONT_SIZE > width && !current.isEmpty()) {
                    line(current, font, FONT_SIZE);
                    current = word;
                } else {
                    current = candidate;
                }
            }
            if (!current.isEmpty()) line(current, font, FONT_SIZE);
        }

        void close() throws IOException {
            cs.close();
        }
    }
}
