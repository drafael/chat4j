package com.github.drafael.chat4j.chat;

final class HtmlEscaper {

    private HtmlEscaper() {
    }

    static String escape(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
