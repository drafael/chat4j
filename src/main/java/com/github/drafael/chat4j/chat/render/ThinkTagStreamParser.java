package com.github.drafael.chat4j.chat.render;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public final class ThinkTagStreamParser {
    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    private final StringBuilder pending = new StringBuilder();
    private boolean inThinking;

    public ThinkTagSplit accept(String token) {
        if (StringUtils.isEmpty(token)) {
            return ThinkTagSplit.empty();
        }

        pending.append(token);
        return drain(false);
    }

    public ThinkTagSplit flush() {
        return drain(true);
    }

    private ThinkTagSplit drain(boolean flush) {
        StringBuilder visible = new StringBuilder();
        StringBuilder thinking = new StringBuilder();

        while (!pending.isEmpty()) {
            String tag = inThinking ? CLOSE_TAG : OPEN_TAG;
            int tagIndex = Strings.CI.indexOf(pending.toString(), tag);
            if (tagIndex >= 0) {
                appendCurrentModeText(visible, thinking, pending.substring(0, tagIndex));
                pending.delete(0, tagIndex + tag.length());
                inThinking = !inThinking;
                continue;
            }

            int keepLength = flush ? 0 : partialTagSuffixLength(pending, tag);
            int emitLength = pending.length() - keepLength;
            if (emitLength <= 0) {
                break;
            }

            appendCurrentModeText(visible, thinking, pending.substring(0, emitLength));
            pending.delete(0, emitLength);
        }

        if (flush && !pending.isEmpty()) {
            appendCurrentModeText(visible, thinking, pending.toString());
            pending.setLength(0);
        }

        return new ThinkTagSplit(visible.toString(), thinking.toString());
    }

    private void appendCurrentModeText(StringBuilder visible, StringBuilder thinking, String text) {
        if (text.isEmpty()) {
            return;
        }

        if (inThinking) {
            thinking.append(text);
        } else {
            visible.append(text);
        }
    }

    private int partialTagSuffixLength(StringBuilder value, String tag) {
        int maxLength = Math.min(value.length(), tag.length() - 1);
        for (int length = maxLength; length > 0; length--) {
            String suffix = value.substring(value.length() - length);
            if (Strings.CI.startsWith(tag, suffix)) {
                return length;
            }
        }

        return 0;
    }
}
