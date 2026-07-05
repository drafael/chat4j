package com.github.drafael.chat4j.stt.provider;

import org.apache.commons.lang3.StringUtils;

public record SpeechToTextResult(String text) {

    public SpeechToTextResult {
        text = StringUtils.trimToEmpty(text);
    }

    public boolean empty() {
        return StringUtils.isBlank(text);
    }

    @Override
    public String toString() {
        return "SpeechToTextResult[textLength=%d]".formatted(text.length());
    }
}
