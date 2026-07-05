package com.github.drafael.chat4j.stt.audio;

import java.nio.file.Path;
import lombok.NonNull;

public record CapturedAudio(@NonNull Path path, long durationMillis, long sizeBytes) {

    @Override
    public String toString() {
        return "CapturedAudio[path=%s, durationMillis=%d, sizeBytes=%d]".formatted(path.getFileName(), durationMillis, sizeBytes);
    }
}
