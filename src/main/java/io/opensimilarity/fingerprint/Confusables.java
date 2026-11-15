package io.opensimilarity.fingerprint;

import java.util.Map;

/**
 * Letters from other alphabets that look like Latin letters. Replacing Latin letters with these is a known way to
 * defeat text matching while leaving the text readable, so the tokenizer folds them back and reports where it did.
 *
 * <p>The list is deliberately short: only letters that are hard to tell apart from a Latin letter in common fonts.
 * It is a subset in the spirit of the Unicode confusables data (UTS #39), not a copy of it.
 */
final class Confusables {

    private static final Map<Integer, Character> TO_LATIN = Map.ofEntries(
            // Cyrillic lower case
            Map.entry(0x0430, 'a'), Map.entry(0x0435, 'e'), Map.entry(0x043E, 'o'), Map.entry(0x0440, 'p'),
            Map.entry(0x0441, 'c'), Map.entry(0x0443, 'y'), Map.entry(0x0445, 'x'), Map.entry(0x0456, 'i'),
            Map.entry(0x0458, 'j'), Map.entry(0x0455, 's'), Map.entry(0x0501, 'd'), Map.entry(0x04BB, 'h'),
            Map.entry(0x04CF, 'l'), Map.entry(0x051B, 'q'), Map.entry(0x051D, 'w'),
            // Cyrillic upper case
            Map.entry(0x0410, 'a'), Map.entry(0x0412, 'b'), Map.entry(0x0415, 'e'), Map.entry(0x041A, 'k'),
            Map.entry(0x041C, 'm'), Map.entry(0x041D, 'h'), Map.entry(0x041E, 'o'), Map.entry(0x0420, 'p'),
            Map.entry(0x0421, 'c'), Map.entry(0x0422, 't'), Map.entry(0x0425, 'x'), Map.entry(0x0423, 'y'),
            Map.entry(0x0406, 'i'), Map.entry(0x0408, 'j'), Map.entry(0x0405, 's'),
            // Greek
            Map.entry(0x03BF, 'o'), Map.entry(0x03BD, 'v'), Map.entry(0x03C1, 'p'), Map.entry(0x03F2, 'c'),
            Map.entry(0x0391, 'a'), Map.entry(0x0392, 'b'), Map.entry(0x0395, 'e'), Map.entry(0x0396, 'z'),
            Map.entry(0x0397, 'h'), Map.entry(0x0399, 'i'), Map.entry(0x039A, 'k'), Map.entry(0x039C, 'm'),
            Map.entry(0x039D, 'n'), Map.entry(0x039F, 'o'), Map.entry(0x03A1, 'p'), Map.entry(0x03A4, 't'),
            Map.entry(0x03A5, 'y'), Map.entry(0x03A7, 'x'),
            // Armenian
            Map.entry(0x0585, 'o'), Map.entry(0x057D, 'u'), Map.entry(0x0570, 'h'), Map.entry(0x0578, 'n'),
            Map.entry(0x0581, 'g'), Map.entry(0x0566, 'q'));

    private Confusables() {
    }

    static boolean isConfusable(int cp) {
        return TO_LATIN.containsKey(cp);
    }

    /** The Latin letter this code point imitates, or the code point itself. */
    static int fold(int cp) {
        Character latin = TO_LATIN.get(cp);
        return latin == null ? cp : latin;
    }

    static boolean isLatinLetter(int cp) {
        return Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
    }
}
