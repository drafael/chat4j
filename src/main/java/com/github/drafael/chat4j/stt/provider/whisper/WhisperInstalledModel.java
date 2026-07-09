package com.github.drafael.chat4j.stt.provider.whisper;

import com.github.drafael.chat4j.stt.provider.LocalSpeechToTextModelReference;
import java.nio.file.Path;
import lombok.NonNull;

public record WhisperInstalledModel(
        @NonNull String id,
        @NonNull String label,
        @NonNull Path directory,
        Path realDirectory,
        WhisperModelCatalogEntry catalogEntry,
        boolean deleteable,
        @NonNull WhisperValidationStatus validationStatus,
        @NonNull String validationMessage,
        @NonNull String fingerprint
) {

    public boolean ready() {
        return validationStatus == WhisperValidationStatus.VALID;
    }

    public boolean eligible() {
        return ready();
    }

    public LocalSpeechToTextModelReference reference() {
        return new LocalSpeechToTextModelReference(id, realDirectory == null ? directory : realDirectory, fingerprint);
    }
}
