package io.opensimilarity.store;

import io.opensimilarity.embedding.Chunker.Chunk;
import io.opensimilarity.fingerprint.Fingerprinter.Fingerprint;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** All SQL lives here. */
@Repository
public class Store {

    /** Applied everywhere a document could become a source, so policy cannot be bypassed by a code path. */
    private static final String ELIGIBLE_SOURCE = """
            d.id <> :queryId
            AND d.index_policy <> 'NEVER_INDEX'
            AND (d.retention_until IS NULL OR d.retention_until > now())
            AND (d.collection_id = :queryCollection
                 OR (d.index_policy = 'DEPT_ONLY' AND c.department = CAST(:queryDepartment AS text)))
            """;

    private static final String ELIGIBLE_FROM = " documents d JOIN collections c ON c.id = d.collection_id ";

    private static final String DOCUMENT_METADATA = """
            d.id, d.collection_id, d.title, d.original_filename, d.mime_type, d.byte_size, d.sha256, d.owner_ref,
            d.consent_to_index, d.index_policy, d.retention_until, d.word_count, d.created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public Store(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record CollectionRow(UUID id, String name, String department, IndexPolicy indexPolicy,
                                Integer retentionDays, Instant createdAt) {
    }

    public record DocumentRow(UUID id, UUID collectionId, String title, String originalFilename, String mimeType,
                              long byteSize, String sha256, String ownerRef, boolean consentToIndex,
                              IndexPolicy indexPolicy, Instant retentionUntil, int wordCount, Instant createdAt) {
        public boolean isExpired() {
            return retentionUntil != null && retentionUntil.isBefore(Instant.now());
        }
    }

    /** What a chunk embedding is stored with. */
    public record ChunkEmbedding(Chunk chunk, float[] vector) {
    }

    public record NearestChunk(UUID documentId, int ordinal, int wordStart, int wordEnd, int charStart, int charEnd,
                               double cosine) {
    }

    /** The document being checked, which determines what it may be compared against. */
    public record QueryScope(UUID documentId, UUID collectionId, String department) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("queryId", documentId)
                    .addValue("queryCollection", collectionId)
                    .addValue("queryDepartment", department);
        }
    }

    // ---------------------------------------------------------------- collections

    public CollectionRow createCollection(String name, String department, IndexPolicy policy, Integer retentionDays,
                                          String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO collections (id, name, department, index_policy, retention_days, created_by)
                VALUES (:id, :name, :department, :policy, :retentionDays, :actor)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("name", name).addValue("department", department)
                .addValue("policy", policy.name()).addValue("retentionDays", retentionDays).addValue("actor", actor));
        return findCollection(id).orElseThrow();
    }

    public Optional<CollectionRow> findCollection(UUID id) {
        return jdbc.query("SELECT * FROM collections WHERE id = :id", Map.of("id", id), Store::mapCollection)
                .stream().findFirst();
    }

    public List<CollectionRow> listCollections() {
        return jdbc.query("SELECT * FROM collections ORDER BY created_at DESC", Store::mapCollection);
    }

    // ---------------------------------------------------------------- documents

    /** Writes a document and its index data atomically, so a document is never half-indexed. */
    public void insertDocument(DocumentRow doc, String fullText, String actor, List<Fingerprint> fingerprints,
                               byte[] sketch, String embeddingModel, List<ChunkEmbedding> chunks) {
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO documents (id, collection_id, title, original_filename, mime_type, byte_size, sha256,
                        owner_ref, consent_to_index, index_policy, retention_until, full_text, word_count, created_by)
                    VALUES (:id, :collection, :title, :filename, :mime, :size, :sha256, :owner, :consent, :policy,
                        :retentionUntil, :text, :words, :actor)
                    """, new MapSqlParameterSource()
                    .addValue("id", doc.id()).addValue("collection", doc.collectionId())
                    .addValue("title", doc.title()).addValue("filename", doc.originalFilename())
                    .addValue("mime", doc.mimeType()).addValue("size", doc.byteSize())
                    .addValue("sha256", doc.sha256()).addValue("owner", doc.ownerRef())
                    .addValue("consent", doc.consentToIndex()).addValue("policy", doc.indexPolicy().name())
                    .addValue("retentionUntil", timestamp(doc.retentionUntil()), java.sql.Types.TIMESTAMP)
                    .addValue("text", fullText).addValue("words", doc.wordCount()).addValue("actor", actor));
            if (doc.indexPolicy() == IndexPolicy.NEVER_INDEX) {
                return;
            }
            SqlParameterSource[] postings = fingerprints.stream()
                    .map(f -> new MapSqlParameterSource()
                            .addValue("hash", f.hash()).addValue("doc", doc.id()).addValue("pos", f.pos()))
                    .toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate("INSERT INTO shingles (hash, doc_id, pos) VALUES (:hash, :doc, :pos)", postings);
            jdbc.update("INSERT INTO sketches (doc_id, minhash) VALUES (:doc, :minhash)",
                    new MapSqlParameterSource().addValue("doc", doc.id()).addValue("minhash", sketch));
            SqlParameterSource[] chunkRows = chunks.stream()
                    .map(c -> new MapSqlParameterSource()
                            .addValue("doc", doc.id()).addValue("ordinal", c.chunk().ordinal())
                            .addValue("wordStart", c.chunk().wordStart()).addValue("wordEnd", c.chunk().wordEnd())
                            .addValue("charStart", c.chunk().charStart()).addValue("charEnd", c.chunk().charEnd())
                            .addValue("model", embeddingModel).addValue("embedding", vectorLiteral(c.vector())))
                    .toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate("""
                    INSERT INTO chunks (doc_id, ordinal, word_start, word_end, char_start, char_end, model, embedding)
                    VALUES (:doc, :ordinal, :wordStart, :wordEnd, :charStart, :charEnd, :model,
                            CAST(:embedding AS halfvec(384)))
                    """, chunkRows);
        });
    }

    public Optional<DocumentRow> findDocument(UUID id) {
        return jdbc.query("SELECT " + DOCUMENT_METADATA + " FROM documents d WHERE d.id = :id",
                Map.of("id", id), Store::mapDocument).stream().findFirst();
    }

    public List<DocumentRow> listDocuments(UUID collectionId) {
        return jdbc.query("SELECT " + DOCUMENT_METADATA
                        + " FROM documents d WHERE d.collection_id = :id ORDER BY d.created_at",
                Map.of("id", collectionId), Store::mapDocument);
    }

    public Optional<String> findText(UUID documentId) {
        return jdbc.queryForList("SELECT full_text FROM documents WHERE id = :id", Map.of("id", documentId), String.class)
                .stream().findFirst();
    }

    public Map<UUID, String> findTexts(Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> texts = new HashMap<>();
        jdbc.query("SELECT id, full_text FROM documents WHERE id IN (:ids)", Map.of("ids", documentIds),
                rs -> {
                    texts.put(rs.getObject("id", UUID.class), rs.getString("full_text"));
                });
        return texts;
    }

    public Map<UUID, String> findTitles(Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> titles = new HashMap<>();
        jdbc.query("SELECT id, title FROM documents WHERE id IN (:ids)", Map.of("ids", documentIds),
                rs -> {
                    titles.put(rs.getObject("id", UUID.class), rs.getString("title"));
                });
        return titles;
    }

    // ---------------------------------------------------------------- candidate retrieval

    public int countEligibleSources(QueryScope scope) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM" + ELIGIBLE_FROM + "WHERE " + ELIGIBLE_SOURCE,
                scope.params(), Integer.class);
        return count == null ? 0 : count;
    }

    /** For each eligible document sharing at least one fingerprint: how many distinct hashes it shares. */
    public Map<UUID, Integer> sharedFingerprintCounts(QueryScope scope, Collection<Long> hashes) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (hashes.isEmpty()) {
            return counts;
        }
        String array = hashes.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        jdbc.query("SELECT s.doc_id, count(DISTINCT s.hash) AS shared FROM shingles s"
                        + " JOIN documents d ON d.id = s.doc_id JOIN collections c ON c.id = d.collection_id"
                        + " WHERE s.hash = ANY (CAST(:hashes AS bigint[])) AND " + ELIGIBLE_SOURCE
                        + " GROUP BY s.doc_id",
                scope.params().addValue("hashes", array),
                rs -> {
                    counts.put(rs.getObject("doc_id", UUID.class), rs.getInt("shared"));
                });
        return counts;
    }

    public Map<UUID, byte[]> eligibleSketches(QueryScope scope) {
        Map<UUID, byte[]> sketches = new HashMap<>();
        jdbc.query("SELECT d.id, s.minhash FROM" + ELIGIBLE_FROM + "JOIN sketches s ON s.doc_id = d.id WHERE "
                        + ELIGIBLE_SOURCE,
                scope.params(),
                rs -> {
                    sketches.put(rs.getObject("id", UUID.class), rs.getBytes("minhash"));
                });
        return sketches;
    }

    /** Nearest eligible chunks for each query vector, in input order. Uses HNSW with iterative scans for filtering. */
    public List<List<NearestChunk>> nearestChunks(QueryScope scope, String model, List<float[]> vectors, int limit) {
        return tx.execute(status -> {
            jdbc.getJdbcTemplate().execute("SET LOCAL hnsw.iterative_scan = 'relaxed_order'");
            jdbc.getJdbcTemplate().execute("SET LOCAL hnsw.ef_search = 100");
            // The same statement runs once per chunk. A cached generic plan cannot see which collection is searched,
            // so it may pick a filtered index scan where an exact scan of a small collection would be far cheaper.
            jdbc.getJdbcTemplate().execute("SET LOCAL plan_cache_mode = force_custom_plan");
            List<List<NearestChunk>> results = new ArrayList<>(vectors.size());
            for (float[] vector : vectors) {
                MapSqlParameterSource params = scope.params()
                        .addValue("vector", vectorLiteral(vector))
                        .addValue("model", model)
                        .addValue("limit", limit);
                results.add(jdbc.query("""
                        WITH nearest AS MATERIALIZED (
                            SELECT ch.doc_id, ch.ordinal, ch.word_start, ch.word_end, ch.char_start, ch.char_end,
                                   ch.embedding <=> CAST(:vector AS halfvec(384)) AS distance
                            FROM chunks ch
                            JOIN documents d ON d.id = ch.doc_id
                            JOIN collections c ON c.id = d.collection_id
                            WHERE ch.model = :model AND
                        """ + ELIGIBLE_SOURCE + """
                            ORDER BY distance
                            LIMIT :limit
                        )
                        SELECT * FROM nearest ORDER BY distance
                        """, params, (rs, row) -> new NearestChunk(
                        rs.getObject("doc_id", UUID.class), rs.getInt("ordinal"),
                        rs.getInt("word_start"), rs.getInt("word_end"),
                        rs.getInt("char_start"), rs.getInt("char_end"),
                        1.0 - rs.getDouble("distance"))));
            }
            return results;
        });
    }

    // ---------------------------------------------------------------- checks and audit

    public void saveCheck(UUID id, UUID documentId, double overlapPercent, String reportJson, String actor) {
        jdbc.update("""
                INSERT INTO checks (id, document_id, overlap_percent, report, created_by)
                VALUES (:id, :document, :overlap, CAST(:report AS jsonb), :actor)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("document", documentId).addValue("overlap", overlapPercent)
                .addValue("report", reportJson).addValue("actor", actor));
    }

    public Optional<String> findReport(UUID checkId) {
        return jdbc.queryForList("SELECT report::text FROM checks WHERE id = :id", Map.of("id", checkId), String.class)
                .stream().findFirst();
    }

    public void audit(String actor, String action, String entityType, UUID entityId) {
        jdbc.update("""
                INSERT INTO audit_log (actor, action, entity_type, entity_id)
                VALUES (:actor, :action, :type, :entity)
                """, new MapSqlParameterSource()
                .addValue("actor", actor).addValue("action", action)
                .addValue("type", entityType).addValue("entity", entityId));
    }

    public String pgvectorVersion() {
        return jdbc.getJdbcTemplate().queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
    }

    // ---------------------------------------------------------------- mapping

    private static String vectorLiteral(float[] vector) {
        return Arrays.toString(vector).replace(" ", "");
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static CollectionRow mapCollection(ResultSet rs, int row) throws SQLException {
        return new CollectionRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("department"),
                IndexPolicy.valueOf(rs.getString("index_policy")), rs.getObject("retention_days", Integer.class),
                instant(rs, "created_at"));
    }

    private static DocumentRow mapDocument(ResultSet rs, int row) throws SQLException {
        return new DocumentRow(rs.getObject("id", UUID.class), rs.getObject("collection_id", UUID.class),
                rs.getString("title"), rs.getString("original_filename"), rs.getString("mime_type"),
                rs.getLong("byte_size"), rs.getString("sha256"), rs.getString("owner_ref"),
                rs.getBoolean("consent_to_index"), IndexPolicy.valueOf(rs.getString("index_policy")),
                instant(rs, "retention_until"), rs.getInt("word_count"), instant(rs, "created_at"));
    }
}
