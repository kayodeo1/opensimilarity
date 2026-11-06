package io.opensimilarity.ingest;

import java.io.IOException;
import java.util.Set;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.SAXException;

/** Extracts plain text from PDF, DOCX and TXT uploads using Apache Tika. */
@Component
public class TextExtractor {

    static final int MAX_CHARS = 20_000_000;

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final Set<String> ACCEPTED = Set.of("application/pdf", "text/plain", DOCX);

    private final AutoDetectParser parser = new AutoDetectParser();

    public record Extracted(String text, String mimeType) {
    }

    public Extracted extract(byte[] bytes, String filename) {
        Metadata metadata = new Metadata();
        if (filename != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        }
        try (TikaInputStream stream = TikaInputStream.get(bytes)) {
            String mimeType = parser.getDetector().detect(stream, metadata).getBaseType().toString();
            if (!ACCEPTED.contains(mimeType)) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "Only PDF, DOCX and TXT are accepted; this file was detected as " + mimeType);
            }
            BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
            parser.parse(stream, handler, metadata, new ParseContext());
            return new Extracted(clean(handler.toString()), mimeType);
        } catch (IOException | TikaException | SAXException e) {
            if (WriteLimitReachedException.isWriteLimitReached(e)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Document exceeds the limit of " + MAX_CHARS + " extracted characters");
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Could not extract text from this file (it may be corrupt or password protected)", e);
        }
    }

    /**
     * Tika turns characters that are invalid in XML, such as NUL, into U+FFFD; left in place they would split words.
     * Line endings are unified so that offsets do not depend on the convention of the uploaded file.
     */
    private static String clean(String text) {
        return text.replace("\uFFFD", "").replace("\r\n", "\n").replace('\r', '\n').strip();
    }
}
