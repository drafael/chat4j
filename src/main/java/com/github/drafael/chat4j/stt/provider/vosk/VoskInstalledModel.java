package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.stt.provider.LocalSpeechToTextModelReference;
import java.nio.file.Path;
import lombok.NonNull;

public record VoskInstalledModel(
        @NonNull String id,
        @NonNull String label,
        @NonNull Path directory,
        Path realDirectory,
        VoskModelCatalogEntry catalogEntry,
        boolean custom,
        boolean obsolete,
        boolean deleteable,
        @NonNull VoskValidationStatus validationStatus,
        @NonNull String validationMessage,
        @NonNull String fingerprint
) {

    public boolean eligible() {
        return ready();
    }

    public boolean ready() {
        return validationStatus == VoskValidationStatus.VALID;
    }

    public LocalSpeechToTextModelReference reference() {
        return new LocalSpeechToTextModelReference(id, realDirectory == null ? directory : realDirectory, fingerprint);
    }
}
