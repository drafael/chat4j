package com.github.drafael.chat4j.provider.support;

import lombok.NonNull;

public final class CredentialStoragePolicy {

    public static final int MAX_UTF8_BYTES = 64 * 1024;

    private CredentialStoragePolicy() {
    }

    public static Validation validate(@NonNull CharSequence value) {
        long utf8Bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return Validation.MALFORMED_UTF16;
                }
                index++;
                utf8Bytes += 4;
            } else if (Character.isLowSurrogate(character)) {
                return Validation.MALFORMED_UTF16;
            } else if (character <= 0x7f) {
                utf8Bytes++;
            } else if (character <= 0x7ff) {
                utf8Bytes += 2;
            } else {
                utf8Bytes += 3;
            }
        }
        return utf8Bytes > MAX_UTF8_BYTES ? Validation.TOO_LARGE : Validation.VALID;
    }

    public enum Validation {
        VALID,
        MALFORMED_UTF16,
        TOO_LARGE
    }
}
