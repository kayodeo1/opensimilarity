package io.opensimilarity.embedding;

import io.opensimilarity.fingerprint.Tokenizer.Tokens;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a document into passages of whole sentences for embedding.
 *
 * <p>Passages never cross a paragraph break or a heading: mixing a title or a neighbouring paragraph into a passage
 * pulls its vector towards the document's general topic, which makes unrelated writing on the same subject look
 * like a paraphrase. Within a paragraph, consecutive passages share a sentence, so a paraphrase need not line up with
 * the source's chunk boundaries.
 */
public final class Chunker {

    static final int MIN_WORDS = 30;
    /** Sentences longer than this (often extraction artefacts) are cut into pieces. */
    static final int MAX_SENTENCE_WORDS = 60;
    /** Below this a passage has too little content for a meaningful comparison. */
    static final int MIN_CHUNK_WORDS = 12;
    /** A line this short with no closing punctuation is taken to be a heading. */
    private static final int MAX_HEADING_WORDS = 10;

    private static final Pattern LINE = Pattern.compile("[^\\n]*(?:\\n|$)");

    private Chunker() {
    }

    /** A passage as half-open word and character ranges. */
    public record Chunk(int ordinal, int wordStart, int wordEnd, int charStart, int charEnd) {
        public String text(String documentText) {
            return documentText.substring(charStart, charEnd);
        }
    }

    public static List<Chunk> chunk(String text, Tokens tokens) {
        List<Chunk> chunks = new ArrayList<>();
        for (int[] block : blocks(text)) {
            addBlockChunks(text, tokens, block[0], block[1], chunks);
        }
        return chunks;
    }

    private static void addBlockChunks(String text, Tokens tokens, int blockStart, int blockEnd, List<Chunk> chunks) {
        List<int[]> sentences = sentenceWordRanges(text, tokens, blockStart, blockEnd);
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
    }

    /**
     * Character ranges of paragraphs: runs of lines separated by blank lines and headings. A short line only counts
     * as a heading next to a blank line, because every wrapped line of a narrow column is short too.
     */
    private static List<int[]> blocks(String text) {
        List<int[]> lines = new ArrayList<>();
        Matcher line = LINE.matcher(text);
        while (line.find() && line.start() < text.length()) {
            lines.add(new int[] {line.start(), line.end()});
        }
        List<int[]> blocks = new ArrayList<>();
        int blockStart = -1;
        int blockEnd = -1;
        for (int i = 0; i < lines.size(); i++) {
            String content = content(text, lines.get(i));
            boolean besideBlank = i == 0 || i == lines.size() - 1
                    || content(text, lines.get(i - 1)).isEmpty() || content(text, lines.get(i + 1)).isEmpty();
            if (content.isEmpty() || (besideBlank && isHeading(content))) {
                if (blockStart >= 0) {
                    blocks.add(new int[] {blockStart, blockEnd});
                    blockStart = -1;
                }
            } else {
                if (blockStart < 0) {
                    blockStart = lines.get(i)[0];
                }
                blockEnd = lines.get(i)[1];
            }
        }
        if (blockStart >= 0) {
            blocks.add(new int[] {blockStart, blockEnd});
        }
        return blocks;
    }

    private static String content(String text, int[] line) {
        return text.substring(line[0], line[1]).strip();
    }

    private static boolean isHeading(String line) {
        char lastChar = line.charAt(line.length() - 1);
        boolean endsSentence = lastChar == '.' || lastChar == '?' || lastChar == '!' || lastChar == ':'
                || lastChar == ';' || lastChar == ',';
        return !endsSentence && line.split("\\s+").length <= MAX_HEADING_WORDS;
    }

    /** Non-empty sentences of one paragraph, as word ranges. */
    private static List<int[]> sentenceWordRanges(String text, Tokens tokens, int blockStart, int blockEnd) {
        List<int[]> ranges = new ArrayList<>();
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(text.substring(blockStart, blockEnd));
        int word = firstWordAtOrAfter(tokens, blockStart);
        for (int end = sentences.next(); end != BreakIterator.DONE; end = sentences.next()) {
            int from = word;
            while (word < tokens.size() && tokens.starts()[word] < blockStart + end) {
                word++;
            }
            for (int piece = from; piece < word; piece += MAX_SENTENCE_WORDS) {
                ranges.add(new int[] {piece, Math.min(word, piece + MAX_SENTENCE_WORDS)});
            }
        }
        return ranges;
    }

    private static int firstWordAtOrAfter(Tokens tokens, int charOffset) {
        int index = Arrays.binarySearch(tokens.starts(), charOffset);
        return index >= 0 ? index : -index - 1;
    }
}
