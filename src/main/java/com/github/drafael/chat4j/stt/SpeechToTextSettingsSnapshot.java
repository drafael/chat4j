package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import java.net.URI;
import java.nio.file.Path;

public record SpeechToTextSettingsSnapshot(
        SpeechToTextProvider provider,
        SpeechToTextCatalogItem model,
        boolean available,
        int maxDurationSeconds,
        Path modelDirectory,
        URI baseUri,
        URI transcriptionUri,
        String statusMessage
) {

    public static SpeechToTextSettingsSnapshot off(int maxDurationSeconds, Path modelDirectory) {
        return new SpeechToTextSettingsSnapshot(null, null, false, maxDurationSeconds, modelDirectory, null, null, "Speech to Text is turned off.");
    }

    public boolean enabled() {
        return provider != null;
    }

    public String providerId() {
        return provider == null ? SettingsKeys.STT_PROVIDER_OFF : provider.id();
    }

    @Override
    public String toString() {
        return "SpeechToTextSettingsSnapshot[providerId=%s, model=%s, available=%s, maxDurationSeconds=%d, modelDirectory=****, baseUri=%s, transcriptionUri=%s]"
                .formatted(providerId(), model, available, maxDurationSeconds, baseUri, transcriptionUri);
    }
}
