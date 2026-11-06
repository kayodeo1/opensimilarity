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
}
