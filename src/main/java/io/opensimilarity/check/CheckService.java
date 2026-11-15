package io.opensimilarity.check;

import io.opensimilarity.check.Report.Match;
import io.opensimilarity.check.Report.Span;
import io.opensimilarity.embedding.Chunker;
import io.opensimilarity.embedding.Chunker.Chunk;
import io.opensimilarity.embedding.Embedder;
import io.opensimilarity.filter.Citations;
import io.opensimilarity.filter.Citations.MatchGroup;
import io.opensimilarity.filter.ExclusionFilters;
import io.opensimilarity.filter.ExclusionFilters.Region;
import io.opensimilarity.fingerprint.Aligner;
import io.opensimilarity.fingerprint.Fingerprinter;
import io.opensimilarity.fingerprint.Fingerprinter.Fingerprint;
import io.opensimilarity.fingerprint.MinHash;
import io.opensimilarity.fingerprint.Tokenizer;
import io.opensimilarity.fingerprint.Tokenizer.Tokens;
import io.opensimilarity.ingest.HiddenTextDetector.HiddenText;
import io.opensimilarity.ingest.IngestService;
import io.opensimilarity.store.Store;
import io.opensimilarity.store.Store.CollectionRow;
import io.opensimilarity.store.Store.DocumentRow;
import io.opensimilarity.store.Store.NearestChunk;
import io.opensimilarity.store.Store.QueryScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;
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
    static final int MAX_FLAG_OFFSETS = 200;
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
    public record CheckRequest(Integer minMatchWords, Double minSourcePercent, Boolean excludeBibliography,
                               Boolean excludeQuotes, Boolean excludeCited, Boolean excludeTemplate,
                               List<UUID> excludeSourceIds, Boolean useEmbeddings, Double minCosine) {
    }

    /** The result of running a check: its id and stored JSON. */
    public record Stored(UUID checkId, String reportJson) {
    }

    /** A reported exact match and the alignment it came from, kept until source-level filters have run. */
    private record Pending(Match match, Aligner.Match aligned) {
    }

    public Stored run(DocumentRow doc, CollectionRow collection, CheckRequest request, String actor) {
        Report.Parameters params = resolve(request);
        String text = store.findText(doc.id()).orElseThrow();
        Tokens tokens = Tokenizer.tokenize(text);
        QueryScope scope = new QueryScope(doc.id(), doc.collectionId(), collection.department());

        Masks masks = Masks.of(text, tokens, params, store.findTemplateText(collection.id()));
        List<Region> citations = Citations.find(text);
        Candidates candidates = findCandidates(scope, tokens, params);
        Map<UUID, String> sourceTexts = store.findTexts(candidates.ids());
        Map<UUID, String> titles = new HashMap<>(store.findTitles(candidates.ids()));

        List<Pending> included = new ArrayList<>();
        List<Match> excluded = new ArrayList<>();
        for (UUID sourceId : candidates.ids()) {
            String sourceText = sourceTexts.get(sourceId);
            Tokens sourceTokens = Tokenizer.tokenize(sourceText);
            boolean[] sourceBibliography = params.excludeBibliography()
                    ? ExclusionFilters.mask(sourceTokens, ExclusionFilters.bibliography(sourceText).stream().toList())
                    : new boolean[sourceTokens.size()];
            for (Aligner.Match aligned : Aligner.align(tokens.words(), sourceTokens.words())) {
                Span query = span(text, tokens, aligned.queryStart(), aligned.queryEnd());
                Optional<Region> citation = Citations.attributing(text, citations, query.charStart(), query.charEnd());
                boolean quoted = share(masks.quotes(), aligned.queryStart(), aligned.queryEnd()) >= FILTER_MAJORITY;
                int counted = masks.countUnmasked(aligned);
                String reason = exclusionReason(sourceId, aligned, masks, sourceBibliography, quoted,
                        citation.isPresent(), counted, params);
                Match match = new Match("FINGERPRINT", MatchGroup.of(citation.isPresent(), quoted).name(),
                        citation.map(c -> text.substring(c.start(), c.end())).orElse(null), sourceId,
                        titles.get(sourceId), null, reason == null ? counted : exactWords(aligned), 1.0, query,
                        span(sourceText, sourceTokens, aligned.sourceStart(), aligned.sourceEnd()), reason);
                if (reason == null) {
                    included.add(new Pending(match, aligned));
                } else {
                    excluded.add(match);
                }
            }
        }

        int considered = masks.consideredWords();
        included = dropSmallSources(included, excluded, masks, considered, params);

        BitSet covered = new BitSet(tokens.size());
        Map<UUID, BitSet> coveredBySource = new HashMap<>();
        Map<MatchGroup, BitSet> coveredByGroup = new EnumMap<>(MatchGroup.class);
        Map<MatchGroup, Integer> matchesByGroup = new EnumMap<>(MatchGroup.class);
        List<Match> matches = new ArrayList<>();
        for (Pending pending : included) {
            Match match = pending.match();
            matches.add(match);
            MatchGroup group = MatchGroup.valueOf(match.matchGroup());
            matchesByGroup.merge(group, 1, Integer::sum);
            BitSet sourceCoverage = coveredBySource.computeIfAbsent(match.sourceDocumentId(),
                    id -> new BitSet(tokens.size()));
            BitSet groupCoverage = coveredByGroup.computeIfAbsent(group, g -> new BitSet(tokens.size()));
            forEachCountedWord(pending.aligned(), masks, t -> {
                covered.set(t);
                sourceCoverage.set(t);
                groupCoverage.set(t);
            });
        }

        BitSet paraphrased = new BitSet(tokens.size());
        Report.Embedding embeddingStatus = addEmbeddingMatches(scope, text, tokens, masks, covered, citations, params,
                matches, excluded, paraphrased);

        Report report = buildReport(doc, params, tokens, masks, candidates, covered, paraphrased, coveredBySource,
                coveredByGroup, matchesByGroup, citations.size(), titles, matches, excluded, embeddingStatus);
        String reportJson = json.writeValueAsString(report);
        store.saveCheck(report.checkId(), doc.id(), report.summary().overlapPercent(), reportJson, actor);
        store.audit(actor, "CHECK_RUN", "check", report.checkId());
        return new Stored(report.checkId(), store.findReport(report.checkId()).orElseThrow());
    }

    /**
     * Turnitin's "exclude small matches" can be set as a percentage: a source whose matches together are below it is
     * left out. Its matches move to the excluded list with reason SMALL_SOURCE, so the reviewer can still see them.
     */
    private static List<Pending> dropSmallSources(List<Pending> included, List<Match> excluded, Masks masks,
                                                  int considered, Report.Parameters params) {
        if (params.minSourcePercent() <= 0) {
            return included;
        }
        Map<UUID, BitSet> words = new HashMap<>();
        for (Pending pending : included) {
            BitSet bits = words.computeIfAbsent(pending.match().sourceDocumentId(), id -> new BitSet());
            forEachCountedWord(pending.aligned(), masks, bits::set);
        }
        List<Pending> kept = new ArrayList<>();
        for (Pending pending : included) {
            int sourceWords = words.get(pending.match().sourceDocumentId()).cardinality();
            if (percent(sourceWords, considered) < params.minSourcePercent()) {
                excluded.add(pending.match().withExclusion("SMALL_SOURCE", exactWords(pending.aligned())));
            } else {
                kept.add(pending);
            }
        }
        return kept;
    }

    private static void forEachCountedWord(Aligner.Match aligned, Masks masks, IntConsumer action) {
        for (Aligner.Run run : aligned.runs()) {
            for (int t = run.queryStart(); t < run.queryEnd(); t++) {
                if (!masks.any(t)) {
                    action.accept(t);
                }
            }
        }
    }

    private Report.Parameters resolve(CheckRequest request) {
        CheckRequest r = request != null ? request
                : new CheckRequest(null, null, null, null, null, null, null, null, null);
        int minMatchWords = r.minMatchWords() != null ? r.minMatchWords() : DEFAULT_MIN_MATCH_WORDS;
        if (minMatchWords < Fingerprinter.K) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "minMatchWords must be at least " + Fingerprinter.K);
        }
        double minSourcePercent = r.minSourcePercent() != null ? r.minSourcePercent() : 0.0;
        if (minSourcePercent < 0 || minSourcePercent >= 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minSourcePercent must be in [0, 100)");
        }
        double minCosine = r.minCosine() != null ? r.minCosine() : DEFAULT_MIN_COSINE;
        if (minCosine <= 0 || minCosine > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minCosine must be in (0, 1]");
        }
        return new Report.Parameters(Fingerprinter.K, Fingerprinter.WINDOW, Fingerprinter.GUARANTEED_RUN_WORDS,
                Aligner.MERGE_GAP_WORDS, minMatchWords, minSourcePercent,
                r.excludeBibliography() == null || r.excludeBibliography(),
                r.excludeQuotes() == null || r.excludeQuotes(),
                r.excludeCited() != null && r.excludeCited(),
                r.excludeTemplate() == null || r.excludeTemplate(),
                r.excludeSourceIds() == null ? List.of() : List.copyOf(r.excludeSourceIds()),
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

    /**
     * Per-word flags for the query document. The raw masks are always computed, because match groups need the quotes
     * even when quoted text is not excluded; {@link #any} applies only the exclusions the reviewer asked for.
     */
    private record Masks(boolean[] bibliography, boolean[] quotes, boolean[] template, boolean excludeBibliography,
                         boolean excludeQuotes, boolean excludeTemplate, Optional<Region> bibliographyRegion,
                         int quotedRegions, boolean templatePresent) {

        static Masks of(String text, Tokens tokens, Report.Parameters params, Optional<String> templateText) {
            Optional<Region> bibliography = ExclusionFilters.bibliography(text);
            List<Region> quotes = ExclusionFilters.quotes(text);
            boolean[] template = new boolean[tokens.size()];
            templateText.ifPresent(t -> {
                // A whole aligned match counts, including the few changed words a merged match may contain, so a
                // template line the student edited slightly is still recognised.
                for (Aligner.Match m : Aligner.align(tokens.words(), Tokenizer.tokenize(t).words())) {
                    for (int w = m.queryStart(); w < m.queryEnd(); w++) {
                        template[w] = true;
                    }
                }
            });
            return new Masks(ExclusionFilters.mask(tokens, bibliography.stream().toList()),
                    ExclusionFilters.mask(tokens, quotes), template,
                    params.excludeBibliography(), params.excludeQuotes(), params.excludeTemplate(),
                    bibliography, quotes.size(), templateText.isPresent());
        }

        boolean excludedBibliography(int word) {
            return excludeBibliography && bibliography[word];
        }

        boolean excludedQuote(int word) {
            return excludeQuotes && quotes[word];
        }

        boolean excludedTemplate(int word) {
            return excludeTemplate && template[word];
        }

        boolean any(int word) {
            return excludedBibliography(word) || excludedQuote(word) || excludedTemplate(word);
        }

        int consideredWords() {
            int considered = 0;
            for (int t = 0; t < quotes.length; t++) {
                if (!any(t)) {
                    considered++;
                }
            }
            return considered;
        }

        int countUnmasked(Aligner.Match match) {
            int[] count = {0};
            forEachCountedWord(match, this, t -> count[0]++);
            return count[0];
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

    /** The first applicable reason, in a fixed order, or null if the match is reported. */
    private static String exclusionReason(UUID sourceId, Aligner.Match aligned, Masks masks,
                                          boolean[] sourceBibliography, boolean quoted, boolean cited,
                                          int countedWords, Report.Parameters params) {
        if (params.excludeSourceIds().contains(sourceId)) {
            return "SOURCE_EXCLUDED";
        }
        if (masks.excludeBibliography()
                && share(masks.bibliography(), aligned.queryStart(), aligned.queryEnd()) >= FILTER_MAJORITY) {
            return "BIBLIOGRAPHY";
        }
        if (share(sourceBibliography, aligned.sourceStart(), aligned.sourceEnd()) >= FILTER_MAJORITY) {
            return "SOURCE_BIBLIOGRAPHY";
        }
        if (masks.excludeTemplate()
                && share(masks.template(), aligned.queryStart(), aligned.queryEnd()) >= FILTER_MAJORITY) {
            return "TEMPLATE";
        }
        if (masks.excludeQuotes() && quoted) {
            return "QUOTED";
        }
        if (params.excludeCited() && cited) {
            return "CITED";
        }
        return countedWords < params.minMatchWords() ? "SHORT_MATCH" : null;
    }

    // ---------------------------------------------------------------- embeddings

    private Report.Embedding addEmbeddingMatches(QueryScope scope, String text, Tokens tokens, Masks masks,
                                                 BitSet covered, List<Region> citations, Report.Parameters params,
                                                 List<Match> matches, List<Match> excluded, BitSet paraphrased) {
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
                int sourceCharStart = group.stream().mapToInt(h -> h.source().charStart()).min().orElseThrow();
                int sourceCharEnd = group.stream().mapToInt(h -> h.source().charEnd()).max().orElseThrow();
                int sourceWordStart = group.stream().mapToInt(h -> h.source().wordStart()).min().orElseThrow();
                int sourceWordEnd = group.stream().mapToInt(h -> h.source().wordEnd()).max().orElseThrow();
                double confidence = group.stream().mapToDouble(h -> h.source().cosine()).max().orElseThrow();
                Span query = span(text, tokens, wordStart, wordEnd);
                Optional<Region> citation = Citations.attributing(text, citations, query.charStart(), query.charEnd());
                boolean quoted = share(masks.quotes(), wordStart, wordEnd) >= FILTER_MAJORITY;
                String reason = params.excludeSourceIds().contains(sourceId) ? "SOURCE_EXCLUDED"
                        : params.excludeCited() && citation.isPresent() ? "CITED" : null;
                Match match = new Match("EMBEDDING", MatchGroup.of(citation.isPresent(), quoted).name(),
                        citation.map(c -> text.substring(c.start(), c.end())).orElse(null), sourceId,
                        titles.get(sourceId), null, counted, Math.round(confidence * 1000) / 1000.0, query,
                        new Span(sourceCharStart, sourceCharEnd, sourceWordStart, sourceWordEnd,
                                sourceTexts.get(sourceId).substring(sourceCharStart, sourceCharEnd)),
                        reason);
                if (reason != null) {
                    excluded.add(match);
                    continue;
                }
                paraphrased.set(wordStart, wordEnd);
                matches.add(match);
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
                               Map<UUID, BitSet> coveredBySource, Map<MatchGroup, BitSet> coveredByGroup,
                               Map<MatchGroup, Integer> matchesByGroup, int citationsDetected,
                               Map<UUID, String> titles, List<Match> matches, List<Match> excluded,
                               Report.Embedding embeddingStatus) {
        int bibliographyWords = 0;
        int quotedOnlyWords = 0;
        int templateOnlyWords = 0;
        for (int t = 0; t < tokens.size(); t++) {
            if (masks.excludedBibliography(t)) {
                bibliographyWords++;
            } else if (masks.excludedQuote(t)) {
                quotedOnlyWords++;
            } else if (masks.excludedTemplate(t)) {
                templateOnlyWords++;
            }
        }
        int considered = tokens.size() - bibliographyWords - quotedOnlyWords - templateOnlyWords;

        List<Map.Entry<UUID, BitSet>> bySize = coveredBySource.entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, BitSet>>comparingInt(e -> -e.getValue().cardinality())
                        .thenComparing(e -> e.getKey().toString()))
                .toList();
        Map<UUID, Integer> ranks = new HashMap<>();
        List<Report.Source> sources = new ArrayList<>();
        for (Map.Entry<UUID, BitSet> e : bySize) {
            int rank = sources.size() + 1;
            ranks.put(e.getKey(), rank);
            sources.add(new Report.Source(rank, e.getKey(), titles.get(e.getKey()), e.getValue().cardinality(),
                    percent(e.getValue().cardinality(), considered),
                    candidates.jaccard().getOrDefault(e.getKey(), 0.0),
                    candidates.shared().getOrDefault(e.getKey(), 0)));
        }

        Map<String, Report.Group> groups = new LinkedHashMap<>();
        for (MatchGroup group : MatchGroup.values()) {
            int words = coveredByGroup.getOrDefault(group, new BitSet()).cardinality();
            groups.put(group.name(), new Report.Group(matchesByGroup.getOrDefault(group, 0), words,
                    percent(words, considered)));
        }

        List<Match> ordered = matches.stream()
                .map(m -> m.withRank(ranks.get(m.sourceDocumentId())))
                .sorted(Comparator.comparingInt(m -> m.query().charStart()))
                .toList();
        List<Match> excludedSample = excluded.stream()
                .sorted(Comparator.comparingInt(m -> -m.matchedWords()))
                .limit(MAX_EXCLUDED_IN_REPORT)
                .toList();

        Report.Summary summary = new Report.Summary(tokens.size(), bibliographyWords, quotedOnlyWords,
                templateOnlyWords, considered, covered.cardinality(), percent(covered.cardinality(), considered),
                percent(covered.cardinality(), tokens.size()),
                paraphrased.cardinality(), percent(paraphrased.cardinality(), considered),
                ordered.size(), sources.size());
        Report.Corpus corpus = new Report.Corpus(candidates.eligible(), candidates.shared().size(),
                candidates.passingMinHash(), candidates.ids().size(), candidates.droppedByCap());
        Report.Filters filters = new Report.Filters(masks.bibliographyRegion().isPresent(),
                masks.bibliographyRegion().map(Region::start).orElse(null), masks.quotedRegions(),
                citationsDetected, masks.templatePresent());
        return new Report(UUID.randomUUID(), doc.id(), doc.title(), Instant.now(), Report.NOTICE, params, summary,
                groups, flags(doc, tokens), corpus, filters, embeddingStatus, sources, ordered, excluded.size(),
                excludedSample);
    }

    private Report.Flags flags(DocumentRow doc, Tokens tokens) {
        HiddenText hidden = store.findIntegrity(doc.id())
                .map(j -> json.readValue(j, IngestService.Integrity.class).hiddenText())
                .orElse(new HiddenText("NOT_RECORDED", 0, ""));
        return new Report.Flags(tokens.replaced().length, sample(tokens.replaced()), tokens.invisible().length,
                sample(tokens.invisible()), hidden.status(), hidden.words(), hidden.sample());
    }

    private static List<Integer> sample(int[] offsets) {
        return Arrays.stream(offsets).limit(MAX_FLAG_OFFSETS).boxed().toList();
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
