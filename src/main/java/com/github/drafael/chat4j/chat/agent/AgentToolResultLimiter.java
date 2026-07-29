package com.github.drafael.chat4j.chat.agent;

import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;

public final class AgentToolResultLimiter {

    public static final int MAX_BYTES = 64 * 1024;
    private static final String MARKER = "\n\n[truncated after 65536 bytes]";

    private AgentToolResultLimiter() {
    }

    public static String limit(String value) {
        String normalized = StringUtils.defaultString(value);
        if (normalized.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
            return normalized;
        }

        int byteCount = 0;
        int end = 0;
        while (end < normalized.length()) {
            int codePoint = normalized.codePointAt(end);
            int codePointBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (byteCount + codePointBytes > MAX_BYTES) {
                break;
            }
            byteCount += codePointBytes;
            end += Character.charCount(codePoint);
        }
        return "%s%s".formatted(normalized.substring(0, end), MARKER);
    }
}
