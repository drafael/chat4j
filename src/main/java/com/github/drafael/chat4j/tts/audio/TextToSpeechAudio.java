package com.github.drafael.chat4j.tts.audio;

import org.apache.commons.lang3.StringUtils;

public record TextToSpeechAudio(byte[] bytes, String contentType, String format) {

    public TextToSpeechAudio {
        bytes = bytes == null ? new byte[0] : bytes.clone();
        contentType = StringUtils.defaultString(contentType);
        String detectedFormat = formatFromContentType(contentType);
        format = StringUtils.defaultIfBlank(detectedFormat, StringUtils.trimToEmpty(format));
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "TextToSpeechAudio[bytes=<masked:%d>, contentType=%s, format=%s]".formatted(bytes.length, contentType, format);
    }

    private static String formatFromContentType(String contentType) {
        String normalized = StringUtils.defaultString(contentType).toLowerCase();
        if (normalized.contains("mpeg") || normalized.contains("mp3")) {
            return "mp3";
        }
        if (normalized.contains("wav") || normalized.contains("wave")) {
            return "wav";
        }
        return "";
    }
}
