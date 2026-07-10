package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProvidersPanelTest {

    @Test
    @DisplayName("Provider panel configured base URL reports read failures through status path")
    void configuredProviderBaseUrl_whenReadFails_returnsDefaultAndShowsStatusError() {
        var subject = new ProvidersPanel(new ThrowingSettingsRepo());

        String baseUrl = subject.configuredProviderBaseUrl(
                "LM Studio",
                ProvidersPanel.ProviderInfo.local("http://localhost:1234/v1")
        );

        assertThat(baseUrl).isEqualTo("http://localhost:1234/v1");
        assertThat(subject.statusLabel().isVisible()).isTrue();
        assertThat(subject.statusLabel().getText())
                .contains("Failed to read setting", "chat4j.provider.lm-studio.baseUrl");
    }

    @Test
    @DisplayName("Provider panel base URL helper falls back to default only for blank values")
    void fallbackBlankProviderBaseUrl_whenValueIsBlank_returnsDefaultOtherwisePreservesValue() {
        assertThat(ProvidersPanel.fallbackBlankProviderBaseUrl(null, "http://localhost:1234/v1"))
                .isEqualTo("http://localhost:1234/v1");
        assertThat(ProvidersPanel.fallbackBlankProviderBaseUrl("   ", "http://localhost:1234/v1"))
                .isEqualTo("http://localhost:1234/v1");
        assertThat(ProvidersPanel.fallbackBlankProviderBaseUrl(
                "  http://127.0.0.1:1234/v1  ",
                "http://localhost:1234/v1"
        )).isEqualTo("  http://127.0.0.1:1234/v1  ");
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-providers-panel.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
