package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialResolverTest {

    @AfterEach
    void tearDown() {
        CredentialResolver.init(Map.of());
    }

    @Test
    @DisplayName("Resolver returns value from loaded shell environment when variable is not present in process environment")
    void getenv_whenVariableExistsOnlyInShellEnv_returnsShellValue() {
        var envVar = "CHAT4J_TEST_%s".formatted(UUID.randomUUID());
        CredentialResolver.init(Map.of(envVar, "test-value"));

        var resolved = CredentialResolver.getenv(envVar);

        assertThat(resolved).isEqualTo("test-value");
    }

    @Test
    @DisplayName("Resolver prefers process environment values over loaded shell environment values")
    void getenv_whenVariableExistsInProcessEnv_returnsProcessValue() {
        var envVar = firstPresentEnvVar();
        var processValue = System.getenv(envVar);
        CredentialResolver.init(Map.of(envVar, "shell-override-value"));

        var resolved = CredentialResolver.getenv(envVar);

        assertThat(resolved).isEqualTo(processValue);
    }

    @Test
    @DisplayName("Merged environment gives precedence to loaded shell variables for subprocesses")
    void mergedEnvironment_whenShellProvidesOverrides_returnsShellOverrideValues() {
        CredentialResolver.init(Map.of("PATH", "/tmp/chat4j-path", "CHAT4J_FLAG", "yes"));

        Map<String, String> merged = CredentialResolver.mergedEnvironment();

        assertThat(merged)
                .containsEntry("PATH", "/tmp/chat4j-path")
                .containsEntry("CHAT4J_FLAG", "yes");
    }

    @Test
    @DisplayName("Provider credential presence check returns true when a known API key exists in loaded shell environment")
    void hasAnyProviderCredentials_whenKnownProviderKeyExistsInShellEnv_returnsTrue() {
        CredentialResolver.init(Map.of("OPENAI_API_KEY", "sk-test"));

        var hasCredentials = CredentialResolver.hasAnyProviderCredentials();

        assertThat(hasCredentials).isTrue();
    }

    @Test
    @DisplayName("Provider credential presence check includes Google AI aliases")
    void hasAnyProviderCredentials_whenGoogleAiAliasExists_returnsTrue() {
        CredentialResolver.init(Map.of("GOOGLEAI_API_KEY", "google-test"));

        var hasCredentials = CredentialResolver.hasAnyProviderCredentials();

        assertThat(hasCredentials).isTrue();
    }

    @Test
    @DisplayName("Required credentials check returns true when no environment variable is required")
    void hasRequiredCredentials_whenEnvVarIsNull_returnsTrue() {
        var hasCredentials = CredentialResolver.hasRequiredCredentials(null);

        assertThat(hasCredentials).isTrue();
    }

    @Test
    @DisplayName("Required credentials check returns false when required variable is missing")
    void hasRequiredCredentials_whenRequiredVariableIsMissing_returnsFalse() {
        var envVar = "CHAT4J_TEST_%s".formatted(UUID.randomUUID());

        var hasCredentials = CredentialResolver.hasRequiredCredentials(envVar);

        assertThat(hasCredentials).isFalse();
    }

    @Test
    @DisplayName("Required API key resolution uses loaded shell environment when process environment is missing")
    void resolveRequiredApiKey_whenKeyExistsOnlyInShellEnv_returnsShellValue() {
        var envVar = "CHAT4J_TEST_%s".formatted(UUID.randomUUID());
        CredentialResolver.init(Map.of(envVar, "shell-api-key"));

        var resolved = CredentialResolver.resolveRequiredApiKey(envVar, "fallback-key");

        assertThat(resolved).isEqualTo("shell-api-key");
    }

    @Test
    @DisplayName("Required API key resolution uses fallback value when environment variable name is null")
    void resolveRequiredApiKey_whenEnvVarIsNull_returnsFallbackValue() {
        var resolved = CredentialResolver.resolveRequiredApiKey(null, "ollama");

        assertThat(resolved).isEqualTo("ollama");
    }

    @Test
    @DisplayName("Required API key resolution supports fallback environment variable aliases")
    void resolveRequiredApiKey_whenAliasVariableExists_returnsAliasValue() {
        var primary = "CHAT4J_TEST_PRIMARY_%s".formatted(UUID.randomUUID());
        var alias = "CHAT4J_TEST_ALIAS_%s".formatted(UUID.randomUUID());
        CredentialResolver.init(Map.of(alias, "alias-key"));

        var resolved = CredentialResolver.resolveRequiredApiKey("%s|%s".formatted(primary, alias), null);

        assertThat(resolved).isEqualTo("alias-key");
    }

    @Test
    @DisplayName("Required credentials check supports fallback environment variable aliases")
    void hasRequiredCredentials_whenAliasVariableExists_returnsTrue() {
        var primary = "CHAT4J_TEST_PRIMARY_%s".formatted(UUID.randomUUID());
        var alias = "CHAT4J_TEST_ALIAS_%s".formatted(UUID.randomUUID());
        CredentialResolver.init(Map.of(alias, "alias-key"));

        var hasCredentials = CredentialResolver.hasRequiredCredentials("%s|%s".formatted(primary, alias));

        assertThat(hasCredentials).isTrue();
    }

    @Test
    @DisplayName("Required API key resolution throws when required environment variable is missing")
    void resolveRequiredApiKey_whenRequiredVariableIsMissing_throwsIllegalStateException() {
        var envVar = "CHAT4J_TEST_%s".formatted(UUID.randomUUID());

        assertThatThrownBy(() -> CredentialResolver.resolveRequiredApiKey(envVar, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("%s not set".formatted(envVar));
    }

    @Test
    @DisplayName("Loaded shell environment is copied defensively before source map mutation")
    void init_whenSourceMapMutatesLater_returnsOriginalLoadedValues() {
        var envVar = "CHAT4J_TEST_%s".formatted(UUID.randomUUID());
        var source = new HashMap<String, String>();
        source.put(envVar, "initial-value");

        CredentialResolver.init(source);
        source.put(envVar, "mutated-value");

        var resolved = CredentialResolver.getenv(envVar);

        assertThat(resolved).isEqualTo("initial-value");
    }

    private static String firstPresentEnvVar() {
        return List.of("PATH", "HOME", "USER").stream()
                .filter(name -> {
                    var value = System.getenv(name);
                    return StringUtils.isNotBlank(value);
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No stable process environment variable available for test"));
    }
}
