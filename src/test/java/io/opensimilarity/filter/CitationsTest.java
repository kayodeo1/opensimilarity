package io.opensimilarity.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.opensimilarity.filter.Citations.MatchGroup;
import io.opensimilarity.filter.ExclusionFilters.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationsTest {

    private static List<String> found(String text) {
        return Citations.find(text).stream().map(r -> text.substring(r.start(), r.end())).toList();
    }

    @Test
    void findsAuthorYearAndNumericCitations() {
        assertThat(found("as shown (Okafor, 2019). Later (Okafor & Bello, 2020, p. 14) and (see Adeyemi et al. 2018)."))
                .containsExactly("(Okafor, 2019)", "(Okafor & Bello, 2020, p. 14)", "(see Adeyemi et al. 2018)");
        assertThat(found("Okafor (2019) and Bello and Musa (2021, p. 3) argue")).containsExactly(
                "Okafor (2019)", "Bello and Musa (2021, p. 3)");
        assertThat(found("in recent work [12] and [3, 4] and [5-7]")).containsExactly("[12]", "[3, 4]", "[5-7]");
    }

    @Test
    void ignoresParenthesesWithoutAuthorAndYear() {
        assertThat(found("the pump (see figure) ran for (about 20 minutes) at [x] speed")).isEmpty();
    }

    @Test
    void attributesCitationsNearTheSpanButNotAcrossParagraphs() {
        String text = "Okafor (2019) reports that the tank emptied by noon. Another sentence follows.\n\n"
                + "A new paragraph says the tank emptied by noon.";
        List<Region> citations = Citations.find(text);
        int first = text.indexOf("the tank");
        assertThat(Citations.attributing(text, citations, first, first + 25)).isPresent();
        int second = text.lastIndexOf("the tank");
        assertThat(Citations.attributing(text, citations, second, second + 25)).isEmpty();

        String after = "The tank emptied by noon on every weekday (Bello, 2020).";
        assertThat(Citations.attributing(after, Citations.find(after), 0, 30)).isPresent();
    }

    @Test
    void classifiesMatchGroupsAsTurnitinDescribesThem() {
        assertThat(MatchGroup.of(false, false)).isEqualTo(MatchGroup.NOT_CITED_OR_QUOTED);
        assertThat(MatchGroup.of(true, false)).isEqualTo(MatchGroup.MISSING_QUOTATIONS);
        assertThat(MatchGroup.of(false, true)).isEqualTo(MatchGroup.MISSING_CITATION);
        assertThat(MatchGroup.of(true, true)).isEqualTo(MatchGroup.CITED_AND_QUOTED);
    }
}
