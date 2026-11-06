package io.opensimilarity.fingerprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Word k-gram shingling and winnowing (Schleimer, Wilkerson, Aiken, SIGMOD 2003).
 *
 * <p>Winnowing keeps the minimum hash of every window of {@link #WINDOW} consecutive shingle hashes.
 * Any two documents sharing a run of at least {@link #GUARANTEED_RUN_WORDS} words therefore share at
 * least one stored fingerprint.
 *
 * <p>K and W are fixed because the stored postings depend on them: changing either requires
 * re-indexing every document.
 */
public final class Fingerprinter {

    public static final int K = 5;
    public static final int WINDOW = 4;
    public static final int GUARANTEED_RUN_WORDS = WINDOW + K - 1;

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Fingerprinter() {
    }

    /** A selected shingle hash and the index of the shingle's first word. */
    public record Fingerprint(long hash, int pos) {
    }

    /** Hash of every k-word shingle; element i covers words i to i+K-1. Empty if there are too few words. */
    public static long[] shingleHashes(String[] words) {
        int count = Math.max(0, words.length - K + 1);
        long[] hashes = new long[count];
        for (int i = 0; i < count; i++) {
            long h = FNV_OFFSET;
            for (int j = i; j < i + K; j++) {
                String word = words[j];
                for (int c = 0; c < word.length(); c++) {
                    h = (h ^ word.charAt(c)) * FNV_PRIME;
                }
                h = (h ^ ' ') * FNV_PRIME;
            }
            hashes[i] = mix(h);
        }
        return hashes;
    }

    /** Robust winnowing: the rightmost minimum of each window, recorded once per position. */
    public static List<Fingerprint> winnow(long[] hashes) {
        List<Fingerprint> selected = new ArrayList<>();
        if (hashes.length == 0) {
            return selected;
        }
        int window = Math.min(WINDOW, hashes.length);
        int last = -1;
        for (int start = 0; start + window <= hashes.length; start++) {
            int min = start;
            for (int i = start + 1; i < start + window; i++) {
                if (hashes[i] <= hashes[min]) {
                    min = i;
                }
            }
            if (min != last) {
                selected.add(new Fingerprint(hashes[min], min));
                last = min;
            }
        }
        return selected;
    }

    /** SplitMix64 finalizer. FNV alone distributes too poorly for minimum-based selection. */
    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
