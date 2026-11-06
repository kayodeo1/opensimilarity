package io.opensimilarity.embedding;

import java.util.List;

/** Turns passages into vectors. All vectors in the index must come from the same model. */
public interface Embedder {

    /** Must match the dimension of the {@code chunks.embedding} column. */
    int DIMENSION = 384;

    String modelId();

    List<float[]> embed(List<String> passages);
}
