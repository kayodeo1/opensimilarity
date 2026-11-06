package io.opensimilarity.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the real bundled model. */
class MiniLmEmbedderTest {

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
        List<float[]> vectors = new MiniLmEmbedder().embed(List.of(
                "The roofing sheets on older blocks are badly corroded, which raises the iron content of collected water.",
                "The metal roofs on the older halls are severely rusted, and this increases the amount of iron in captured water.",
                "Children who struggle with fractions on the chalkboard often handle them easily at a market stall."));
        assertThat(vectors.get(0)).hasSize(Embedder.DIMENSION);
        assertThat(cosine(vectors.get(0), vectors.get(1))).isGreaterThan(cosine(vectors.get(0), vectors.get(2)) + 0.3);
    }

    @Test
    void emptyInputNeedsNoModelCall() {
        assertThat(new MiniLmEmbedder().embed(List.of())).isEmpty();
    }
}
