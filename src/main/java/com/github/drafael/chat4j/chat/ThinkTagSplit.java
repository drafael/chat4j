package com.github.drafael.chat4j.chat;

record ThinkTagSplit(String visibleText, String thinkingText) {
    static ThinkTagSplit empty() {
        return new ThinkTagSplit("", "");
    }
}
