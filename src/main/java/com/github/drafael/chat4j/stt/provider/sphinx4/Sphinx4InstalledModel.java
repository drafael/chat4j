package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.github.drafael.chat4j.stt.provider.LocalSpeechToTextModelReference;
import java.nio.file.Path;

public record Sphinx4InstalledModel(
        String id,
        String label,
        String language,
        Path directory,
        Path realDirectory,
        Sphinx4ModelCatalogEntry catalogEntry,
        boolean custom,
        boolean deleteable,
        Sphinx4ValidationStatus validationStatus,
        String validationMessage,
        String fingerprint,
        Sphinx4ModelMetadata metadata
) {

    public boolean ready() {
        return validationStatus == Sphinx4ValidationStatus.VALID;
    }

    public boolean eligible() {
        return ready();
    }

    public boolean selectable() {
        return ready() || validationStatus == Sphinx4ValidationStatus.PLAUSIBLE_UNVERIFIED;
    }

    public LocalSpeechToTextModelReference reference() {
        return new LocalSpeechToTextModelReference(id, realDirectory == null ? directory : realDirectory, fingerprint);
    }
}
