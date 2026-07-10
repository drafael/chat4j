package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.ProviderRuntimeSettings;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderRuntimeSettingsResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Resolve uses provider defaults when settings are missing")
    void resolve_whenSettingsMissing_usesProviderDefaults() throws Exception {
        var settingsRepo = settingsRepo("resolver-defaults");
        var subject = new ProviderRuntimeSettingsResolver(settingsRepo);
        var provider = provider("OpenAI", "https://api.openai.com/v1");

        ProviderRegistry.ProviderRuntimeConfig config = subject.resolve(provider);

        assertThat(config.enabled()).isTrue();
        assertThat(config.baseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    @DisplayName("Resolve uses configured enabled flag and base URL")
    void resolve_whenSettingsConfigured_usesConfiguredValues() throws Exception {
        var settingsRepo = settingsRepo("resolver-configured");
        ProviderRuntimeSettings openAiSettings = ProviderRuntimeSettings.forProvider(settingsRepo, "OpenAI");
        settingsRepo.put(openAiSettings.enabledKey(), "false");
        settingsRepo.put(openAiSettings.baseUrlKey(), "https://gateway.example/v1");

        var subject = new ProviderRuntimeSettingsResolver(settingsRepo);
        var provider = provider("OpenAI", "https://api.openai.com/v1");

        ProviderRegistry.ProviderRuntimeConfig config = subject.resolve(provider);

        assertThat(config.enabled()).isFalse();
        assertThat(config.baseUrl()).isEqualTo("https://gateway.example/v1");
    }

    @Test
    @DisplayName("Resolve falls back to default URL when configured URL is blank")
    void resolve_whenConfiguredBaseUrlIsBlank_usesProviderDefaultBaseUrl() throws Exception {
        var settingsRepo = settingsRepo("resolver-blank-url");
        settingsRepo.put(ProviderRuntimeSettings.forProvider(settingsRepo, "OpenAI").baseUrlKey(), "   ");

        var subject = new ProviderRuntimeSettingsResolver(settingsRepo);
        var provider = provider("OpenAI", "https://api.openai.com/v1");

        ProviderRegistry.ProviderRuntimeConfig config = subject.resolve(provider);

        assertThat(config.baseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    @DisplayName("Resolve all returns empty map for null or empty provider lists")
    void resolveAll_whenProvidersAreMissing_returnsEmptyMap() {
        var subject = new ProviderRuntimeSettingsResolver(settingsRepo("resolver-empty"));

        assertThat(subject.resolveAll(null)).isEmpty();
        assertThat(subject.resolveAll(emptyList())).isEmpty();
    }

    @Test
    @DisplayName("Resolve all returns config entries keyed by provider name")
    void resolveAll_whenProvidersProvided_returnsConfigMapByProviderName() throws Exception {
        var settingsRepo = settingsRepo("resolver-all");
        settingsRepo.put(ProviderRuntimeSettings.forProvider(settingsRepo, "OpenAI").enabledKey(), "false");

        var subject = new ProviderRuntimeSettingsResolver(settingsRepo);
        var openAi = provider("OpenAI", "https://api.openai.com/v1");
        var anthropic = provider("Anthropic", "https://api.anthropic.com");

        Map<String, ProviderRegistry.ProviderRuntimeConfig> configs = subject.resolveAll(List.of(openAi, anthropic));

        assertThat(configs).hasSize(2);
        assertThat(configs.get("OpenAI").enabled()).isFalse();
        assertThat(configs.get("Anthropic").enabled()).isTrue();
    }

    @Test
    @DisplayName("Resolve preserves whitespace around non-blank base URLs")
    void resolve_whenConfiguredBaseUrlHasWhitespace_preservesStoredValue() throws Exception {
        var settingsRepo = settingsRepo("resolver-whitespace-url");
        settingsRepo.put(ProviderRuntimeSettings.forProvider(settingsRepo, "OpenAI").baseUrlKey(), "  https://gateway.example/v1  ");

        var subject = new ProviderRuntimeSettingsResolver(settingsRepo);
        var provider = provider("OpenAI", "https://api.openai.com/v1");

        ProviderRegistry.ProviderRuntimeConfig config = subject.resolve(provider);

        assertThat(config.baseUrl()).isEqualTo("  https://gateway.example/v1  ");
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }

    private ProviderRegistry.ProviderDef provider(String name, String baseUrl) {
        return new ProviderRegistry.ProviderDef(
                name,
                "API_KEY",
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> null,
                () -> emptyList()
        );
    }
}
