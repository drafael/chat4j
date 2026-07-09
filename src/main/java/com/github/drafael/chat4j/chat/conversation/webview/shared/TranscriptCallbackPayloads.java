package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

public final class TranscriptCallbackPayloads {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TranscriptCallbackPayloads() {
    }

    public static String callbackArg(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        if (value.isEmpty()) {
            return "";
        }

        try {
            JsonNode node = OBJECT_MAPPER.readTree(value);
            if (node.has("args") && node.get("args").isArray() && !node.get("args").isEmpty()) {
                return node.get("args").get(0).asText("");
            }
            if (node.isArray() && !node.isEmpty()) {
                return node.get(0).asText("");
            }
            if (node.isTextual() || node.isNumber() || node.isBoolean()) {
                return node.asText("");
            }
        } catch (Exception ignored) {
            // Fall back to legacy raw string handling.
        }

        return StringUtils.unwrap(value, '"');
    }

    public static TranscriptAction transcriptAction(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        if (value.isEmpty()) {
            return null;
        }

        try {
            return transcriptAction(OBJECT_MAPPER.readTree(value), 0);
        } catch (Exception ignored) {
            // Ignore malformed callback payloads.
        }

        return null;
    }

    private static TranscriptAction transcriptAction(JsonNode node, int depth) throws Exception {
        if (node == null || depth > 2) {
            return null;
        }
        JsonNode args = node.has("args") && node.get("args").isArray() ? node.get("args") : node;
        if (args.isArray() && args.size() >= 2) {
            String text = args.size() >= 3 ? args.get(2).asText("") : "";
            return new TranscriptAction(args.get(0).asText(""), args.get(1).asInt(-1), text);
        }
        if (args.isArray() && args.size() == 1) {
            return transcriptAction(args.get(0), depth + 1);
        }
        if (node.isTextual() && StringUtils.isNotBlank(node.asText())) {
            return transcriptAction(OBJECT_MAPPER.readTree(node.asText()), depth + 1);
        }
        return null;
    }

    public record TranscriptAction(String action, int messageIndex, String text) {
        @Override
        public String toString() {
            return "TranscriptAction[action=%s, messageIndex=%d, text=<masked>]".formatted(action, messageIndex);
        }
    }
}
