package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRuntimeSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Provider runtime settings create backward-compatible keys")
    void keys_whenProviderNamesVary_matchPersistedSettingNames() {
        assertThat(settings("OpenAI").enabledKey()).isEqualTo("chat4j.provider.openai.enabled");
        assertThat(settings("OpenAI").baseUrlKey()).isEqualTo("chat4j.provider.openai.baseUrl");
        assertThat(settings("LM Studio").enabledKey()).isEqualTo("chat4j.provider.lm-studio.enabled");
        assertThat(settings("LM Studio").baseUrlKey()).isEqualTo("chat4j.provider.lm-studio.baseUrl");
        assertThat(settings("OpenAI Codex").enabledKey()).isEqualTo("chat4j.provider.openai-codex.enabled");
        assertThat(settings("!!!").baseUrlKey()).isEqualTo("chat4j.provider.unknown.baseUrl");
    }

    @Test
    @DisplayName("Provider runtime settings read enabled values with existing parse semantics")
    void enabled_whenStoredValuesVary_usesBooleanParseBoolean() {
        SettingsRepository settingsRepo = settingsRepo("provider-runtime-enabled");
        ProviderRuntimeSettings subject = ProviderRuntimeSettings.forProvider(settingsRepo, "OpenAI");

        settingsRepo.put(subject.enabledKey(), "not-a-boolean");

        assertThat(subject.enabled(true)).isFalse();
    }

    @Test
    @DisplayName("Provider runtime settings preserve non-blank base URL whitespace")
    void baseUrl_whenStoredValueHasWhitespace_preservesStoredValue() {
        SettingsRepository settingsRepo = settingsRepo("provider-runtime-base-url");
        ProviderRuntimeSettings subject = ProviderRuntimeSettings.forProvider(settingsRepo, "OpenAI");
        settingsRepo.put(subject.baseUrlKey(), "  https://gateway.example/v1  ");

        assertThat(subject.baseUrl("https://api.openai.com/v1")).isEqualTo("  https://gateway.example/v1  ");
    }

    @Test
    @DisplayName("Provider runtime settings fall back for blank base URLs and repository failures")
    void baseUrl_whenBlankOrReadFailure_returnsDefaultValue() {
        SettingsRepository settingsRepo = settingsRepo("provider-runtime-blank-base-url");
        ProviderRuntimeSettings blankSubject = ProviderRuntimeSettings.forProvider(settingsRepo, "OpenAI");
        settingsRepo.put(blankSubject.baseUrlKey(), "   ");

        assertThat(blankSubject.baseUrl("https://api.openai.com/v1")).isEqualTo("https://api.openai.com/v1");
        assertThat(ProviderRuntimeSettings.forProvider(new ThrowingSettingsRepo(), "OpenAI").baseUrl("default"))
                .isEqualTo("default");
    }

    private ProviderRuntimeSettings settings(String providerName) {
        return ProviderRuntimeSettings.forProvider(settingsRepo("keys-%s".formatted(providerName)), providerName);
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-provider-runtime-settings.properties"));
        }

        @Override
        public String get(String key, String defaultValue) {
            throw new IllegalStateException("forced failure");
        }
    }
}
