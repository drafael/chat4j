package com.github.drafael.chat4j.chat;

final class MarkdownCssBuilder {

    private MarkdownCssBuilder() {
    }

    static String build(Palette palette) {
        return """
            body {
                font-family: %s;
                font-size: 11px;
                color: %s;
                margin: 0;
                padding: 0;
                line-height: 1.4;
                word-wrap: break-word;
            }
            h1, h2, h3, h4, h5, h6 {
                color: %s;
                margin-left: 0;
                margin-right: 0;
                font-weight: bold;
            }
            h1 { font-size: 16px; margin-top: 14px; margin-bottom: 6px; }
            h2 { font-size: 14px; margin-top: 12px; margin-bottom: 5px; }
            h3 { font-size: 12px; margin-top: 10px; margin-bottom: 4px; }
            h4 { font-size: 11px; margin-top: 9px; margin-bottom: 4px; }
            h5 { font-size: 10px; margin-top: 8px; margin-bottom: 3px; }
            h6 { font-size: 10px; margin-top: 8px; margin-bottom: 3px; }
            p {
                margin: 5px 0;
                word-wrap: break-word;
            }
            ul, ol { margin: 4px 0; padding-left: 20px; }
            li {
                margin: 1px 0;
                word-wrap: break-word;
            }
            a { color: %s; text-decoration: none; }
            a:hover { text-decoration: underline; }
            hr {
                border: none;
                border-top: 1px solid %s;
                margin: 10px 0;
                height: 0;
            }
            blockquote {
                margin: 6px 0;
                padding: 4px 10px;
                border-left: 3px solid %s;
                background: %s;
                color: %s;
            }
            table {
                border-collapse: collapse;
                margin: 6px 0;
                width: 100%%;
            }
            th, td {
                border-bottom: 1px solid %s;
                padding: 6px 10px;
                word-wrap: break-word;
            }
            code, pre {
                font-family: %s;
            }
            """.formatted(
                palette.baseFontFamily(),
                palette.textColor(),
                palette.textColor(),
                palette.linkColor(),
                palette.hrColor(),
                palette.codeBorder(),
                palette.surfaceBg(),
                palette.textColor(),
                palette.codeBorder(),
                palette.monoFontFamily());
    }
}
