package com.github.drafael.chat4j.chat;

public class MarkdownRenderer {

    public static String toHtml(String markdown, boolean isDark) {
        Palette palette = MarkdownPaletteResolver.resolve(isDark);
        String css = MarkdownCssBuilder.build(palette);
        String body = MarkdownBlockRenderer.render(markdown, palette);
        return "<html><head><style>" + css + "</style></head><body>" + body + "</body></html>";
    }
}
