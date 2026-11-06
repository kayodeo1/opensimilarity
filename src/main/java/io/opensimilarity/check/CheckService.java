package io.opensimilarity.check;

import io.opensimilarity.check.Report.Match;
import io.opensimilarity.check.Report.Span;
import io.opensimilarity.embedding.Chunker;
import io.opensimilarity.embedding.Chunker.Chunk;
import io.opensimilarity.embedding.Embedder;
import io.opensimilarity.filter.ExclusionFilters;
import io.opensimilarity.filter.ExclusionFilters.Region;
import io.opensimilarity.fingerprint.Aligner;
import io.opensimilarity.fingerprint.Fingerprinter;
import io.opensimilarity.fingerprint.Fingerprinter.Fingerprint;
import io.opensimilarity.fingerprint.MinHash;
import io.opensimilarity.fingerprint.Tokenizer;
import io.opensimilarity.fingerprint.Tokenizer.Tokens;
import io.opensimilarity.store.Store;
import io.opensimilarity.store.Store.CollectionRow;
import io.opensimilarity.store.Store.DocumentRow;
import io.opensimilarity.store.Store.NearestChunk;
import io.opensimilarity.store.Store.QueryScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a check: finds candidate sources, aligns exact word runs, applies filters, adds embedding leads for
 * text the fingerprints did not explain, then stores the report.
 */
@Service
public class CheckService {

    static final int DEFAULT_MIN_MATCH_WORDS = 12;
    /**
     * Sits between clean synonym rewrites (0.79 to 0.86) and original writing on the same topic (up to 0.66) in the
     * small synthetic set under samples/. Heavily restructured paraphrases score 0.63 to 0.67 and are missed.
     */
    static final double DEFAULT_MIN_COSINE = 0.72;
    static final double MINHASH_THRESHOLD = 0.02;
    static final int MAX_ALIGNED_DOCUMENTS = 50;
    static final int MAX_EXCLUDED_IN_REPORT = 100;
    /** A match is attributed to a filter when at least this share of its words falls inside the filtered region. */
    static final double FILTER_MAJORITY = 0.5;
    static final int NEIGHBOURS_PER_CHUNK = 5;
    /** Consecutive query chunks are one embedding match if their source chunks are this close together. */
    static final int MAX_SOURCE_ORDINAL_GAP = 3;

    /**
     * Below this length a shared run can hold a single fingerprint. From here on it spans two disjoint winnowing
     * windows, so it is guaranteed to share at least two (unless the text repeats itself).
     */
    private static final int WORDS_GUARANTEEING_TWO_FINGERPRINTS =
            Fingerprinter.K + 2 * Fingerprinter.WINDOW - 1;

    private final Store store;
    private final Optional<Embedder> embedder;
    private final JsonMapper json;

    public CheckService(Store store, Optional<Embedder> embedder, JsonMapper json) {
        this.store = store;
        this.embedder = embedder;
        this.json = json;
    }

    /** All fields optional; null means the default. */
    public record CheckRequest(Integer minMatchWords, Boolean excludeBibliography, Boolean excludeQuotes,
                               Boolean useEmbeddings, Double minCosine) {
    }

    /** The result of running a check: its id and stored JSON. */
    public record Stored(UUID checkId, String reportJson) {
    }

    public Stored run(DocumentRow doc, CollectionRow collection, CheckRequest request, String actor) {
        Report.Parameters params = resolve(request);
        String text = store.findText(doc.id()).orElseThrow();
        Tokens tokens = Tokenizer.tokenize(text);
        QueryScope scope = new QueryScope(doc.id(), doc.collectionId(), collection.department());

        Masks masks = Masks.of(text, tokens, params);
        Candidates candidates = findCandidates(scope, tokens, params);
        Map<UUID, String> sourceTexts = store.findTexts(candidates.ids());
        Map<UUID, String> titles = store.findTitles(candidates.ids());

        BitSet covered = new BitSet(tokens.size());
        List<Match> matches = new ArrayList<>();
        List<Match> excluded = new ArrayList<>();
        Map<UUID, BitSet> coveredBySource = new HashMap<>();

        for (UUID sourceId : candidates.ids()) {
            String sourceText = sourceTexts.get(sourceId);
            Tokens sourceTokens = Tokenizer.tokenize(sourceText);
            boolean[] sourceBibliography = params.excludeBibliography()
                    ? ExclusionFilters.mask(sourceTokens, ExclusionFilters.bibliography(sourceText).stream().toList())
                    : new boolean[sourceTokens.size()];
            for (Aligner.Match aligned : Aligner.align(tokens.words(), sourceTokens.words())) {
                int counted = masks.countUnmasked(aligned);
                String reason = exclusionReason(aligned, masks, sourceBibliography, counted, params);
                int reportedWords = reason == null ? counted : exactWords(aligned);
                Match match = new Match("FINGERPRINT", sourceId, titles.get(sourceId), reportedWords, 1.0,
                        span(text, tokens, aligned.queryStart(), aligned.queryEnd()),
                        span(sourceText, sourceTokens, aligned.sourceStart(), aligned.sourceEnd()), reason);
                if (reason != null) {
                    excluded.add(match);
                    continue;
                }
                matches.add(match);
                BitSet sourceCoverage = coveredBySource.computeIfAbsent(sourceId, id -> new BitSet(tokens.size()));
                for (Aligner.Run run : aligned.runs()) {
                    for (int t = run.queryStart(); t < run.queryEnd(); t++) {
                        if (!masks.any(t)) {
                            covered.set(t);
                            sourceCoverage.set(t);
                        }
                    }
                }
            }
        }

        BitSet paraphrased = new BitSet(tokens.size());
        Report.Embedding embeddingStatus = addEmbeddingMatches(scope, text, tokens, masks, covered, params, matches,
                paraphrased);

        Report report = buildReport(doc, params, tokens, masks, candidates, covered, paraphrased, coveredBySource,
                titles, matches, excluded, embeddingStatus);
        String reportJson = json.writeValueAsString(report);
        store.saveCheck(report.checkId(), doc.id(), report.summary().overlapPercent(), reportJson, actor);
        store.audit(actor, "CHECK_RUN", "check", report.checkId());
        return new Stored(report.checkId(), store.findReport(report.checkId()).orElseThrow());
    }

    private Report.Parameters resolve(CheckRequest request) {
        CheckRequest r = request != null ? request : new CheckRequest(null, null, null, null, null);
        int minMatchWords = r.minMatchWords() != null ? r.minMatchWords() : DEFAULT_MIN_MATCH_WORDS;
        if (minMatchWords < Fingerprinter.K) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "minMatchWords must be at least " + Fingerprinter.K);
        }
        double minCosine = r.minCosine() != null ? r.minCosine() : DEFAULT_MIN_COSINE;
        if (minCosine <= 0 || minCosine > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minCosine must be in (0, 1]");
        }
        return new Report.Parameters(Fingerprinter.K, Fingerprinter.WINDOW, Fingerprinter.GUARANTEED_RUN_WORDS,
                Aligner.MERGE_GAP_WORDS, minMatchWords,
                r.excludeBibliography() == null || r.excludeBibliography(),
                r.excludeQuotes() == null || r.excludeQuotes(),
                r.useEmbeddings() == null || r.useEmbeddings(),
                minCosine);
    }

    // ---------------------------------------------------------------- candidates

    private record Candidates(List<UUID> ids, Map<UUID, Integer> shared, Map<UUID, Double> jaccard, int eligible,
                              int passingMinHash, int droppedByCap) {
    }

    /**
     * MinHash cannot be the only gate: a copied paragraph in a long document gives a Jaccard near zero, so a pure
     * resemblance filter would lose exactly the case that matters. A document is therefore aligned if it shares
     * enough fingerprints or if its MinHash estimate marks it as a near duplicate. Near duplicates rank first, so
     * they survive the cap on documents aligned per check.
     */
    private Candidates findCandidates(QueryScope scope, Tokens tokens, Report.Parameters params) {
        long[] shingles = Fingerprinter.shingleHashes(tokens.words());
        Set<Long> hashes = Fingerprinter.winnow(shingles).stream().map(Fingerprint::hash).collect(Collectors.toSet());
        Map<UUID, Integer> shared = store.sharedFingerprintCounts(scope, hashes);

        long[] querySketch = MinHash.sketch(shingles);
        Map<UUID, Double> jaccard = new HashMap<>();
        store.eligibleSketches(scope).forEach((id, bytes) ->
                jaccard.put(id, MinHash.estimateJaccard(querySketch, MinHash.fromBytes(bytes))));

        int minShared = params.minMatchWords() >= WORDS_GUARANTEEING_TWO_FINGERPRINTS ? 2 : 1;
        int passingMinHash = (int) jaccard.values().stream().filter(j -> j >= MINHASH_THRESHOLD).count();
        List<UUID> ranked = jaccard.keySet().stream()
                .filter(id -> jaccard.get(id) >= MINHASH_THRESHOLD || shared.getOrDefault(id, 0) >= minShared)
                .sorted(Comparator.<UUID>comparingDouble(id -> -jaccard.get(id))
                        .thenComparingInt(id -> -shared.getOrDefault(id, 0)))
                .toList();
        List<UUID> kept = ranked.stream().limit(MAX_ALIGNED_DOCUMENTS).toList();
        return new Candidates(kept, shared, jaccard, store.countEligibleSources(scope), passingMinHash,
                ranked.size() - kept.size());
    }

    // ---------------------------------------------------------------- filters

    /** Per-word flags for the query document's filtered text. */
    private record Masks(boolean[] bibliography, boolean[] quotes, Report.Filters filters) {

        static Masks of(String text, Tokens tokens, Report.Parameters params) {
            Optional<Region> bibliography = ExclusionFilters.bibliography(text);
            List<Region> quotes = ExclusionFilters.quotes(text);
            return new Masks(
                    params.excludeBibliography()
                            ? ExclusionFilters.mask(tokens, bibliography.stream().toList())
                            : new boolean[tokens.size()],
                    params.excludeQuotes() ? ExclusionFilters.mask(tokens, quotes) : new boolean[tokens.size()],
                    new Report.Filters(bibliography.isPresent(), bibliography.map(Region::start).orElse(null),
                            quotes.size()));
        }

        boolean any(int word) {
            return bibliography[word] || quotes[word];
        }

        int countUnmasked(Aligner.Match match) {
            int count = 0;
            for (Aligner.Run run : match.runs()) {
                for (int t = run.queryStart(); t < run.queryEnd(); t++) {
                    if (!any(t)) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    private static double share(boolean[] mask, int start, int end) {
        int masked = 0;
        for (int t = start; t < end; t++) {
            if (mask[t]) {
                masked++;
            }
        }
        return end > start ? (double) masked / (end - start) : 0.0;
    }

    private static int exactWords(Aligner.Match match) {
        return match.runs().stream().mapToInt(run -> run.queryEnd() - run.queryStart()).sum();
    }

    private static String exclusionReason(Aligner.Match aligned, Masks masks, boolean[] sourceBibliography,
                                          int countedWords, Report.Parameters params) {
        if (share(masks.bibliography(), aligned.queryStart(), aligned.queryEnd()) >= FILTER_MAJORITY) {
            return "BIBLIOGRAPHY";
        }
        if (share(sourceBibliography, aligned.sourceStart(), aligned.sourceEnd()) >= FILTER_MAJORITY) {
            return "SOURCE_BIBLIOGRAPHY";
        }
        if (share(masks.quotes(), aligned.queryStart(), aligned.queryEnd()) >= FILTER_MAJORITY) {
            return "QUOTED";
        }
        return countedWords < params.minMatchWords() ? "SHORT_MATCH" : null;
    }

    // ---------------------------------------------------------------- embeddings

    private Report.Embedding addEmbeddingMatches(QueryScope scope, String text, Tokens tokens, Masks masks,
                                                 BitSet covered, Report.Parameters params, List<Match> matches,
                                                 BitSet paraphrased) {
        if (!params.useEmbeddings()) {
            return new Report.Embedding("NOT_REQUESTED", null);
        }
        if (embedder.isEmpty()) {
            return new Report.Embedding("DISABLED_ON_SERVER", null);
        }
        String model = embedder.get().modelId();

        // Only ask the model about text that is neither filtered out nor already explained by exact matches.
        List<Chunk> chunks = Chunker.chunk(text, tokens).stream()
                .filter(c -> unexplainedShare(c, masks, covered) > FILTER_MAJORITY)
                .toList();
        List<float[]> vectors = embedder.get().embed(chunks.stream().map(c -> c.text(text)).toList());
        List<List<NearestChunk>> neighbours = store.nearestChunks(scope, model, vectors, NEIGHBOURS_PER_CHUNK);

        Map<UUID, List<Hit>> hitsBySource = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            Set<UUID> seen = new HashSet<>();
            for (NearestChunk neighbour : neighbours.get(i)) {
                // Results are nearest first, so the first chunk seen from a document is its best.
                if (neighbour.cosine() >= params.minCosine() && seen.add(neighbour.documentId())) {
                    hitsBySource.computeIfAbsent(neighbour.documentId(), id -> new ArrayList<>())
                            .add(new Hit(chunks.get(i), neighbour));
                }
            }
        }

        Map<UUID, String> sourceTexts = store.findTexts(hitsBySource.keySet());
        Map<UUID, String> titles = store.findTitles(hitsBySource.keySet());
        hitsBySource.forEach((sourceId, hits) -> {
            for (List<Hit> group : groupConsecutive(hits)) {
                Hit first = group.get(0);
                Hit last = group.get(group.size() - 1);
                int wordStart = first.query().wordStart();
                int wordEnd = last.query().wordEnd();
                int counted = 0;
                for (int t = wordStart; t < wordEnd; t++) {
                    if (!masks.any(t) && !covered.get(t)) {
                        counted++;
                    }
                }
                if (counted < params.minMatchWords()) {
                    continue;
                }
                paraphrased.set(wordStart, wordEnd);
                int sourceCharStart = group.stream().mapToInt(h -> h.source().charStart()).min().orElseThrow();
                int sourceCharEnd = group.stream().mapToInt(h -> h.source().charEnd()).max().orElseThrow();
                int sourceWordStart = group.stream().mapToInt(h -> h.source().wordStart()).min().orElseThrow();
                int sourceWordEnd = group.stream().mapToInt(h -> h.source().wordEnd()).max().orElseThrow();
                double confidence = group.stream().mapToDouble(h -> h.source().cosine()).max().orElseThrow();
                matches.add(new Match("EMBEDDING", sourceId, titles.get(sourceId), counted,
                        Math.round(confidence * 1000) / 1000.0,
                        span(text, tokens, wordStart, wordEnd),
                        new Span(sourceCharStart, sourceCharEnd, sourceWordStart, sourceWordEnd,
                                sourceTexts.get(sourceId).substring(sourceCharStart, sourceCharEnd)),
                        null));
            }
        });
        // Words filtered or matched exactly do not count as paraphrase leads, even inside a merged span.
        for (int t = paraphrased.nextSetBit(0); t >= 0; t = paraphrased.nextSetBit(t + 1)) {
            if (masks.any(t) || covered.get(t)) {
                paraphrased.clear(t);
            }
        }
        return new Report.Embedding("RAN", model);
    }

    private record Hit(Chunk query, NearestChunk source) {
    }

    private static double unexplainedShare(Chunk chunk, Masks masks, BitSet covered) {
        int unexplained = 0;
        for (int t = chunk.wordStart(); t < chunk.wordEnd(); t++) {
            if (!masks.any(t) && !covered.get(t)) {
                unexplained++;
            }
        }
        return (double) unexplained / (chunk.wordEnd() - chunk.wordStart());
    }

    /** Hits arrive in query order. Splits them where the query chunks stop touching or the source jumps elsewhere. */
    private static List<List<Hit>> groupConsecutive(List<Hit> hits) {
        List<List<Hit>> groups = new ArrayList<>();
        List<Hit> current = new ArrayList<>();
        for (Hit hit : hits) {
            if (!current.isEmpty()) {
                Hit previous = current.get(current.size() - 1);
                boolean touching = hit.query().wordStart() <= previous.query().wordEnd();
                boolean nearby = Math.abs(hit.source().ordinal() - previous.source().ordinal())
                        <= MAX_SOURCE_ORDINAL_GAP;
                if (!touching || !nearby) {
                    groups.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(hit);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    // ---------------------------------------------------------------- report

    private Report buildReport(DocumentRow doc, Report.Parameters params, Tokens tokens, Masks masks,
                               Candidates candidates, BitSet covered, BitSet paraphrased,
                               Map<UUID, BitSet> coveredBySource, Map<UUID, String> titles, List<Match> matches,
                               List<Match> excluded, Report.Embedding embeddingStatus) {
        int bibliographyWords = 0;
        int quotedOnlyWords = 0;
        for (int t = 0; t < tokens.size(); t++) {
            if (masks.bibliography()[t]) {
                bibliographyWords++;
            } else if (masks.quotes()[t]) {
                quotedOnlyWords++;
            }
        }
        int considered = tokens.size() - bibliographyWords - quotedOnlyWords;

        List<Report.Source> sources = coveredBySource.entrySet().stream()
                .map(e -> new Report.Source(e.getKey(), titles.get(e.getKey()), e.getValue().cardinality(),
                        percent(e.getValue().cardinality(), considered),
                        candidates.jaccard().getOrDefault(e.getKey(), 0.0),
                        candidates.shared().getOrDefault(e.getKey(), 0)))
                .sorted(Comparator.comparingInt(s -> -s.matchedWords()))
                .toList();

        List<Match> ordered = matches.stream().sorted(Comparator.comparingInt(m -> m.query().charStart())).toList();
        List<Match> excludedSample = excluded.stream()
                .sorted(Comparator.comparingInt(m -> -m.matchedWords()))
                .limit(MAX_EXCLUDED_IN_REPORT)
                .toList();

        Report.Summary summary = new Report.Summary(tokens.size(), bibliographyWords, quotedOnlyWords, considered,
                covered.cardinality(), percent(covered.cardinality(), considered),
                paraphrased.cardinality(), percent(paraphrased.cardinality(), considered),
                ordered.size(), sources.size());
        Report.Corpus corpus = new Report.Corpus(candidates.eligible(), candidates.shared().size(),
                candidates.passingMinHash(), candidates.ids().size(), candidates.droppedByCap());
        return new Report(UUID.randomUUID(), doc.id(), doc.title(), Instant.now(), Report.NOTICE, params, summary,
                corpus, masks.filters(), embeddingStatus, sources, ordered, excluded.size(), excludedSample);
    }

    private static double percent(int part, int whole) {
        return whole == 0 ? 0.0 : Math.round(10000.0 * part / whole) / 100.0;
    }

    private static Span span(String text, Tokens tokens, int wordStart, int wordEnd) {
        int charStart = tokens.starts()[wordStart];
        int charEnd = tokens.ends()[wordEnd - 1];
        return new Span(charStart, charEnd, wordStart, wordEnd, text.substring(charStart, charEnd));
    }
}
