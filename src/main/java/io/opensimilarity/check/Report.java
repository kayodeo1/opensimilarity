package io.opensimilarity.check;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The stored and returned result of a check. */
public record Report(
        UUID checkId,
        UUID documentId,
        String documentTitle,
        Instant createdAt,
        String notice,
        Parameters parameters,
        Summary summary,
        Corpus corpus,
        Filters filters,
        Embedding embedding,
        List<Source> sources,
        List<Match> matches,
        int excludedMatchesTotal,
        List<Match> excludedMatches) {

    public static final String NOTICE = "Similarity is text overlap, not a finding of plagiarism. "
            + "Overlap can be legitimate; a person must review each match and its context.";

    public record Parameters(int shingleWords, int winnowWindow, int guaranteedRunWords, int mergeGapWords,
                             int minMatchWords, boolean excludeBibliography, boolean excludeQuotes,
                             boolean useEmbeddings, double minCosine) {
    }

    /**
     * overlapPercent = matchedWords / consideredWords, where matchedWords counts words exactly shared with at
     * least one source and consideredWords = totalWords minus words removed by the filters.
     * paraphraseLeadPercent is separate because it comes from a model's judgement rather than exact overlap.
     */
    public record Summary(int totalWords, int excludedBibliographyWords, int excludedQuotedWords,
                          int consideredWords, int matchedWords, double overlapPercent,
                          int paraphraseLeadWords, double paraphraseLeadPercent, int matchCount, int sourceCount) {
    }

    /** How the set of compared documents was narrowed, so an empty result can be told apart from an empty corpus. */
    public record Corpus(int eligibleDocuments, int documentsSharingFingerprints, int documentsPassingMinHash,
                         int documentsAligned, int documentsDroppedByCap) {
    }

    public record Filters(boolean bibliographyDetected, Integer bibliographyCharStart, int quotedRegions) {
    }

    public record Embedding(String status, String model) {
    }

    public record Source(UUID documentId, String title, int matchedWords, double percentOfConsidered,
                         double estimatedJaccard, int sharedFingerprints) {
    }

    /** Offsets are UTF-16 code units into the document's extracted text; ranges are half-open. */
    public record Span(int charStart, int charEnd, int wordStart, int wordEnd, String text) {
    }

    /**
     * @param method FINGERPRINT (words are exactly shared) or EMBEDDING (similar meaning, for human review)
     * @param matchedWords words that count towards the percentages; for an excluded match, the exact words it held
     * @param confidence 1.0 for an exact fingerprint match; cosine similarity for an embedding match
     * @param excludedBecause null for reported matches; otherwise the filter that removed the match
     */
    public record Match(String method, UUID sourceDocumentId, String sourceTitle, int matchedWords, double confidence,
                        Span query, Span source, String excludedBecause) {
    }
}
