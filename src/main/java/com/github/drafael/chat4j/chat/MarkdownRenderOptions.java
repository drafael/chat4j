package com.github.drafael.chat4j.chat;

public record MarkdownRenderOptions(
        boolean latexEnabled,
        boolean singleDollarEnabled,
        boolean bracketDelimitersEnabled
) {

    private static final MarkdownRenderOptions DEFAULTS = new MarkdownRenderOptions(true, true, true);
    private static final MarkdownRenderOptions LATEX_DISABLED = new MarkdownRenderOptions(false, false, false);

    public static MarkdownRenderOptions defaults() {
        return DEFAULTS;
    }

    public static MarkdownRenderOptions latexDisabled() {
        return LATEX_DISABLED;
    }
}
