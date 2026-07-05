package com.github.drafael.chat4j.stt.provider;

import java.nio.file.Path;
import lombok.NonNull;

public record SpeechToTextRequest(
        @NonNull String providerId,
        @NonNull String modelId,
        @NonNull Path audioFile,
        long durationMillis,
        long sizeBytes
) {

    @Override
    public String toString() {
        return "SpeechToTextRequest[providerId=%s, modelId=%s, audioFile=%s, durationMillis=%d, sizeBytes=%d]"
                .formatted(providerId, modelId, audioFile.getFileName(), durationMillis, sizeBytes);
    }
}
