package io.opensimilarity.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import io.opensimilarity.fingerprint.Tokenizer.Tokens;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    @Test
    void normalizesWordsAndKeepsOriginalOffsets() {
        String text = "The  QUICK, brown-fox don’t!";
        Tokens tokens = Tokenizer.tokenize(text);
        assertThat(tokens.words()).containsExactly("the", "quick", "brown", "fox", "don't");
        assertThat(text.substring(tokens.starts()[1], tokens.ends()[1])).isEqualTo("QUICK");
        assertThat(text.substring(tokens.starts()[4], tokens.ends()[4])).isEqualTo("don’t");
    }

    @Test
    void ignoresInvisibleCharactersAndRejoinsLineEndHyphens() {
        assertThat(Tokenizer.tokenize("in​visible soft­hyphen compu-\nter compu­\r\nter").words())
                .containsExactly("invisible", "softhyphen", "computer", "computer");
    }

    @Test
    void doesNotJoinHyphenBeforeCapitalisedLine() {
        assertThat(Tokenizer.tokenize("first-\nSecond").words()).containsExactly("first", "second");
    }

    @Test
    void keepsNonLatinScriptsAndCombiningMarks() {
        // Composed and decomposed forms of the same word must compare equal.
        String[] yoruba = Tokenizer.tokenize("\u1EB9\u0300k\u1ECD\u0301 e\u0323\u0300ko\u0323\u0301").words();
        assertThat(yoruba).hasSize(2);
        assertThat(yoruba[0]).isEqualTo(yoruba[1]);
        assertThat(Tokenizer.tokenize("caf\u00E9 cafe\u0301").words()).containsExactly("caf\u00E9", "caf\u00E9");
    }

    @Test
    void handlesEmptyAndPunctuationOnlyText() {
        assertThat(Tokenizer.tokenize("").size()).isZero();
        assertThat(Tokenizer.tokenize(" ... -- \n ").size()).isZero();
    }

    @Test
    void foldsLookAlikeLettersInLatinWordsAndReportsThem() {
        // "ассess" with Cyrillic а and с, "rеport" with Cyrillic е.
        String text = "We \u0430\u0441cess the r\u0435port";
        Tokens tokens = Tokenizer.tokenize(text);
        assertThat(tokens.words()).containsExactly("we", "access", "the", "report");
        assertThat(tokens.replaced()).containsExactly(3, 4, 15);
    }

    @Test
    void foldsWholeLookAlikeWordsInAMostlyLatinDocument() {
        // "сор" is spelled entirely with Cyrillic letters that look like Latin "cop".
        Tokens tokens = Tokenizer.tokenize("The \u0441\u043e\u0440 stood on the corner of the busy street");
        assertThat(tokens.words()[1]).isEqualTo("cop");
        assertThat(tokens.replaced()).hasSize(3);
    }

    @Test
    void leavesGenuineGreekAndCyrillicWordsAlone() {
        // Russian text, and a Greek word containing letters with no Latin twin.
        Tokens russian = Tokenizer.tokenize("\u0441\u043e\u0440 \u0432\u043e\u0434\u0430 \u043c\u0438\u0440");
        assertThat(russian.words()[0]).isEqualTo("\u0441\u043e\u0440");
        assertThat(russian.replaced()).isEmpty();
        Tokens greek = Tokenizer.tokenize("The word \u03bb\u03bf\u03b3\u03bf\u03c2 means reason");
        assertThat(greek.words()[2]).isEqualTo("\u03bb\u03bf\u03b3\u03bf\u03c2");
        assertThat(greek.replaced()).isEmpty();
    }

    @Test
    void reportsInvisibleCharactersInsideWords() {
        Tokens tokens = Tokenizer.tokenize("in\u200Bvisible plain");
        assertThat(tokens.words()).containsExactly("invisible", "plain");
        assertThat(tokens.invisible()).containsExactly(2);
    }
}
