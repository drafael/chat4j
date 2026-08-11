package com.github.drafael.chat4j.chat.conversation.webview.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.commons.lang3.StringUtils;

public final class TranscriptReadAloudToken {

    private TranscriptReadAloudToken() {
    }

    public static String create(int messageIndex, String sourceText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) (messageIndex >>> 24));
            digest.update((byte) (messageIndex >>> 16));
            digest.update((byte) (messageIndex >>> 8));
            digest.update((byte) messageIndex);
            digest.update(StringUtils.defaultString(sourceText).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Could not identify a transcript message.", e);
        }
    }
}
