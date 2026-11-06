package io.opensimilarity.ingest;

import io.opensimilarity.embedding.Chunker;
import io.opensimilarity.embedding.Chunker.Chunk;
import io.opensimilarity.embedding.Embedder;
import io.opensimilarity.fingerprint.Fingerprinter;
import io.opensimilarity.fingerprint.MinHash;
import io.opensimilarity.fingerprint.Tokenizer;
import io.opensimilarity.fingerprint.Tokenizer.Tokens;
import io.opensimilarity.store.IndexPolicy;
import io.opensimilarity.store.Store;
import io.opensimilarity.store.Store.ChunkEmbedding;
import io.opensimilarity.store.Store.CollectionRow;
import io.opensimilarity.store.Store.DocumentRow;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Upload pipeline: extract text, fingerprint, embed, then store everything in one transaction. */
@Service
public class IngestService {

    private final TextExtractor extractor;
    private final Store store;
    private final Optional<Embedder> embedder;

    public IngestService(TextExtractor extractor, Store store, Optional<Embedder> embedder) {
        this.extractor = extractor;
        this.store = store;
        this.embedder = embedder;
    }

    public record Upload(byte[] bytes, String filename, String title, String ownerRef, boolean consentToIndex,
                         IndexPolicy requestedPolicy) {
    }

    public DocumentRow ingest(CollectionRow collection, Upload upload, String actor) {
        if (upload.bytes().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty");
        }
        IndexPolicy policy = effectivePolicy(collection, upload);

        TextExtractor.Extracted extracted = extractor.extract(upload.bytes(), upload.filename());
        String text = extracted.text();
        Tokens tokens = Tokenizer.tokenize(text);
        if (tokens.size() == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "No text could be extracted. Scanned PDFs are not supported because OCR is not implemented.");
        }

        long[] shingles = Fingerprinter.shingleHashes(tokens.words());
        List<ChunkEmbedding> chunks = policy == IndexPolicy.NEVER_INDEX ? List.of() : embedChunks(text, tokens);

        String title = upload.title() != null && !upload.title().isBlank() ? upload.title()
                : upload.filename() != null ? upload.filename() : "Untitled";
        Instant retentionUntil = collection.retentionDays() == null ? null
                : Instant.now().plus(Duration.ofDays(collection.retentionDays()));
        DocumentRow doc = new DocumentRow(UUID.randomUUID(), collection.id(), title, upload.filename(),
                extracted.mimeType(), upload.bytes().length, sha256(upload.bytes()), upload.ownerRef(),
                upload.consentToIndex(), policy, retentionUntil, tokens.size(), Instant.now());

        store.insertDocument(doc, text, actor, Fingerprinter.winnow(shingles),
                MinHash.toBytes(MinHash.sketch(shingles)), embedder.map(Embedder::modelId).orElse(null), chunks);
        store.audit(actor, "DOCUMENT_UPLOADED", "document", doc.id());
        return store.findDocument(doc.id()).orElseThrow();
    }

    /** Indexing is opt-in: without consent a document is never a source, whatever the collection allows. */
    private static IndexPolicy effectivePolicy(CollectionRow collection, Upload upload) {
        if (!upload.consentToIndex()) {
            return IndexPolicy.NEVER_INDEX;
        }
        IndexPolicy requested = upload.requestedPolicy() == null ? collection.indexPolicy() : upload.requestedPolicy();
        if (requested.compareTo(collection.indexPolicy()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "indexPolicy " + requested
                    + " is wider than this collection allows (" + collection.indexPolicy() + ")");
        }
        return requested;
    }

    private List<ChunkEmbedding> embedChunks(String text, Tokens tokens) {
        if (embedder.isEmpty()) {
            return List.of();
        }
        List<Chunk> chunks = Chunker.chunk(text, tokens);
        List<float[]> vectors = embedder.get().embed(chunks.stream().map(c -> c.text(text)).toList());
        List<ChunkEmbedding> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            result.add(new ChunkEmbedding(chunks.get(i), vectors.get(i)));
        }
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
