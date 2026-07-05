package com.github.drafael.chat4j.stt.provider;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

public record SttHttpResponse(int statusCode, @NonNull Map<String, List<String>> headers, @NonNull byte[] body) {

    public boolean successful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "SttHttpResponse[statusCode=%d, bodyBytes=%d]".formatted(statusCode, body.length);
    }
}
