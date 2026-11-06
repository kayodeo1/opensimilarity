package io.opensimilarity.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    void extractsPlainTextAndCleansIt() {
        TextExtractor.Extracted result = extractor.extract(
                "  Line one\r\nLine\u0000 two\n".getBytes(StandardCharsets.UTF_8), "notes.txt");
        assertThat(result.mimeType()).isEqualTo("text/plain");
        assertThat(result.text()).isEqualTo("Line one\nLine two");
    }

    @Test
    void extractsDocx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Hello from a Word document");
            document.write(out);
        }
        TextExtractor.Extracted result = extractor.extract(out.toByteArray(), "paper.docx");
        assertThat(result.mimeType()).contains("wordprocessingml");
        assertThat(result.text()).contains("Hello from a Word document");
    }

    /** Roadmap Phase 0 asks for at least 95% success on 20 PDFs. These are generated, not real student theses. */
    @Test
    void extractsTextFromTwentyGeneratedMultiPagePdfs() throws Exception {
        int succeeded = 0;
        for (int i = 0; i < 20; i++) {
            String marker = "Final year project number " + i + " on campus water supply";
            TextExtractor.Extracted result = extractor.extract(pdf(marker, 1 + i % 5), "project" + i + ".pdf");
            if (result.mimeType().equals("application/pdf") && result.text().contains(marker)) {
                succeeded++;
            }
        }
        assertThat(succeeded).isEqualTo(20);
    }

    @Test
    void imageOnlyPdfYieldsNoText() throws Exception {
        assertThat(extractor.extract(pdf(null, 1), "scan.pdf").text()).isEmpty();
    }

    @Test
    void rejectsUnsupportedFormatsAndCorruptFiles() {
        assertThatThrownBy(() -> extractor.extract(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, "x.png"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        assertThatThrownBy(() -> extractor.extract("%PDF-1.7 not really a pdf".getBytes(StandardCharsets.UTF_8), "bad.pdf"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
    }

    private static byte[] pdf(String text, int pages) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (int p = 0; p < pages; p++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text != null) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), 11);
                        stream.newLineAtOffset(60, 700);
                        stream.showText(text);
                        stream.endText();
                    }
                }
            }
            document.save(out);
        }
        return out.toByteArray();
    }
}
