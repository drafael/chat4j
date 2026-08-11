package com.github.drafael.chat4j.chat.render;

public class MarkdownRenderer {

    public static String toHtml(String markdown, boolean isDark) {
        return toHtml(markdown, MarkdownPaletteResolver.resolve(isDark));
    }

    public static String toHtml(String markdown, Palette palette) {
        String css = MarkdownCssBuilder.build(palette);
        String body = toBodyHtml(markdown, palette);
        return "<html><head><style>%s</style></head><body>%s</body></html>".formatted(css, body);
    }

    static String toBodyHtml(String markdown, Palette palette) {
        String linkedMarkdown = SourceReferenceLinkifier.linkify(markdown);
        return MarkdownBlockRenderer.render(linkedMarkdown, palette);
    }
}
