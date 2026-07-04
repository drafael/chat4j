package com.github.drafael.chat4j.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

abstract class AbstractHttpTextToSpeechProvider implements TextToSpeechProvider {

    protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TtsHttpTransport transport;

    protected AbstractHttpTextToSpeechProvider(TtsHttpTransport transport) {
        this.transport = transport;
    }

    protected TtsHttpResponse get(URI uri, Map<String, String> headers) throws Exception {
        TtsHttpResponse response = transport.send(new TtsHttpRequest("GET", uri, headers, null));
        requireSuccess(response, displayName());
        return response;
    }

    protected TtsHttpResponse postJson(URI uri, Map<String, String> headers, JsonNode body) throws Exception {
        byte[] payload = OBJECT_MAPPER.writeValueAsBytes(body);
        TtsHttpResponse response = transport.send(new TtsHttpRequest("POST", uri, headers, payload));
        requireSuccess(response, displayName());
        return response;
    }

    protected JsonNode jsonBody(TtsHttpResponse response) throws Exception {
        return OBJECT_MAPPER.readTree(response.body());
    }

    protected TextToSpeechAudio audioBody(TtsHttpResponse response, String fallbackFormat) {
        String contentType = response.firstHeader("content-type");
        return new TextToSpeechAudio(response.body(), contentType, fallbackFormat);
    }

    protected List<TextToSpeechCatalogItem> nonEmptyOrBundled(List<TextToSpeechCatalogItem> discovered, List<TextToSpeechCatalogItem> bundled) {
        return discovered == null || discovered.isEmpty() ? bundled : discovered;
    }

    private static void requireSuccess(TtsHttpResponse response, String providerName) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        String detail = safeErrorDetail(response);
        String suffix = StringUtils.isBlank(detail) ? "" : ": %s".formatted(detail);
        throw new IllegalStateException("%s TTS request failed with HTTP %d%s".formatted(providerName, response.statusCode(), suffix));
    }

    private static String safeErrorDetail(TtsHttpResponse response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (StringUtils.isBlank(body) || body.stripLeading().startsWith("<")) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            String message = root.path("error").path("message").asText("");
            if (StringUtils.isBlank(message)) {
                message = root.path("message").asText("");
            }
            return StringUtils.abbreviate(StringUtils.normalizeSpace(message), 300);
        } catch (Exception e) {
            return "";
        }
    }
}
