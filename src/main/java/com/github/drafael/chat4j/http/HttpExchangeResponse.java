package com.github.drafael.chat4j.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.Strings;

import static java.util.Collections.emptyMap;

public record HttpExchangeResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
    public HttpExchangeResponse {
        headers = sanitize(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    public boolean successful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public String firstHeader(String name) {
        return headers.entrySet().stream()
                .filter(entry -> Strings.CI.equals(entry.getKey(), name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse("");
    }

    private static Map<String, List<String>> sanitize(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return emptyMap();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name != null && values != null) {
                List<String> safeValues = values.stream().filter(Objects::nonNull).toList();
                if (!safeValues.isEmpty()) {
                    result.put(name, List.copyOf(safeValues));
                }
            }
        });
        return Map.copyOf(result);
    }

    @Override
    public String toString() {
        return "HttpExchangeResponse[statusCode=%d, headers=%d, body=<masked:%d>]"
                .formatted(statusCode, headers.size(), body.length);
    }
}
