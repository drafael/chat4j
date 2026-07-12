package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import java.nio.file.Path;
import java.util.Arrays;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class ProvidersPanelTest {

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() {
        CredentialResolver.configureTokenVault(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        CredentialResolver.init(emptyMap());
    }

    @AfterEach
    void tearDown() {
        CredentialResolver.configureTokenVault(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        CredentialResolver.init(emptyMap());
    }

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
    @DisplayName("Provider credential refresh hides stale missing token guidance")
    void refreshProviderCredentialUi_whenTokenWasSaved_hidesMissingTokenInfoPanel() {
        var subject = new ProvidersPanel(new SettingsRepository(tempDir.resolve("providers.properties")));
        var statusLabel = new JLabel();
        var missingTokenInfoPanel = new JPanel();
        char[] token = "saved-token".toCharArray();
        try {
            CredentialResolver.saveTokenOverride("OPENAI_API_KEY", token);
        } finally {
            Arrays.fill(token, '\0');
        }

        subject.refreshProviderCredentialUi(
                statusLabel,
                "OpenAI",
                ProvidersPanel.ProviderInfo.envVar("OPENAI_API_KEY", "https://api.openai.com/v1"),
                missingTokenInfoPanel
        );

        assertThat(statusLabel.getText()).contains("Saved token configured");
        assertThat(missingTokenInfoPanel.isVisible()).isFalse();
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
