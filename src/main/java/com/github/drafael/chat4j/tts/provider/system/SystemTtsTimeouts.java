package com.github.drafael.chat4j.tts.provider.system;

import java.time.Duration;

final class SystemTtsTimeouts {

    private static final long BASE_SECONDS = 30;
    private static final long CHARACTERS_PER_EXTRA_SECOND = 60;
    private static final long MAX_SECONDS = 300;

    private SystemTtsTimeouts() {
    }

    static Duration synthesisTimeout(String text) {
        long characters = text == null ? 0 : text.length();
        long extraSeconds = Math.ceilDiv(characters, CHARACTERS_PER_EXTRA_SECOND);
        return Duration.ofSeconds(Math.min(MAX_SECONDS, BASE_SECONDS + extraSeconds));
    }
}
