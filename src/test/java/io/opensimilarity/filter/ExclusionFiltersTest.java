package io.opensimilarity.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.opensimilarity.filter.ExclusionFilters.Region;
import io.opensimilarity.fingerprint.Tokenizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExclusionFiltersTest {

    private static final String BODY = "Body text. ".repeat(40);

    @Test
    void findsReferenceListHeadingVariants() {
        for (String heading : List.of("References", "REFERENCES", "7. References", "Bibliography:", "Works Cited", "  iv. Reference List  ")) {
            String text = BODY + "\n" + heading + "\nSmith (2020). A paper.";
            Region region = ExclusionFilters.bibliography(text).orElseThrow();
            assertThat(text.substring(region.start()).strip()).startsWith(heading.strip());
            assertThat(region.end()).isEqualTo(text.length());
        }
    }

    @Test
    void ignoresHeadingsThatAreNotReferenceLists() {
        // In prose, and too early in the document to be the list itself.
        assertThat(ExclusionFilters.bibliography(BODY + "\nThe references given below are useful.\n")).isEmpty();
        assertThat(ExclusionFilters.bibliography("References\n" + BODY)).isEmpty();
    }

    @Test
    void bibliographyStopsAtAppendix() {
        String text = BODY + "\nReferences\nSmith (2020).\nAppendix A: Survey\nQuestion one.";
        Region region = ExclusionFilters.bibliography(text).orElseThrow();
        assertThat(text.substring(region.start(), region.end())).contains("Smith").doesNotContain("Survey");
    }

    @Test
    void findsStraightAndCurlyQuotes() {
        String text = "He said \"first quote\" and “second quote” then stopped.";
        assertThat(ExclusionFilters.quotes(text)).extracting(r -> text.substring(r.start(), r.end()))
                .containsExactly("\"first quote\"", "“second quote”");
    }

    @Test
    void unclosedOrOverlongQuotesAreNotExclusions() {
        assertThat(ExclusionFilters.quotes("An inch mark 5\" and then\n\nanother \" paragraph")).isEmpty();
        assertThat(ExclusionFilters.quotes("\"" + "long text ".repeat(200) + "\"")).isEmpty();
    }

    @Test
    void masksWordsInsideRegions() {
        String text = "aa bb cc dd";
        boolean[] mask = ExclusionFilters.mask(Tokenizer.tokenize(text), List.of(new Region(3, 8)));
        assertThat(mask).containsExactly(false, true, true, false);
    }
}
