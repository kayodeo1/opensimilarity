package io.opensimilarity.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import io.opensimilarity.embedding.Chunker.Chunk;
import io.opensimilarity.fingerprint.Tokenizer;
import io.opensimilarity.fingerprint.Tokenizer.Tokens;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    private static String sentence(int words) {
        return "Start " + "word ".repeat(words - 2) + "end. ";
    }

    @Test
    void coversEveryWordWithOverlappingChunks() {
        String text = sentence(12).repeat(30);
        Tokens tokens = Tokenizer.tokenize(text);
        List<Chunk> chunks = Chunker.chunk(text, tokens);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).wordStart()).isZero();
        assertThat(chunks.get(chunks.size() - 1).wordEnd()).isEqualTo(tokens.size());
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).wordStart()).isLessThan(chunks.get(i - 1).wordEnd());
            assertThat(chunks.get(i).wordStart()).isGreaterThan(chunks.get(i - 1).wordStart());
        }
    }

    @Test
    void cutsVeryLongSentences() {
        String text = "word ".repeat(500);
        List<Chunk> chunks = Chunker.chunk(text, Tokenizer.tokenize(text));
        assertThat(chunks).allSatisfy(c -> assertThat(c.wordEnd() - c.wordStart()).isLessThanOrEqualTo(Chunker.MAX_SENTENCE_WORDS));
    }

    @Test
    void producesNoChunkFromTooLittleText() {
        String text = "Too short to compare.";
        assertThat(Chunker.chunk(text, Tokenizer.tokenize(text))).isEmpty();
        assertThat(Chunker.chunk("", Tokenizer.tokenize(""))).isEmpty();
    }
}
