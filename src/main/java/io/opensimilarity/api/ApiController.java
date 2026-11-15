package io.opensimilarity.api;

import io.opensimilarity.check.CheckService;
import io.opensimilarity.check.CheckService.CheckRequest;
import io.opensimilarity.fingerprint.Fingerprinter;
import io.opensimilarity.fingerprint.Tokenizer;
import io.opensimilarity.ingest.IngestService;
import io.opensimilarity.ingest.TextExtractor;
import io.opensimilarity.store.IndexPolicy;
import io.opensimilarity.store.Store;
import io.opensimilarity.store.Store.CollectionRow;
import io.opensimilarity.store.Store.DocumentRow;
import io.opensimilarity.store.Store.TemplateRow;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * The HTTP API. There is no authentication yet: X-Actor is self-declared and only labels audit rows.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final String ACTOR_HEADER = "X-Actor";

    private final Store store;
    private final IngestService ingest;
    private final CheckService checks;
    private final TextExtractor extractor;

    public ApiController(Store store, IngestService ingest, CheckService checks, TextExtractor extractor) {
        this.store = store;
        this.ingest = ingest;
        this.checks = checks;
        this.extractor = extractor;
    }

    public record CreateCollection(String name, String department, IndexPolicy indexPolicy, Integer retentionDays) {
    }

    @PostMapping("/collections")
    public ResponseEntity<CollectionRow> createCollection(
            @RequestBody CreateCollection request,
            @RequestHeader(name = ACTOR_HEADER, defaultValue = "anonymous") String actor) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.retentionDays() != null && request.retentionDays() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "retentionDays must be positive");
        }
        IndexPolicy policy = request.indexPolicy() != null ? request.indexPolicy() : IndexPolicy.COURSE_ONLY;
        if (policy == IndexPolicy.DEPT_ONLY && (request.department() == null || request.department().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DEPT_ONLY requires a department");
        }
        CollectionRow created = store.createCollection(request.name(), request.department(), policy,
                request.retentionDays(), actor);
        store.audit(actor, "COLLECTION_CREATED", "collection", created.id());
        return ResponseEntity.created(URI.create("/api/collections/" + created.id())).body(created);
    }

    @GetMapping("/collections")
    public List<CollectionRow> listCollections() {
        return store.listCollections();
    }

    @GetMapping("/collections/{id}")
    public CollectionRow getCollection(@PathVariable UUID id) {
        return requireCollection(id);
    }

    @GetMapping("/collections/{id}/documents")
    public List<DocumentRow> listDocuments(@PathVariable UUID id) {
        requireCollection(id);
        return store.listDocuments(id);
    }

    /** Indexing is opt-in, so consentToIndex defaults to false and such a document is never a match source. */
    @PostMapping(path = "/collections/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentRow> uploadDocument(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String ownerRef,
            @RequestParam(defaultValue = "false") boolean consentToIndex,
            @RequestParam(required = false) IndexPolicy indexPolicy,
            @RequestHeader(name = ACTOR_HEADER, defaultValue = "anonymous") String actor) throws IOException {
        CollectionRow collection = requireCollection(id);
        DocumentRow created = ingest.ingest(collection, new IngestService.Upload(file.getBytes(),
                file.getOriginalFilename(), title, ownerRef, consentToIndex, indexPolicy), actor);
        return ResponseEntity.created(URI.create("/api/documents/" + created.id())).body(created);
    }

    /**
     * Sets the collection's assignment template: instructions, a cover sheet or question prompts that every student
     * is expected to reuse. Matches on it are excluded. Unlike Turnitin, it can be set or changed after submissions,
     * because it is applied when a check runs.
     */
    @PutMapping(path = "/collections/{id}/template", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TemplateRow putTemplate(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestHeader(name = ACTOR_HEADER, defaultValue = "anonymous") String actor) throws IOException {
        requireCollection(id);
        String text = extractor.extract(file.getBytes(), file.getOriginalFilename()).text();
        int words = Tokenizer.tokenize(text).size();
        if (words < Fingerprinter.K) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A template needs at least " + Fingerprinter.K + " words of text");
        }
        String name = title != null && !title.isBlank() ? title
                : file.getOriginalFilename() != null ? file.getOriginalFilename() : "Template";
        store.saveTemplate(id, name, text, words, actor);
        store.audit(actor, "TEMPLATE_SET", "collection", id);
        return store.findTemplate(id).orElseThrow();
    }

    @GetMapping("/collections/{id}/template")
    public TemplateRow getTemplate(@PathVariable UUID id) {
        requireCollection(id);
        return store.findTemplate(id).orElseThrow(() -> notFound("template for collection", id));
    }

    @DeleteMapping("/collections/{id}/template")
    public ResponseEntity<Void> deleteTemplate(
            @PathVariable UUID id,
            @RequestHeader(name = ACTOR_HEADER, defaultValue = "anonymous") String actor) {
        requireCollection(id);
        if (!store.deleteTemplate(id)) {
            throw notFound("template for collection", id);
        }
        store.audit(actor, "TEMPLATE_DELETED", "collection", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents/{id}")
    public DocumentRow getDocument(@PathVariable UUID id) {
        return requireLiveDocument(id);
    }

    @GetMapping(path = "/documents/{id}/text", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String getDocumentText(
            @PathVariable UUID id,
            @RequestHeader(name = ACTOR_HEADER, defaultValue = "anonymous") String actor) {
        requireLiveDocument(id);
        store.audit(actor, "DOCUMENT_TEXT_VIEWED", "document", id);
        return store.findText(id).orElseThrow();
    }

    @PostMapping(path = "/documents/{id}/checks", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> runCheck(
            @PathVariable UUID id,
            @RequestBody(required = false) CheckRequest request,
            @RequestHeader(name = ACTOR_HEADER, defaultValue = "anonymous") String actor) {
        DocumentRow document = requireLiveDocument(id);
        CollectionRow collection = requireCollection(document.collectionId());
        CheckService.Stored stored = checks.run(document, collection, request, actor);
        return ResponseEntity.created(URI.create("/api/checks/" + stored.checkId())).body(stored.reportJson());
    }

    @GetMapping(path = "/checks/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String getReport(
            @PathVariable UUID id,
            @RequestHeader(name = ACTOR_HEADER, defaultValue = "anonymous") String actor) {
        String report = store.findReport(id).orElseThrow(() -> notFound("check", id));
        store.audit(actor, "REPORT_VIEWED", "check", id);
        return report;
    }

    private CollectionRow requireCollection(UUID id) {
        return store.findCollection(id).orElseThrow(() -> notFound("collection", id));
    }

    /** Purging expired documents is not implemented yet, so expiry is enforced at read time. */
    private DocumentRow requireLiveDocument(UUID id) {
        DocumentRow document = store.findDocument(id).orElseThrow(() -> notFound("document", id));
        if (document.isExpired()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Document " + id + " is past its retention date");
        }
        return document;
    }

    private static ResponseStatusException notFound(String kind, UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + kind + " with id " + id);
    }
}
