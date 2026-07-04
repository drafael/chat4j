package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderAvailabilityResolverTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Model selection is enabled for non-local providers")
    void isModelSelectionEnabled_whenProviderIsNotHealthGated_returnsTrue() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("provider-availability-non-local");
        var probe = new FakeLocalServiceHealthProbe();
        var subject = new ProviderAvailabilityResolver(settingsRepo, probe);

        boolean enabled = subject.isModelSelectionEnabled(provider("OpenAI", "https://api.openai.com/v1"));

        assertThat(enabled).isTrue();
        assertThat(probe.nonBlockingCalls).isEmpty();
    }

    @Test
    @DisplayName("Model selection for local providers uses non-blocking health probe")
    void isModelSelectionEnabled_whenProviderIsHealthGated_usesNonBlockingProbe() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("provider-availability-local");
        var probe = new FakeLocalServiceHealthProbe();
        probe.nonBlockingResultByUrl.put("http://localhost:11434/v1", true);
        var subject = new ProviderAvailabilityResolver(settingsRepo, probe);

        boolean enabled = subject.isModelSelectionEnabled(provider("Ollama", "http://localhost:11434/v1"));

        assertThat(enabled).isTrue();
        assertThat(probe.nonBlockingCalls).containsExactly("http://localhost:11434/v1");
    }

    @Test
    @DisplayName("Menu availability uses configured base URLs for local providers")
    void resolveMenuAvailability_whenBaseUrlConfigured_usesConfiguredUrlForReachability() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("provider-availability-configured");
        settingsRepo.put(SettingsKeys.providerBaseUrlKey("Ollama"), "http://127.0.0.1:11434/v1");

        var probe = new FakeLocalServiceHealthProbe();
        probe.blockingResultByUrl.put("http://127.0.0.1:11434/v1", true);
        probe.blockingResultByUrl.put("http://localhost:1234/v1", false);

        var subject = new ProviderAvailabilityResolver(settingsRepo, probe);

        Map<String, Boolean> availability = subject.resolveMenuAvailability(List.of(
                provider("Ollama", "http://localhost:11434/v1"),
                provider("LM Studio", "http://localhost:1234/v1"),
                provider("OpenAI", "https://api.openai.com/v1")
        ));

        assertThat(availability).containsEntry("Ollama", true);
        assertThat(availability).containsEntry("LM Studio", false);
        assertThat(probe.blockingCalls).contains("http://127.0.0.1:11434/v1", "http://localhost:1234/v1");
    }

    @Test
    @DisplayName("Menu availability falls back to default base URL when configured value is blank")
    void resolveMenuAvailability_whenConfiguredBaseUrlBlank_usesDefaultBaseUrl() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("provider-availability-blank");
        settingsRepo.put(SettingsKeys.providerBaseUrlKey("LM Studio"), "   ");

        var probe = new FakeLocalServiceHealthProbe();
        probe.blockingResultByUrl.put("http://localhost:1234/v1", true);
        probe.blockingResultByUrl.put("http://localhost:11434/v1", false);

        var subject = new ProviderAvailabilityResolver(settingsRepo, probe);

        Map<String, Boolean> availability = subject.resolveMenuAvailability(List.of(
                provider("LM Studio", "http://localhost:1234/v1"),
                provider("Ollama", "http://localhost:11434/v1")
        ));

        assertThat(availability).containsEntry("LM Studio", true);
        assertThat(probe.blockingCalls).contains("http://localhost:1234/v1");
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

    private static class FakeLocalServiceHealthProbe implements ProviderAvailabilityResolver.LocalServiceHealthProbe {

        private final List<String> blockingCalls = new ArrayList<>();
        private final List<String> nonBlockingCalls = new ArrayList<>();
        private final Map<String, Boolean> blockingResultByUrl = new LinkedHashMap<>();
        private final Map<String, Boolean> nonBlockingResultByUrl = new LinkedHashMap<>();

        @Override
        public boolean isReachable(String baseUrl) {
            blockingCalls.add(baseUrl);
            return blockingResultByUrl.getOrDefault(baseUrl, false);
        }

        @Override
        public boolean isReachableNonBlocking(String baseUrl) {
            nonBlockingCalls.add(baseUrl);
            return nonBlockingResultByUrl.getOrDefault(baseUrl, false);
        }
    }
}
