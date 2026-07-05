package com.github.drafael.chat4j.stt.model;

import java.nio.file.Path;
import lombok.NonNull;

public record SpeechToTextModelDescriptor(@NonNull String providerId, @NonNull String modelId, @NonNull Path directory) {

    @Override
    public String toString() {
        return "SpeechToTextModelDescriptor[providerId=%s, modelId=%s, directory=****]".formatted(providerId, modelId);
    }
}
