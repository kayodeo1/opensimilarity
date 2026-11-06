package io.opensimilarity.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AlignerTest {

    private static String[] words(String text) {
        return Tokenizer.tokenize(text).words();
    }

    @Test
    void reportsExactWordRangesOnBothSides() {
        String shared = "one two three four five six seven eight";
        List<Aligner.Match> matches = Aligner.align(words("alpha beta " + shared + " gamma"),
                words("delta " + shared + " epsilon zeta"));
        assertThat(matches).hasSize(1);
        Aligner.Match match = matches.get(0);
        assertThat(match.queryStart()).isEqualTo(2);
        assertThat(match.queryEnd()).isEqualTo(10);
        assertThat(match.sourceStart()).isEqualTo(1);
        assertThat(match.sourceEnd()).isEqualTo(9);
    }

    @Test
    void mergesRunsAcrossASmallEditButCountsOnlyTheExactWords() {
        List<Aligner.Match> matches = Aligner.align(
                words("a1 a2 a3 a4 a5 a6 CHANGED a7 a8 a9 a10 a11 a12"),
                words("a1 a2 a3 a4 a5 a6 edited a7 a8 a9 a10 a11 a12"));
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).runs()).hasSize(2);
        assertThat(matches.get(0).queryEnd() - matches.get(0).queryStart()).isEqualTo(13);
    }

    @Test
    void keepsDistantRunsSeparate() {
        String filler = "x1 x2 x3 x4 x5 x6 x7 x8 x9 x10";
        List<Aligner.Match> matches = Aligner.align(
                words("a1 a2 a3 a4 a5 a6 " + filler + " b1 b2 b3 b4 b5 b6"),
                words("a1 a2 a3 a4 a5 a6 y1 y2 b1 b2 b3 b4 b5 b6"));
        assertThat(matches).hasSize(2);
    }

    @Test
    void ignoresSharedRunsShorterThanAShingleAndUnrelatedText() {
        assertThat(Aligner.align(words("a b c d x y z"), words("a b c d p q r"))).isEmpty();
        assertThat(Aligner.align(words("completely different words in this text"), words("nothing at all shared with the other"))).isEmpty();
    }
}
