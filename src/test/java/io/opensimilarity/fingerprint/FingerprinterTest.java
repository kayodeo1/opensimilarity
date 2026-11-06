package io.opensimilarity.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import io.opensimilarity.fingerprint.Fingerprinter.Fingerprint;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FingerprinterTest {

    private static String[] randomWords(Random random, int count) {
        String[] words = new String[count];
        for (int i = 0; i < count; i++) {
            words[i] = "w" + random.nextInt(5000);
        }
        return words;
    }

    private static Set<Long> fingerprintHashes(String[] words) {
        return Fingerprinter.winnow(Fingerprinter.shingleHashes(words)).stream()
                .map(Fingerprint::hash).collect(Collectors.toSet());
    }

    /** The defining property of winnowing: any shared run of length t = w + k - 1 shares a fingerprint. */
    @Test
    void anySharedRunOfGuaranteedLengthSharesAFingerprint() {
        Random random = new Random(42);
        for (int trial = 0; trial < 2000; trial++) {
            String[] run = randomWords(random, Fingerprinter.GUARANTEED_RUN_WORDS);
            String[] a = embed(randomWords(random, 40), run, randomWords(random, 40));
            String[] b = embed(randomWords(random, 25), run, randomWords(random, 60));
            Set<Long> shared = new HashSet<>(fingerprintHashes(a));
            shared.retainAll(fingerprintHashes(b));
            assertThat(shared).as("trial %d", trial).isNotEmpty();
        }
    }

    private static String[] embed(String[] before, String[] run, String[] after) {
        String[] all = Arrays.copyOf(before, before.length + run.length + after.length);
        System.arraycopy(run, 0, all, before.length, run.length);
        System.arraycopy(after, 0, all, before.length + run.length, after.length);
        return all;
    }

    @Test
    void winnowingSelectsFewerHashesThanShingles() {
        String[] words = randomWords(new Random(7), 5000);
        long[] shingles = Fingerprinter.shingleHashes(words);
        int selected = Fingerprinter.winnow(shingles).size();
        // Expected density is 2 / (w + 1) = 0.4 of all shingles.
        assertThat((double) selected / shingles.length).isBetween(0.3, 0.5);
    }

    @Test
    void shortDocumentsStillGetAFingerprintWhenTheyHaveAShingle() {
        assertThat(Fingerprinter.winnow(Fingerprinter.shingleHashes(new String[] {"a", "b", "c", "d", "e"}))).hasSize(1);
        assertThat(Fingerprinter.winnow(Fingerprinter.shingleHashes(new String[] {"a", "b"}))).isEmpty();
    }
}
