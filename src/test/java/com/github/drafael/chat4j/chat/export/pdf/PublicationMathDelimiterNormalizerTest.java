package com.github.drafael.chat4j.chat.export.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationMathDelimiterNormalizerTest {

    @Test
    @DisplayName("Backslash math delimiters are converted to GFM-compatible dollar delimiters")
    void normalize_whenBackslashMathIsValid_convertsDisplayInlineAndTableMath() {
        String markdown = slashes("""
                Before §( N §) turns.

                - Constant
                    - §( F §approx 96,485.3 § §text{C/mol} §)

                §[
                §mathcal{E} = -§frac{d§Phi_B}{dt}
                §]

                | Form | Equation |
                | --- | --- |
                | Integral | §(§displaystyle §oint §mathbf{E} §cdot d§boldsymbol{§ell}§) |
                """);

        assertThat(PublicationMathDelimiterNormalizer.normalize(markdown)).isEqualTo(slashes("""
                Before $N$ turns.

                - Constant
                    - $F §approx 96,485.3 § §text{C/mol}$

                $$
                §mathcal{E} = -§frac{d§Phi_B}{dt}
                $$

                | Form | Equation |
                | --- | --- |
                | Integral | $§displaystyle §oint §mathbf{E} §cdot d§boldsymbol{§ell}$ |
                """));
    }

    @Test
    @DisplayName("Math-like delimiters in fenced, indented, and inline code remain literal")
    void normalize_whenDelimitersAreCode_preservesSource() {
        String markdown = slashes("""
                ```markdown
                §[fenced§]
                ```

                ~~~text
                §(tilde fenced§)
                ~~~

                    §(indented§)
                    - §(top-level indented code§)
                    §[
                    indented display
                    §]

                `§(inline§)` and ``§[inline display§]``
                """);

        assertThat(PublicationMathDelimiterNormalizer.normalize(markdown)).isEqualTo(markdown);
    }

    @Test
    @DisplayName("Unmatched and escaped backslash delimiters remain unchanged")
    void normalize_whenDelimiterIsUnmatchedOrEscaped_preservesSource() {
        String markdown = slashes("""
                Unmatched §(value
                Blank §(   §) delimiters

                §§(escaped§§)

                §[
                no closing delimiter
                """);

        assertThat(PublicationMathDelimiterNormalizer.normalize(markdown)).isEqualTo(markdown);
    }

    private String slashes(String value) {
        return value.replace('§', '\\');
    }
}
