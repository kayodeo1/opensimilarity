package io.opensimilarity.filter;

import io.opensimilarity.filter.ExclusionFilters.Region;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds in-text citations and decides which match group a match belongs to.
 *
 * <p>Turnitin's public guides describe four match groups based on whether an in-text citation and quotation marks were
 * detected around a match. They do not say how either is detected, so these rules are this project's own: common
 * author-year forms (APA, Harvard, MLA with a year) and bracketed numeric references (IEEE, Vancouver). Footnote
 * markers, superscript numbers and author-page MLA citations without a year are not recognised.
 */
public final class Citations {

    /** How far before a match a narrative citation ("Okafor (2019) found that ...") may start. */
    static final int LOOK_BEHIND_CHARS = 200;
    /** How far after a match a parenthetical or numeric citation may start. */
    static final int LOOK_AHEAD_CHARS = 200;

    private static final String YEAR = "(?:1[5-9]\\d{2}|20\\d{2}|n\\.\\s?d\\.)[a-z]?";
    private static final String NAME = "\\p{Lu}[\\p{L}'’\\-]+";

    /** (Okafor, 2019), (Okafor & Bello, 2019, p. 4), (see Okafor et al. 2019; Bello 2020). */
    private static final Pattern PARENTHETICAL = Pattern.compile(
            "\\((?:[^()\\n]{0,40}?\\s)?" + NAME + "[^()\\n]{0,160}?\\b" + YEAR + "(?:[^()\\n]{0,160})?\\)");

    /** Okafor (2019), Okafor and Bello (2019, p. 12), Okafor et al. (2019). */
    private static final Pattern NARRATIVE = Pattern.compile(
            NAME + "(?:\\s+(?:et\\s+al\\.?|(?:and|&)\\s+" + NAME + "))?\\s*\\(" + YEAR
                    + "(?:,\\s*(?:p|pp)\\.\\s*[\\d\\-–]+)?\\)");

    /** [12], [3, 4], [5-7], [2; 9]. */
    private static final Pattern NUMERIC = Pattern.compile("\\[\\d{1,3}(?:\\s*[,;\\-–]\\s*\\d{1,3})*\\]");

    private Citations() {
    }

    /** Turnitin's four match groups, as named in its public guides. */
    public enum MatchGroup {
        NOT_CITED_OR_QUOTED,
        MISSING_QUOTATIONS,
        MISSING_CITATION,
        CITED_AND_QUOTED;

        public static MatchGroup of(boolean cited, boolean quoted) {
            if (cited) {
                return quoted ? CITED_AND_QUOTED : MISSING_QUOTATIONS;
            }
            return quoted ? MISSING_CITATION : NOT_CITED_OR_QUOTED;
        }
    }

    /** Every in-text citation in the text, in order of position. Overlapping detections are merged. */
    public static List<Region> find(String text) {
        List<Region> found = new ArrayList<>();
        for (Pattern pattern : List.of(PARENTHETICAL, NARRATIVE, NUMERIC)) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                found.add(new Region(m.start(), m.end()));
            }
        }
        found.sort(Comparator.comparingInt(Region::start));
        List<Region> merged = new ArrayList<>();
        for (Region r : found) {
            if (!merged.isEmpty() && r.start() < merged.get(merged.size() - 1).end()) {
                Region last = merged.remove(merged.size() - 1);
                merged.add(new Region(last.start(), Math.max(last.end(), r.end())));
            } else {
                merged.add(r);
            }
        }
        return merged;
    }

    /**
     * The citation that attributes the text between {@code start} and {@code end}, if any: one inside the span, one
     * starting shortly after it, or one ending shortly before it. The search never crosses a paragraph break.
     */
    public static Optional<Region> attributing(String text, List<Region> citations, int start, int end) {
        int paragraphStart = text.lastIndexOf("\n\n", Math.max(0, start - 1));
        int from = Math.max(paragraphStart < 0 ? 0 : paragraphStart, start - LOOK_BEHIND_CHARS);
        int paragraphEnd = text.indexOf("\n\n", end);
        int to = Math.min(paragraphEnd < 0 ? text.length() : paragraphEnd, end + LOOK_AHEAD_CHARS);
        Region best = null;
        for (Region c : citations) {
            boolean inside = c.start() >= start && c.start() < end;
            boolean after = c.start() >= end && c.start() < to;
            boolean before = c.end() <= start && c.start() >= from;
            if (inside || after || before) {
                // Prefer the nearest citation to the span.
                if (best == null || distance(c, start, end) < distance(best, start, end)) {
                    best = c;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static int distance(Region c, int start, int end) {
        if (c.start() >= start && c.start() < end) {
            return 0;
        }
        return c.start() >= end ? c.start() - end : start - c.end();
    }
}
