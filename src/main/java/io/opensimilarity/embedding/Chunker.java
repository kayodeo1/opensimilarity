package io.opensimilarity.embedding;

import io.opensimilarity.fingerprint.Tokenizer.Tokens;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits a document into overlapping passages of whole sentences for embedding. Consecutive passages
 * share a sentence, so a paraphrased passage need not line up with the source's chunk boundaries.
 */
public final class Chunker {

    static final int MIN_WORDS = 30;
    /** Sentences longer than this (often extraction artefacts) are cut into pieces. */
    static final int MAX_SENTENCE_WORDS = 60;
    /** Below this a passage has too little content for a meaningful comparison. */
    static final int MIN_CHUNK_WORDS = 12;

    private Chunker() {
    }

    /** A passage as half-open word and character ranges. */
    public record Chunk(int ordinal, int wordStart, int wordEnd, int charStart, int charEnd) {
        public String text(String documentText) {
            return documentText.substring(charStart, charEnd);
        }
    }

    public static List<Chunk> chunk(String text, Tokens tokens) {
        List<int[]> sentences = sentenceWordRanges(text, tokens);
        List<Chunk> chunks = new ArrayList<>();
        int first = 0;
        while (first < sentences.size()) {
            int last = first;
            while (last < sentences.size() - 1 && sentences.get(last)[1] - sentences.get(first)[0] < MIN_WORDS) {
                last++;
            }
            int wordStart = sentences.get(first)[0];
            int wordEnd = sentences.get(last)[1];
            if (wordEnd - wordStart >= MIN_CHUNK_WORDS) {
                chunks.add(new Chunk(chunks.size(), wordStart, wordEnd,
                        tokens.starts()[wordStart], tokens.ends()[wordEnd - 1]));
            }
            if (last == sentences.size() - 1) {
                break;
            }
            first = last > first ? last : first + 1;
        }
        return chunks;
    }

    /** Non-empty sentences as word ranges. */
    private static List<int[]> sentenceWordRanges(String text, Tokens tokens) {
        List<int[]> ranges = new ArrayList<>();
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(text);
        int word = 0;
        for (int end = sentences.next(); end != BreakIterator.DONE; end = sentences.next()) {
            int from = word;
            while (word < tokens.size() && tokens.starts()[word] < end) {
                word++;
            }
            for (int piece = from; piece < word; piece += MAX_SENTENCE_WORDS) {
                ranges.add(new int[] {piece, Math.min(word, piece + MAX_SENTENCE_WORDS)});
            }
        }
        return ranges;
    }
}
