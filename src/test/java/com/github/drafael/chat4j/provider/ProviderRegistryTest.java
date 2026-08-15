package com.github.drafael.chat4j.provider;

import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry.ProviderRuntimeConfig;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderRegistryTest {

    @TempDir
    private Path tempDir;

    private ProviderRegistry subject;

    @BeforeEach
    void setUp() {
        subject = registry("");
    }

    @Test
    @DisplayName("Runtime config can disable Ollama from available provider list")
    void availableProviders_whenOllamaIsDisabled_returnsProvidersWithoutOllama() {
        subject.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRuntimeConfig(false, "http://localhost:11434/v1")
        ));

        assertThat(subject.availableProviders())
                .noneMatch(providerDef -> "Ollama".equals(providerDef.name()));
    }

    @Test
    @DisplayName("Runtime config base URL override is reflected in provider definition and factory")
    void availableProviders_whenBaseUrlIsOverridden_returnsProviderWithOverriddenBaseUrl() throws Exception {
        String overriddenBaseUrl = "http://127.0.0.1:22445/v1";
        subject.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRuntimeConfig(true, overriddenBaseUrl)
        ));

        ProviderRegistry.ProviderDef ollama = subject.availableProviders().stream()
                .filter(providerDef -> "Ollama".equals(providerDef.name()))
                .findFirst()
                .orElseThrow();

        assertThat(ollama.baseUrl()).isEqualTo(overriddenBaseUrl);

        Object provider = ollama.factory().create("llama3.2:latest");
        assertThat(readProviderBaseUrl(provider)).isEqualTo(overriddenBaseUrl);
    }

    @Test
    @DisplayName("Runtime config base URL override is trimmed before provider and factory use")
    void availableProviders_whenBaseUrlOverrideHasWhitespace_returnsTrimmedProviderAndFactoryBaseUrl() throws Exception {
        String overriddenBaseUrl = "http://127.0.0.1:22445/v1";
        subject.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRuntimeConfig(true, "  %s  ".formatted(overriddenBaseUrl))
        ));

        ProviderRegistry.ProviderDef ollama = subject.availableProviders().stream()
                .filter(providerDef -> "Ollama".equals(providerDef.name()))
                .findFirst()
                .orElseThrow();

        assertThat(ollama.baseUrl()).isEqualTo(overriddenBaseUrl);
        assertThat(readProviderBaseUrl(ollama.factory().create("llama3.2:latest"))).isEqualTo(overriddenBaseUrl);
    }

    @Test
    @DisplayName("Anthropic provider definition uses root API URL without v1 suffix")
    void allProviders_whenAnthropicIsAvailable_returnsAnthropicWithRootApiUrl() {
        assertThat(subject.allProviders())
                .filteredOn(providerDef -> "Anthropic".equals(providerDef.name()))
                .singleElement()
                .extracting(ProviderRegistry.ProviderDef::baseUrl)
                .isEqualTo("https://api.anthropic.com");
    }

    @Test
    @DisplayName("Together becomes available with its environment credential and default endpoint")
    void availableProviders_whenTogetherCredentialExists_returnsTogetherProvider() {
        var togetherRegistry = registry("together-", Map.of("TOGETHER_API_KEY", "secret"));

        assertThat(togetherRegistry.availableProviders())
                .filteredOn(provider -> "Together".equals(provider.name()))
                .singleElement()
                .satisfies(provider -> {
                    assertThat(provider.envVar()).isEqualTo("TOGETHER_API_KEY");
                    assertThat(provider.baseUrl()).isEqualTo("https://api.together.ai/v1");
                    assertThat(provider.seedModels()).isEmpty();
                });
    }

    @Test
    @DisplayName("Separate provider registries keep runtime configuration isolated")
    void applyRuntimeConfig_whenRegistriesAreIndependent_doesNotChangeOtherRegistry() {
        var other = registry("other-");
        subject.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRuntimeConfig(false, "http://localhost:11434/v1")
        ));

        assertThat(subject.availableProviders()).noneMatch(provider -> "Ollama".equals(provider.name()));
        assertThat(other.availableProviders()).anyMatch(provider -> "Ollama".equals(provider.name()));
    }

    private ProviderRegistry registry(String prefix) {
        return registry(prefix, emptyMap());
    }

    private ProviderRegistry registry(String prefix, Map<String, String> processEnvironment) {
        ApiTokenVault vault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDir.resolve("%scredentials".formatted(prefix))));
        CredentialResolver credentialResolver = new CredentialResolver(vault, processEnvironment, emptyMap());
        var attachmentAuthority = ProviderAttachmentTestSupport.authority();
        return new ProviderRegistry(
                new CopilotAuthResolver(tempDir.resolve("%scopilot-home".formatted(prefix)), emptyMap(), HttpClient.newHttpClient()),
                new CodexAuthResolver(tempDir.resolve("%scodex-home".formatted(prefix)), emptyMap(), HttpClient.newHttpClient()),
                new CopilotModelMetadataStore(tempDir.resolve("%smetadata".formatted(prefix))),
                credentialResolver,
                emptyMap(),
                attachmentAuthority
        );
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static String readProviderBaseUrl(Object provider) throws Exception {
        try {
            Object value = readField(provider, "baseUrl");
            return value == null ? null : value.toString();
        } catch (NoSuchFieldException e) {
            Object runtime = readField(provider, "runtime");
            Method baseUrl = runtime.getClass().getDeclaredMethod("baseUrl");
            baseUrl.setAccessible(true);
            Object value = baseUrl.invoke(runtime);
            return value == null ? null : value.toString();
        }
    }
}
