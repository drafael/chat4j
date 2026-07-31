package com.github.drafael.chat4j.chat.render;

import org.apache.commons.lang3.StringUtils;

public final class BoundedUtf8 {

    private BoundedUtf8() {
    }

    public static String presentation(String source, int maximumCodePoints, long maximumBytes) {
        return presentation(source, maximumCodePoints, maximumBytes, false);
    }

    public static String multilinePresentation(String source, int maximumCodePoints, long maximumBytes) {
        return presentation(source, maximumCodePoints, maximumBytes, true);
    }

    private static String presentation(
            String source,
            int maximumCodePoints,
            long maximumBytes,
            boolean preserveLineFeeds
    ) {
        if (maximumCodePoints < 0 || maximumBytes < 0) {
            throw new IllegalArgumentException("limits must not be negative");
        }
        String value = StringUtils.defaultString(source);
        StringBuilder filtered = new StringBuilder(value.length());
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int characters = Character.charCount(codePoint);
            if (characters == 1 && Character.isSurrogate(value.charAt(index))) {
                codePoint = 0xfffd;
            }
            if (!isPresentationControl(codePoint, preserveLineFeeds)) {
                filtered.appendCodePoint(codePoint);
            }
            index += characters;
        }

        String trimmed = filtered.toString().strip();
        StringBuilder bounded = new StringBuilder(Math.min(trimmed.length(), maximumCodePoints));
        long bytes = 0;
        int codePoints = 0;
        for (int index = 0; index < trimmed.length() && codePoints < maximumCodePoints;) {
            int codePoint = trimmed.codePointAt(index);
            int codePointBytes = utf8Length(codePoint);
            if (bytes + codePointBytes > maximumBytes) {
                break;
            }
            bounded.appendCodePoint(codePoint);
            bytes += codePointBytes;
            codePoints++;
            index += Character.charCount(codePoint);
        }
        return bounded.toString();
    }

    private static int utf8Length(int codePoint) {
        return codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
    }

    private static boolean isPresentationControl(int codePoint, boolean preserveLineFeeds) {
        if (preserveLineFeeds && codePoint == '\n') {
            return false;
        }
        return codePoint <= 0x1f
                || codePoint >= 0x7f && codePoint <= 0x9f
                || codePoint == 0x061c
                || codePoint >= 0x200e && codePoint <= 0x200f
                || codePoint >= 0x202a && codePoint <= 0x202e
                || codePoint >= 0x2066 && codePoint <= 0x2069;
    }
}
