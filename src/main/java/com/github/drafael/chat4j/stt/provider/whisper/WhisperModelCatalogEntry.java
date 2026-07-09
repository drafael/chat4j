package com.github.drafael.chat4j.stt.provider.whisper;

import java.net.URI;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public record WhisperModelCatalogEntry(
        @NonNull String id,
        @NonNull String label,
        @NonNull String description,
        @NonNull String sizeLabel,
        @NonNull URI downloadUri,
        long sizeBytes,
        @NonNull String sha1,
        @NonNull String expectedFileName,
        boolean englishOnly,
        boolean quantized,
        boolean tinydiarize
) {

    public WhisperModelCatalogEntry {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (StringUtils.isBlank(expectedFileName)) {
            throw new IllegalArgumentException("expectedFileName must not be blank");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (!StringUtils.defaultString(sha1).matches("[a-fA-F0-9]{40}")) {
            throw new IllegalArgumentException("sha1 must be a 40-character hex digest");
        }
    }
}
