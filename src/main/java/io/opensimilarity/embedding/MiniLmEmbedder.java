package io.opensimilarity.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** all-MiniLM-L6-v2 (Apache-2.0), quantized, running in-process on ONNX Runtime. Mainly English. */
@Component
@ConditionalOnProperty(name = "opensim.embedding.provider", havingValue = "minilm", matchIfMissing = true)
public class MiniLmEmbedder implements Embedder {

    private final AllMiniLmL6V2QuantizedEmbeddingModel model = new AllMiniLmL6V2QuantizedEmbeddingModel();

    @Override
    public String modelId() {
        return "all-MiniLM-L6-v2-q";
    }

    @Override
    public List<float[]> embed(List<String> passages) {
        if (passages.isEmpty()) {
            return List.of();
        }
        List<TextSegment> segments = passages.stream().map(TextSegment::from).toList();
        return model.embedAll(segments).content().stream().map(Embedding::vector).toList();
    }
}
