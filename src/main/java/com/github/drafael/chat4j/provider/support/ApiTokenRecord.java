package com.github.drafael.chat4j.provider.support;

import java.time.Instant;

public record ApiTokenRecord(String algorithm, String nonce, String ciphertext, String updatedAt) {

    public static final String ALGORITHM = "AES-256-GCM";

    public ApiTokenRecord {
        updatedAt = updatedAt == null ? Instant.EPOCH.toString() : updatedAt;
    }

    @Override
    public String toString() {
        return "ApiTokenRecord[algorithm=%s, nonce=<masked>, ciphertext=<masked>, updatedAt=%s]"
                .formatted(algorithm, updatedAt);
    }
}
