package io.opensimilarity.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Finds text a reader cannot see but text extraction still picks up: the "hidden text" integrity flag. Hidden text
 * inflates the word count or dilutes the similarity percentage.
 *
 * <p>DOCX: runs coloured like the page background (white unless the document sets one), runs marked hidden
 * ({@code w:vanish}), and runs at 1 point or smaller. Direct run formatting only: colour that comes from a style is not
 * resolved. PDF: glyphs drawn with a white fill. Text in invisible rendering mode is not flagged, because scanned PDFs
 * with an OCR layer use it legitimately. White text placed on a dark shape is reported too; a person must look.
 */
public final class HiddenTextDetector {

    static final int MAX_SAMPLE_CHARS = 300;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** Half-points, so 2 means 1 pt. */
    private static final int MAX_INVISIBLE_HALF_POINTS = 2;

    private HiddenTextDetector() {
    }

    /** status is CHECKED, NOT_APPLICABLE (plain text has no formatting) or FAILED. */
    public record HiddenText(String status, int words, String sample) {
        static HiddenText none(String status) {
            return new HiddenText(status, 0, "");
        }
    }

    public static HiddenText detect(byte[] bytes, String mimeType) {
        try {
            if (mimeType.equals("application/pdf")) {
                return pdf(bytes);
            }
            if (mimeType.contains("wordprocessingml")) {
                return docx(bytes);
            }
            return HiddenText.none("NOT_APPLICABLE");
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return HiddenText.none("FAILED");
        }
    }

    // ------------------------------------------------------------------ DOCX

    static HiddenText docx(byte[] bytes) throws IOException, XMLStreamException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("word/document.xml")) {
                    return scanDocumentXml(zip);
                }
            }
        }
        return HiddenText.none("FAILED");
    }

    private static HiddenText scanDocumentXml(InputStream in) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader xml = factory.createXMLStreamReader(in);
        String background = "FFFFFF";
        Collector hidden = new Collector();
        boolean inRun = false;
        boolean inRunProperties = false;
        boolean runHidden = false;
        boolean inText = false;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT && W.equals(xml.getNamespaceURI())) {
                String name = xml.getLocalName();
                String val = xml.getAttributeValue(W, "val");
                switch (name) {
                    case "background" -> {
                        String color = xml.getAttributeValue(W, "color");
                        if (color != null && !color.equalsIgnoreCase("auto")) {
                            background = color.toUpperCase(Locale.ROOT);
                        }
                    }
                    case "r" -> {
                        inRun = true;
                        runHidden = false;
                    }
                    case "rPr" -> inRunProperties = inRun;
                    case "color" -> {
                        if (inRunProperties && val != null && val.equalsIgnoreCase(background)) {
                            runHidden = true;
                        }
                    }
                    case "vanish" -> {
                        if (inRunProperties && (val == null || !val.equals("0") && !val.equalsIgnoreCase("false"))) {
                            runHidden = true;
                        }
                    }
                    case "sz" -> {
                        if (inRunProperties && val != null && val.matches("\\d+")
                                && Integer.parseInt(val) <= MAX_INVISIBLE_HALF_POINTS) {
                            runHidden = true;
                        }
                    }
                    case "t" -> inText = inRun;
                    default -> {
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT && W.equals(xml.getNamespaceURI())) {
                switch (xml.getLocalName()) {
                    case "r" -> {
                        inRun = false;
                        hidden.endRun();
                    }
                    case "rPr" -> inRunProperties = false;
                    case "t" -> inText = false;
                    default -> {
                    }
                }
            } else if (event == XMLStreamConstants.CHARACTERS && inText && runHidden) {
                hidden.add(xml.getText());
            }
        }
        return hidden.result();
    }

    // ------------------------------------------------------------------ PDF

    static HiddenText pdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            WhiteTextStripper stripper = new WhiteTextStripper();
            stripper.getText(document);
            stripper.hidden.endRun();
            return stripper.hidden.result();
        }
    }

    private static final class WhiteTextStripper extends PDFTextStripper {
        final Collector hidden = new Collector();

        WhiteTextStripper() throws IOException {
            // The text stripper ignores colour operators by default, so every glyph would read as black.
            addOperator(new SetNonStrokingColorSpace(this));
            addOperator(new SetNonStrokingColor(this));
            addOperator(new SetNonStrokingColorN(this));
            addOperator(new SetNonStrokingDeviceGrayColor(this));
            addOperator(new SetNonStrokingDeviceRGBColor(this));
            addOperator(new SetNonStrokingDeviceCMYKColor(this));
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            super.processTextPosition(text);
            try {
                var state = getGraphicsState();
                RenderingMode mode = state.getTextState().getRenderingMode();
                PDColor fill = state.getNonStrokingColor();
                if (mode.isFill() && fill != null && fill.toRGB() == 0xFFFFFF) {
                    hidden.add(text.getUnicode());
                    return;
                }
            } catch (IOException | UnsupportedOperationException e) {
                // A colour space that cannot be converted to RGB is treated as visible.
            }
            hidden.endRun();
        }
    }

    // ------------------------------------------------------------------ shared

    /** Accumulates hidden characters, keeping runs apart so words from separate runs do not join. */
    private static final class Collector {
        private final StringBuilder all = new StringBuilder();
        private boolean open;

        void add(String s) {
            if (s == null) {
                return;
            }
            all.append(s);
            open = true;
        }

        void endRun() {
            if (open) {
                all.append(' ');
                open = false;
            }
        }

        HiddenText result() {
            String text = all.toString().replaceAll("\\s+", " ").strip();
            int words = text.isEmpty() ? 0 : (int) text.split(" ").length;
            String sample = text.length() > MAX_SAMPLE_CHARS ? text.substring(0, MAX_SAMPLE_CHARS) + "…" : text;
            return new HiddenText("CHECKED", words, sample);
        }
    }
}
