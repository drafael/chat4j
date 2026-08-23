package com.github.drafael.chat4j.http;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toUnmodifiableMap;

public record HttpExchangeRequest(
        @NonNull String method,
        @NonNull URI uri,
        @NonNull Map<String, String> headers,
        @NonNull HttpBody body,
        @NonNull Duration timeout,
        long maxResponseBytes
) {
    public HttpExchangeRequest {
        method = StringUtils.defaultIfBlank(method, "GET").toUpperCase();
        headers = sanitize(headers);
    }

    private static Map<String, String> sanitize(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return emptyMap();
        }
        return headers.entrySet().stream()
                .filter(entry -> StringUtils.isNotBlank(entry.getKey()))
                .filter(entry -> entry.getValue() != null)
                .collect(toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));
    }

    @Override
    public String toString() {
        return "HttpExchangeRequest[method=%s, uri=%s, headers=<masked:%d>, body=<masked>, timeout=%s, maxResponseBytes=%d]"
                .formatted(method, uri, headers.size(), timeout, maxResponseBytes);
    }
}
