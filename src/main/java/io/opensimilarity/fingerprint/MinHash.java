package io.opensimilarity.fingerprint;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** MinHash signatures (Broder 1997) over a document's shingle hashes, for estimating Jaccard resemblance. */
public final class MinHash {

    public static final int SIZE = 128;

    private static final long[] MULTIPLIERS = new long[SIZE];
    private static final long[] OFFSETS = new long[SIZE];

    static {
        long seed = 0x5851f42d4c957f2dL;
        for (int i = 0; i < SIZE; i++) {
            seed += 0x9e3779b97f4a7c15L;
            MULTIPLIERS[i] = Fingerprinter.mix(seed) | 1L;
            seed += 0x9e3779b97f4a7c15L;
            OFFSETS[i] = Fingerprinter.mix(seed);
        }
    }

    private MinHash() {
    }

    public static long[] sketch(long[] shingleHashes) {
        long[] signature = new long[SIZE];
        Arrays.fill(signature, Long.MAX_VALUE);
        for (long hash : shingleHashes) {
            for (int i = 0; i < SIZE; i++) {
                long value = Fingerprinter.mix(MULTIPLIERS[i] * hash + OFFSETS[i]);
                if (value < signature[i]) {
                    signature[i] = value;
                }
            }
        }
        return signature;
    }

    /** Estimated Jaccard resemblance. Documents too short to have shingles resemble nothing. */
    public static double estimateJaccard(long[] a, long[] b) {
        int equal = 0;
        for (int i = 0; i < SIZE; i++) {
            if (a[i] == Long.MAX_VALUE || b[i] == Long.MAX_VALUE) {
                return 0.0;
            }
            if (a[i] == b[i]) {
                equal++;
            }
        }
        return (double) equal / SIZE;
    }

    public static byte[] toBytes(long[] signature) {
        ByteBuffer buffer = ByteBuffer.allocate(SIZE * Long.BYTES);
        for (long value : signature) {
            buffer.putLong(value);
        }
        return buffer.array();
    }

    public static long[] fromBytes(byte[] bytes) {
        long[] signature = new long[SIZE];
        ByteBuffer.wrap(bytes).asLongBuffer().get(signature);
        return signature;
    }
}
