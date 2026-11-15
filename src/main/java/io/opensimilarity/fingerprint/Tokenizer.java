package io.opensimilarity.fingerprint;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits text into normalized words, keeping each word's character offsets in the original text so
 * that matches can be reported as spans of the stored document.
 *
 * <p>Words are compared after NFKC normalization and lower-casing. Invisible format characters
 * (soft hyphens, zero-width spaces) are ignored, and a word broken by a hyphen at the end of a line,
 * which is common in text extracted from justified PDFs, is rejoined.
 *
 * <p>Letters from another alphabet that imitate Latin letters (Cyrillic "а" for Latin "a") are folded to the Latin
 * letter when the word is otherwise Latin, or when the document is mostly Latin and every letter of the word is either
 * Latin or such a look-alike. A genuine Greek or Russian word keeps its letters, because it contains letters with no
 * Latin twin. The offsets of folded characters are reported so a reviewer can see them.
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    /**
     * Parallel arrays: word i is {@code words[i]}, found at {@code [starts[i], ends[i])}. {@code replaced} holds the
     * character offsets of look-alike letters that were folded to Latin; {@code invisible} those of ignored invisible
     * characters inside words.
     */
    public record Tokens(String[] words, int[] starts, int[] ends, int[] replaced, int[] invisible) {
        public int size() {
            return words.length;
        }
    }

    public static Tokens tokenize(String text) {
        boolean mostlyLatin = mostlyLatin(text);
        List<String> words = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        List<Integer> replaced = new ArrayList<>();
        List<Integer> invisible = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            int cp = text.codePointAt(i);
            if (!isWordChar(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            int start = i;
            int end = i;
            StringBuilder word = new StringBuilder();
            List<Integer> lookAlikes = new ArrayList<>();
            boolean hasLatin = false;
            boolean otherScript = false;
            while (i < n) {
                int resume = afterLineEndHyphen(text, i);
                if (resume > 0 && word.length() > 0) {
                    i = resume;
                    continue;
                }
                cp = text.codePointAt(i);
                int len = Character.charCount(cp);
                if (isWordChar(cp)) {
                    if (Character.getType(cp) != Character.FORMAT) {
                        word.appendCodePoint(cp);
                        if (Confusables.isConfusable(cp)) {
                            lookAlikes.add(i);
                        } else if (Confusables.isLatinLetter(cp)) {
                            hasLatin = true;
                        } else if (Character.isLetter(cp)) {
                            otherScript = true;
                        }
                    } else if (word.length() > 0) {
                        invisible.add(i);
                    }
                    i += len;
                    end = i;
                } else if (isApostrophe(cp) && word.length() > 0 && i + len < n
                        && isWordChar(text.codePointAt(i + len))) {
                    word.append('\'');
                    i += len;
                } else {
                    break;
                }
            }
            CharSequence chars = word;
            if (!lookAlikes.isEmpty() && !otherScript && (hasLatin || mostlyLatin)) {
                StringBuilder folded = new StringBuilder(word.length());
                word.codePoints().map(Confusables::fold).forEach(folded::appendCodePoint);
                chars = folded;
                replaced.addAll(lookAlikes);
            }
            String normalized = Normalizer.normalize(chars, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                words.add(normalized);
                spans.add(new int[] {start, end});
            }
        }
        int[] starts = new int[spans.size()];
        int[] ends = new int[spans.size()];
        for (int t = 0; t < spans.size(); t++) {
            starts[t] = spans.get(t)[0];
            ends[t] = spans.get(t)[1];
        }
        return new Tokens(words.toArray(String[]::new), starts, ends,
                replaced.stream().mapToInt(Integer::intValue).toArray(),
                invisible.stream().mapToInt(Integer::intValue).toArray());
    }

    /** True when more than half of the letters are Latin, counting look-alikes as foreign. */
    private static boolean mostlyLatin(String text) {
        int latin = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) {
                letters++;
                if (Confusables.isLatinLetter(cp)) {
                    latin++;
                }
            }
            i += Character.charCount(cp);
        }
        return latin * 2 > letters;
    }

    private static boolean isWordChar(int cp) {
        if (Character.isLetterOrDigit(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT;
    }

    private static boolean isApostrophe(int cp) {
        return cp == '\'' || cp == '’';
    }

    /** If position i holds "hyphen, line break, lower-case letter", returns that letter's index; else -1. */
    private static int afterLineEndHyphen(String text, int i) {
        char c = text.charAt(i);
        if (c != '-' && c != '­' && c != '‐') {
            return -1;
        }
        int j = skipBlanks(text, i + 1);
        if (j < text.length() && text.charAt(j) == '\r') {
            j++;
        }
        if (j >= text.length() || text.charAt(j) != '\n') {
            return -1;
        }
        j = skipBlanks(text, j + 1);
        return j < text.length() && Character.isLowerCase(text.codePointAt(j)) ? j : -1;
    }

    private static int skipBlanks(String text, int i) {
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }
}
