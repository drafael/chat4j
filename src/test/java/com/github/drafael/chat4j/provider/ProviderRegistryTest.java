package com.github.drafael.chat4j.provider;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Collections.emptyMap;

class ProviderRegistryTest {

    @AfterEach
    void tearDown() {
        ProviderRegistry.applyRuntimeConfig(emptyMap());
    }

    @Test
    @DisplayName("Runtime config can disable Ollama from available provider list")
    void availableProviders_whenOllamaIsDisabled_returnsProvidersWithoutOllama() {
        ProviderRegistry.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRegistry.ProviderRuntimeConfig(false, "http://localhost:11434/v1")
        ));

        assertThat(ProviderRegistry.availableProviders())
                .noneMatch(providerDef -> "Ollama".equals(providerDef.name()));
    }

    @Test
    @DisplayName("Runtime config base URL override is reflected in provider definition and factory")
    void availableProviders_whenBaseUrlIsOverridden_returnsProviderWithOverriddenBaseUrl() throws Exception {
        String overriddenBaseUrl = "http://127.0.0.1:22445/v1";
        ProviderRegistry.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRegistry.ProviderRuntimeConfig(true, overriddenBaseUrl)
        ));

        ProviderRegistry.ProviderDef ollama = ProviderRegistry.availableProviders().stream()
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
        ProviderRegistry.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRegistry.ProviderRuntimeConfig(true, "  %s  ".formatted(overriddenBaseUrl))
        ));

        ProviderRegistry.ProviderDef ollama = ProviderRegistry.availableProviders().stream()
                .filter(providerDef -> "Ollama".equals(providerDef.name()))
                .findFirst()
                .orElseThrow();

        assertThat(ollama.baseUrl()).isEqualTo(overriddenBaseUrl);
        assertThat(readProviderBaseUrl(ollama.factory().create("llama3.2:latest"))).isEqualTo(overriddenBaseUrl);
    }

    @Test
    @DisplayName("Anthropic provider definition uses root API URL without v1 suffix")
    void availableProviders_whenAnthropicIsAvailable_returnsAnthropicWithRootApiUrl() {
        assertThat(ProviderRegistry.allProviders())
                .filteredOn(providerDef -> "Anthropic".equals(providerDef.name()))
                .singleElement()
                .extracting(ProviderRegistry.ProviderDef::baseUrl)
                .isEqualTo("https://api.anthropic.com");
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
