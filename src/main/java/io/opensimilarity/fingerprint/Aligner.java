package io.opensimilarity.fingerprint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns "these two documents share fingerprints" into exact word-level spans.
 *
 * <p>Fingerprints only sample a document, so the aligner compares every shingle: it greedily takes the
 * longest exact run at each query position, then merges runs separated by a few changed words so a
 * lightly edited passage is reported as one match. Word comparison makes the spans immune to hash
 * collisions.
 */
public final class Aligner {

    /** Runs are merged across at most this many unmatched words on each side. */
    public static final int MERGE_GAP_WORDS = 4;

    /** Bounds the work on highly repetitive text. */
    private static final int MAX_POSITIONS_PER_HASH = 64;

    private Aligner() {
    }

    /** An exact run of words, as half-open word ranges in the query and the source. */
    public record Run(int queryStart, int queryEnd, int sourceStart, int sourceEnd) {
    }

    /** One or more runs reported as a single match. Words between runs are not counted as matching. */
    public record Match(int queryStart, int queryEnd, int sourceStart, int sourceEnd, List<Run> runs) {
    }

    public static List<Match> align(String[] query, String[] source) {
        return merge(exactRuns(query, source));
    }

    static List<Run> exactRuns(String[] query, String[] source) {
        Map<Long, List<Integer>> positions = new HashMap<>();
        long[] sourceHashes = Fingerprinter.shingleHashes(source);
        for (int j = 0; j < sourceHashes.length; j++) {
            List<Integer> list = positions.computeIfAbsent(sourceHashes[j], h -> new ArrayList<>(1));
            if (list.size() < MAX_POSITIONS_PER_HASH) {
                list.add(j);
            }
        }
        List<Run> runs = new ArrayList<>();
        long[] queryHashes = Fingerprinter.shingleHashes(query);
        int i = 0;
        while (i < queryHashes.length) {
            int bestLength = 0;
            int bestSource = -1;
            for (int j : positions.getOrDefault(queryHashes[i], List.of())) {
                int length = commonRunLength(query, i, source, j);
                if (length > bestLength) {
                    bestLength = length;
                    bestSource = j;
                }
            }
            if (bestLength >= Fingerprinter.K) {
                runs.add(new Run(i, i + bestLength, bestSource, bestSource + bestLength));
                i += bestLength;
            } else {
                i++;
            }
        }
        return runs;
    }

    private static int commonRunLength(String[] a, int i, String[] b, int j) {
        int length = 0;
        while (i + length < a.length && j + length < b.length && a[i + length].equals(b[j + length])) {
            length++;
        }
        return length;
    }

    static List<Match> merge(List<Run> runs) {
        List<Match> matches = new ArrayList<>();
        List<Run> current = new ArrayList<>();
        for (Run run : runs) {
            if (!current.isEmpty() && !continues(current.get(current.size() - 1), run)) {
                matches.add(toMatch(current));
                current = new ArrayList<>();
            }
            current.add(run);
        }
        if (!current.isEmpty()) {
            matches.add(toMatch(current));
        }
        return matches;
    }

    private static boolean continues(Run previous, Run next) {
        int queryGap = next.queryStart() - previous.queryEnd();
        int sourceGap = next.sourceStart() - previous.sourceEnd();
        return queryGap <= MERGE_GAP_WORDS && sourceGap >= 0 && sourceGap <= MERGE_GAP_WORDS;
    }

    private static Match toMatch(List<Run> runs) {
        Run first = runs.get(0);
        Run last = runs.get(runs.size() - 1);
        return new Match(first.queryStart(), last.queryEnd(), first.sourceStart(), last.sourceEnd(), List.copyOf(runs));
    }
}
