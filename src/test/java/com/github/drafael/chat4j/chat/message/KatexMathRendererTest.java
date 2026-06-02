package com.github.drafael.chat4j.chat.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KatexMathRendererTest {

    private final KatexMathRenderer subject = KatexMathRenderer.instance();

    @Test
    @DisplayName("Inline math renders to KaTeX HTML before WebView load")
    void render_whenInlineMathProvided_returnsKatexHtml() {
        var result = subject.render("$\\mathcal{E}$", false);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow())
                .contains("class=\"katex\"")
                .contains("mathcal");
    }

    @Test
    @DisplayName("Chemistry notation renders through the mhchem extension")
    void render_whenChemistryFormulaProvided_returnsKatexHtml() {
        var result = subject.render("$\\ce{CO2}$", false);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow())
                .contains("class=\"katex\"")
                .contains("CO");
    }

    @Test
    @DisplayName("Display math strips delimiters and renders in display mode")
    void render_whenDisplayMathProvided_returnsDisplayKatexHtml() {
        var result = subject.render("$$m = \\frac{Q \\cdot M}{F \\cdot z}$$", true);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow())
                .contains("katex-display")
                .contains("m");
    }

    @Test
    @DisplayName("Malformed model math with one extra closing brace is repaired before fallback")
    void render_whenModelMathContainsExtraClosingBrace_returnsKatexHtml() {
        String formula = "\\text{PI}_3^- (\\text{aq}) + \\text{P} (\\text{s}) \\xrightarrow{\\text{Heat}} \\text{I}_3^- \\text{(in situ)}} \\text{I}_5^- + \\text{H}_2\\text{O} + \\text{PH}_3(\\text{g})";

        var result = subject.render(formula, true);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow())
                .contains("katex-display")
                .contains("Heat")
                .doesNotContain("katex-error");
    }

    @Test
    @DisplayName("Render cache is bounded")
    void render_whenManyUniqueExpressionsProvided_keepsCacheBounded() {
        for (int i = 0; i < 600; i++) {
            subject.render("$x_%d$".formatted(i), false);
        }

        assertThat(subject.cacheSize()).isLessThanOrEqualTo(512);
    }

    @Test
    @DisplayName("Unrecoverable parse errors keep the fallback visible")
    void render_whenKatexCannotParseFormula_returnsEmpty() {
        var result = subject.render("\\notARealCommand{", true);

        assertThat(result).isEmpty();
    }
}
