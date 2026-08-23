package com.github.drafael.chat4j.chat.conversation.webview.shared;

import com.github.drafael.chat4j.json.JsonCodec;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public final class TranscriptCallbackPayloads {

    private static final JsonCodec JSON_CODEC = JsonCodec.standard();

    private TranscriptCallbackPayloads() {
    }

    public static String callbackArg(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        if (value.isEmpty()) {
            return "";
        }

        try {
            Object decoded = JSON_CODEC.read(value, Object.class);
            Object args = decoded instanceof Map<?, ?> object ? object.get("args") : decoded;
            if (args instanceof List<?> values && !values.isEmpty()) {
                return scalarText(values.getFirst());
            }
            if (isScalar(args)) {
                return scalarText(args);
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
            return transcriptAction(JSON_CODEC.read(value, Object.class), 0);
        } catch (Exception ignored) {
            // Ignore malformed callback payloads.
        }

        return null;
    }

    private static TranscriptAction transcriptAction(Object decoded, int depth) {
        if (decoded == null || depth > 2) {
            return null;
        }
        Object args = decoded instanceof Map<?, ?> object && object.get("args") instanceof List<?> values ? values : decoded;
        if (args instanceof List<?> values && values.size() >= 2) {
            String text = values.size() >= 3 ? scalarText(values.get(2)) : "";
            return new TranscriptAction(scalarText(values.get(0)), integerValue(values.get(1)), text);
        }
        if (args instanceof List<?> values && values.size() == 1) {
            return transcriptAction(values.getFirst(), depth + 1);
        }
        if (decoded instanceof String nested && StringUtils.isNotBlank(nested)) {
            try {
                return transcriptAction(JSON_CODEC.read(nested, Object.class), depth + 1);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static String scalarText(Object value) {
        return isScalar(value) ? String.valueOf(value) : "";
    }

    private static int integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(scalarText(value));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public record TranscriptAction(String action, int messageIndex, String text) {
        @Override
        public String toString() {
            return "TranscriptAction[action=%s, messageIndex=%d, text=<masked>]".formatted(action, messageIndex);
        }
    }
}
