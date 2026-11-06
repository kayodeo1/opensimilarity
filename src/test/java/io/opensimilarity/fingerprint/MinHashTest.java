package io.opensimilarity.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;
import org.junit.jupiter.api.Test;

class MinHashTest {

    @Test
    void estimatesJaccardWithinSamplingError() {
        Random random = new Random(1);
        long[] common = random.longs(5000).toArray();
        long[] onlyA = random.longs(2500).toArray();
        long[] onlyB = random.longs(2500).toArray();
        // True Jaccard = 5000 / 10000 = 0.5; the standard error at 128 slots is about 0.044.
        double estimate = MinHash.estimateJaccard(MinHash.sketch(concat(common, onlyA)),
                MinHash.sketch(concat(common, onlyB)));
        assertThat(estimate).isCloseTo(0.5, within(0.15));
    }

    @Test
    void identicalSetsEstimateOneAndEmptySetsResembleNothing() {
        long[] hashes = new Random(2).longs(100).toArray();
        assertThat(MinHash.estimateJaccard(MinHash.sketch(hashes), MinHash.sketch(hashes))).isEqualTo(1.0);
        long[] empty = MinHash.sketch(new long[0]);
        assertThat(MinHash.estimateJaccard(empty, empty)).isZero();
    }

    @Test
    void roundTripsThroughBytes() {
        long[] sketch = MinHash.sketch(new Random(3).longs(50).toArray());
        assertThat(MinHash.fromBytes(MinHash.toBytes(sketch))).isEqualTo(sketch);
    }

    private static long[] concat(long[] a, long[] b) {
        long[] all = new long[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
