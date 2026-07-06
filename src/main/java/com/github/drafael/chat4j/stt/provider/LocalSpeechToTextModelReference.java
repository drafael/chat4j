package com.github.drafael.chat4j.stt.provider;

import java.nio.file.Path;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public record LocalSpeechToTextModelReference(
        @NonNull String modelId,
        @NonNull Path directory,
        @NonNull String fingerprint
) {

    public LocalSpeechToTextModelReference {
        modelId = StringUtils.trimToEmpty(modelId);
        fingerprint = StringUtils.trimToEmpty(fingerprint);
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        if (fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint must not be blank");
        }
    }

    @Override
    public String toString() {
        return "LocalSpeechToTextModelReference[modelId=%s, directory=****, fingerprint=%s]"
                .formatted(modelId, fingerprint);
    }
}
