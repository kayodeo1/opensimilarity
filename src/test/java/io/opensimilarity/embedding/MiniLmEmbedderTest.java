package io.opensimilarity.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the real bundled model. */
class MiniLmEmbedderTest {

    private final MiniLmEmbedder embedder = new MiniLmEmbedder();

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }

    @Test
    void paraphraseScoresHigherThanUnrelatedTextAndDimensionMatchesSchema() {
        List<float[]> vectors = embedder.embed(List.of(
                "The roofing sheets on older blocks are badly corroded, which raises the iron content of collected water.",
                "The metal roofs on the older halls are severely rusted, and this increases the amount of iron in captured water.",
                "Children who struggle with fractions on the chalkboard often handle them easily at a market stall."));
        assertThat(vectors.get(0)).hasSize(Embedder.DIMENSION);
        assertThat(cosine(vectors.get(0), vectors.get(1))).isGreaterThan(cosine(vectors.get(0), vectors.get(2)) + 0.3);
    }

    /**
     * Stored vectors are compared with vectors computed later, so a model must give the same answer every time.
     * The quantized all-MiniLM-L6-v2 artifact failed this on an AVX-VNNI Xeon, with self-similarity as low as 0.45.
     */
    @Test
    void sameTextAlwaysGetsTheSameVectorAloneOrInABatch() {
        String passage = "The roofing sheets on older blocks are badly corroded, which raises the iron content.";
        String other = "Children who struggle with fractions often handle them easily at a market stall.";
        float[] reference = embedder.embed(List.of(passage)).get(0);
        for (int i = 0; i < 25; i++) {
            List<float[]> vectors = embedder.embed(List.of(passage, other, passage, other));
            assertThat(cosine(reference, vectors.get(0))).isGreaterThan(0.9999);
            assertThat(cosine(reference, vectors.get(2))).isGreaterThan(0.9999);
            assertThat(cosine(reference, embedder.embed(List.of(passage)).get(0))).isGreaterThan(0.9999);
        }
    }

    @Test
    void emptyInputNeedsNoModelCall() {
        assertThat(embedder.embed(List.of())).isEmpty();
    }
}
