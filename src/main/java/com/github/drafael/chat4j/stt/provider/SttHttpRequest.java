package com.github.drafael.chat4j.stt.provider;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import lombok.NonNull;

public record SttHttpRequest(
        @NonNull String method,
        @NonNull URI uri,
        @NonNull Map<String, String> headers,
        @NonNull HttpRequest.BodyPublisher bodyPublisher,
        @NonNull Duration timeout,
        long maxResponseBytes
) {

    @Override
    public String toString() {
        return "SttHttpRequest[method=%s, uri=%s, headers=%s, timeout=%s, maxResponseBytes=%d]"
                .formatted(method, uri, headers.keySet(), timeout, maxResponseBytes);
    }
}
