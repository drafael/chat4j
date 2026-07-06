package com.github.drafael.chat4j.stt.provider.vosk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoskBundledCatalogLoaderTest {

    @Test
    @DisplayName("Bundled fallback catalog includes active Russian and Ukrainian speech models")
    void load_whenBundledFallbackUsed_includesRussianAndUkrainianModels() {
        var subject = new VoskBundledCatalogLoader(new VoskModelCatalogClient());

        var catalog = subject.load();

        assertThat(catalog)
                .extracting(VoskModelCatalogEntry::name)
                .contains(
                        "vosk-model-small-ru-0.22",
                        "vosk-model-ru-0.42",
                        "vosk-model-small-uk-v3-small",
                        "vosk-model-uk-v3",
                        "vosk-model-uk-v3-lgraph"
                );
    }
}
