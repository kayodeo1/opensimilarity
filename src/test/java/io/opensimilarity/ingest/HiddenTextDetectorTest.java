package io.opensimilarity.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.opensimilarity.ingest.HiddenTextDetector.HiddenText;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

class HiddenTextDetectorTest {

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Test
    void findsWhiteVanishedAndTinyRunsInDocx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph p = document.createParagraph();
            p.createRun().setText("Visible opening words. ");
            XWPFRun white = p.createRun();
            white.setText("padding words in white");
            white.setColor("FFFFFF");
            XWPFRun hidden = p.createRun();
            hidden.setText("vanished text");
            hidden.setVanish(true);
            XWPFRun tiny = p.createRun();
            tiny.setText("tiny");
            tiny.setFontSize(1);
            p.createRun().setText(" Visible closing words.");
            document.write(out);
        }
        HiddenText result = HiddenTextDetector.detect(out.toByteArray(), DOCX);
        assertThat(result.status()).isEqualTo("CHECKED");
        assertThat(result.words()).isEqualTo(7);
        assertThat(result.sample()).contains("padding words in white").contains("vanished text").contains("tiny");
        assertThat(result.sample()).doesNotContain("Visible");
    }

    @Test
    void cleanDocxHasNoHiddenText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFRun run = document.createParagraph().createRun();
            run.setText("Ordinary text in red is still visible");
            run.setColor("C00000");
            document.write(out);
        }
        assertThat(HiddenTextDetector.detect(out.toByteArray(), DOCX).words()).isZero();
    }

    @Test
    void findsWhiteTextInPdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), 11);
                stream.beginText();
                stream.newLineAtOffset(60, 700);
                stream.showText("Visible line of text");
                stream.endText();
                stream.setNonStrokingColor(Color.WHITE);
                stream.beginText();
                stream.newLineAtOffset(60, 680);
                stream.showText("invisible white padding");
                stream.endText();
            }
            document.save(out);
        }
        HiddenText result = HiddenTextDetector.detect(out.toByteArray(), "application/pdf");
        assertThat(result.status()).isEqualTo("CHECKED");
        assertThat(result.sample()).contains("invisible white padding").doesNotContain("Visible");
        assertThat(result.words()).as(result.sample()).isEqualTo(3);
    }

    @Test
    void plainTextIsNotApplicable() {
        assertThat(HiddenTextDetector.detect("hello".getBytes(StandardCharsets.UTF_8), "text/plain").status())
                .isEqualTo("NOT_APPLICABLE");
    }
}
