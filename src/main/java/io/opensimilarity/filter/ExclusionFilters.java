package io.opensimilarity.filter;

import io.opensimilarity.fingerprint.Tokenizer.Tokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Heuristics that locate text a reviewer usually does not want counted: reference lists and quotations. */
public final class ExclusionFilters {

    /** Half-open character range. */
    public record Region(int start, int end) {
    }

    private static final Pattern BIBLIOGRAPHY_HEADING = Pattern.compile(
            "^[ \\t]*(?:(?:\\d+(?:\\.\\d+)*\\.?|[ivxlc]+\\.)[ \\t]+)?"
                    + "(?:references|bibliography|works cited|literature cited|reference list|list of references)"
                    + "[ \\t]*:?[ \\t]*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern APPENDIX_HEADING = Pattern.compile(
            "^[ \\t]*appendix(?:[ \\t]+\\S+)?[ \\t]*:?.*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /** A "References" heading this early is a section in the body, or a contents entry, not the list itself. */
    private static final double BIBLIOGRAPHY_MIN_POSITION = 0.30;

    /** Longer "quotations" are not treated as quotes, so wrapping a whole section in quote marks is not an exclusion. */
    private static final int MAX_QUOTE_CHARS = 1500;

    private ExclusionFilters() {
    }

    /** The reference list: from the last qualifying heading to the next appendix heading or the end of the text. */
    public static Optional<Region> bibliography(String text) {
        int start = -1;
        Matcher heading = BIBLIOGRAPHY_HEADING.matcher(text);
        while (heading.find()) {
            if (heading.start() >= text.length() * BIBLIOGRAPHY_MIN_POSITION) {
                start = heading.start();
            }
        }
        if (start < 0) {
            return Optional.empty();
        }
        Matcher appendix = APPENDIX_HEADING.matcher(text);
        int end = appendix.find(start) ? appendix.start() : text.length();
        return Optional.of(new Region(start, end));
    }

    /** Regions enclosed in double quotation marks that close within the same paragraph. */
    public static List<Region> quotes(String text) {
        List<Region> regions = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char open = text.charAt(i);
            if (open == '"' || open == '“') {
                int close = findClosingQuote(text, i, open == '"' ? '"' : '”');
                if (close >= 0) {
                    regions.add(new Region(i, close + 1));
                    i = close;
                }
            }
            i++;
        }
        return regions;
    }

    private static int findClosingQuote(String text, int open, char closing) {
        int limit = Math.min(text.length(), open + MAX_QUOTE_CHARS);
        int paragraphEnd = text.indexOf("\n\n", open);
        if (paragraphEnd >= 0) {
            limit = Math.min(limit, paragraphEnd);
        }
        for (int i = open + 1; i < limit; i++) {
            if (text.charAt(i) == closing) {
                return i;
            }
        }
        return -1;
    }

    /** Marks each word that starts inside one of the regions. */
    public static boolean[] mask(Tokens tokens, List<Region> regions) {
        boolean[] mask = new boolean[tokens.size()];
        for (Region region : regions) {
            for (int t = 0; t < tokens.size(); t++) {
                if (tokens.starts()[t] >= region.start() && tokens.starts()[t] < region.end()) {
                    mask[t] = true;
                }
            }
        }
        return mask;
    }
}
