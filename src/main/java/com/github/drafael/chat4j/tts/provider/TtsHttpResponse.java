package com.github.drafael.chat4j.tts.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.Strings;

import static java.util.Collections.emptyMap;

public record TtsHttpResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {

    public TtsHttpResponse {
        headers = sanitizeHeaders(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public String firstHeader(String name) {
        return headers.entrySet().stream()
                .filter(entry -> Strings.CI.equals(entry.getKey(), name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse("");
    }

    private static Map<String, List<String>> sanitizeHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return emptyMap();
        }
        Map<String, List<String>> sanitized = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name == null || values == null) {
                return;
            }
            List<String> sanitizedValues = values.stream()
                    .filter(Objects::nonNull)
                    .toList();
            if (!sanitizedValues.isEmpty()) {
                sanitized.put(name, List.copyOf(sanitizedValues));
            }
        });
        return Map.copyOf(sanitized);
    }

    @Override
    public String toString() {
        return "TtsHttpResponse[statusCode=%d, headers=%d, body=<masked:%d>]"
                .formatted(statusCode, headers.size(), body.length);
    }
}
