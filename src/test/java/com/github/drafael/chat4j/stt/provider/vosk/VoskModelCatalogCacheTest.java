package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class VoskModelCatalogCacheTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Vosk raw JSON is published before being read from its snapshot")
    void saveRawJson_whenPayloadIsValid_publishesImmutableSnapshot() {
        var settings = new SettingsRepository(tempDir.resolve("settings.properties"));
        var subject = new VoskModelCatalogCache(settings);
        String json = "[{\"name\":\"vosk-model-small-en-us\"}]";

        boolean saved = subject.saveRawJson(json);

        assertThat(saved).isTrue();
        assertThat(subject.rawJson()).contains(json);
        assertThat(settings.get("chat4j.stt.catalog.vosk.rawJsonFile"))
                .hasValueSatisfying(reference -> assertThat(reference)
                        .matches("stt-vosk-raw-json-[0-9a-f]{32}\\.json"));
        assertThat(settings.get("chat4j.stt.catalog.vosk.rawJson")).isEmpty();
    }
}
