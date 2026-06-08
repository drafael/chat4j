package com.github.drafael.chat4j.chat.render;

public record ThinkTagSplit(String visibleText, String thinkingText) {
    @Override
    public String toString() {
        return "ThinkTagSplit[visibleText=<masked>, thinkingText=<masked>]";
    }

    static ThinkTagSplit empty() {
        return new ThinkTagSplit("", "");
    }
}
