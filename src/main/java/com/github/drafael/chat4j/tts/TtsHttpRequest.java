package com.github.drafael.chat4j.tts;

import java.net.URI;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toUnmodifiableMap;

public record TtsHttpRequest(String method, URI uri, Map<String, String> headers, byte[] body) {

    public TtsHttpRequest {
        method = StringUtils.defaultIfBlank(method, "GET").toUpperCase();
        headers = sanitizeHeaders(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    private static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
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
        return "TtsHttpRequest[method=%s, uri=%s, headers=<masked:%d>, body=<masked:%d>]"
                .formatted(method, uri, headers.size(), body.length);
    }
}
