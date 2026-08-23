package com.github.drafael.chat4j.tts.provider;

import lombok.NonNull;

public final class TtsJsonTestSupport {

    private TtsJsonTestSupport() {
    }

    public static <T> T read(@NonNull byte[] body, @NonNull Class<T> responseType) {
        var client = new TtsHttpClient((request, cancellation) -> {
            throw new AssertionError("JSON decoding must not use the transport");
        });
        return client.readJson(body, responseType, "Test JSON was invalid.");
    }
}
