package io.opensimilarity.check;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        Map<String, Group> matchGroups,
        Flags flags,
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
                             int minMatchWords, double minSourcePercent, boolean excludeBibliography,
                             boolean excludeQuotes, boolean excludeCited, boolean excludeTemplate,
                             List<UUID> excludeSourceIds, boolean useEmbeddings, double minCosine) {
    }

    /**
     * overlapPercent = matchedWords / consideredWords, where matchedWords counts words exactly shared with at
     * least one source and consideredWords = totalWords minus words removed by the filters.
     * overlapPercentOfAllWords divides by totalWords instead, which is how Turnitin's guides describe its score.
     * paraphraseLeadPercent is separate because it comes from a model's judgement rather than exact overlap.
     */
    public record Summary(int totalWords, int excludedBibliographyWords, int excludedQuotedWords,
                          int excludedTemplateWords, int consideredWords, int matchedWords, double overlapPercent,
                          double overlapPercentOfAllWords, int paraphraseLeadWords, double paraphraseLeadPercent,
                          int matchCount, int sourceCount) {
    }

    /** Exact-match words in one match group, as a share of consideredWords. */
    public record Group(int matches, int words, double percent) {
    }

    /**
     * Integrity flags. Turnitin's guides describe "replaced characters" (letters swapped for look-alikes from another
     * alphabet) and "hidden text" (text coloured like the background). Invisible characters are zero-width or other
     * format characters inside words. A flag is a reason to look, not evidence of intent.
     */
    public record Flags(int replacedCharacters, List<Integer> replacedCharacterOffsets, int invisibleCharacters,
                        List<Integer> invisibleCharacterOffsets, String hiddenTextStatus, int hiddenTextWords,
                        String hiddenTextSample) {
    }

    /** How the set of compared documents was narrowed, so an empty result can be told apart from an empty corpus. */
    public record Corpus(int eligibleDocuments, int documentsSharingFingerprints, int documentsPassingMinHash,
                         int documentsAligned, int documentsDroppedByCap) {
    }

    public record Filters(boolean bibliographyDetected, Integer bibliographyCharStart, int quotedRegions,
                          int citationsDetected, boolean templatePresent) {
    }

    public record Embedding(String status, String model) {
    }

    /** @param rank 1 for the source with the most matched words, as in a match overview */
    public record Source(int rank, UUID documentId, String title, int matchedWords, double percentOfConsidered,
                         double estimatedJaccard, int sharedFingerprints) {
    }

    /** Offsets are UTF-16 code units into the document's extracted text; ranges are half-open. */
    public record Span(int charStart, int charEnd, int wordStart, int wordEnd, String text) {
    }

    /**
     * @param method FINGERPRINT (words are exactly shared) or EMBEDDING (similar meaning, for human review)
     * @param matchGroup NOT_CITED_OR_QUOTED, MISSING_QUOTATIONS, MISSING_CITATION or CITED_AND_QUOTED
     * @param citation the in-text citation taken to attribute this match, or null
     * @param matchedWords words that count towards the percentages; for an excluded match, the exact words it held
     * @param confidence 1.0 for an exact fingerprint match; cosine similarity for an embedding match
     * @param excludedBecause null for reported matches; otherwise the filter that removed the match
     */
    public record Match(String method, String matchGroup, String citation, UUID sourceDocumentId, String sourceTitle,
                        Integer sourceRank, int matchedWords, double confidence, Span query, Span source,
                        String excludedBecause) {

        Match withExclusion(String reason, int exactWords) {
            return new Match(method, matchGroup, citation, sourceDocumentId, sourceTitle, null, exactWords,
                    confidence, query, source, reason);
        }

        Match withRank(Integer rank) {
            return new Match(method, matchGroup, citation, sourceDocumentId, sourceTitle, rank, matchedWords,
                    confidence, query, source, excludedBecause);
        }
    }
}
