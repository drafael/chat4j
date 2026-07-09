package com.github.drafael.chat4j.stt.provider.whisper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WhisperModelCatalogTest {

    @Test
    @DisplayName("Whisper catalog pins integrity metadata for the full official model list")
    void entries_whenLoaded_havePinnedIntegrityMetadata() {
        assertThat(WhisperModelCatalog.entries()).hasSize(30);
        assertThat(WhisperModelCatalog.entries())
                .allSatisfy(entry -> {
                    assertThat(entry.id()).isNotBlank();
                    assertThat(entry.expectedFileName()).isEqualTo("ggml-%s.bin".formatted(entry.id()));
                    assertThat(entry.sizeBytes()).isPositive();
                    assertThat(entry.sha1()).matches("[a-f0-9]{40}");
                    assertThat(entry.downloadUri()).hasScheme("https");
                });
    }

    @Test
    @DisplayName("Tinydiarize model uses the documented upstream exception")
    void find_whenTinydiarize_usesExceptionRepository() {
        var entry = WhisperModelCatalog.find("small.en-tdrz").orElseThrow();

        assertThat(entry.tinydiarize()).isTrue();
        assertThat(entry.downloadUri()).hasHost("huggingface.co");
        assertThat(entry.downloadUri().getPath()).isEqualTo("/akashmjn/tinydiarize-whisper.cpp/resolve/main/ggml-small.en-tdrz.bin");
    }
}
