package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Collections.emptyMap;

class CredentialResolverTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        CredentialResolver.configureTokenVault(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        CredentialResolver.configureProcessEnv(System::getenv);
        CredentialResolver.init(emptyMap());
    }

    @AfterEach
    void tearDown() {
        CredentialResolver.configureProcessEnv(System::getenv);
        CredentialResolver.configureTokenVault(new ApiTokenVault(StoragePaths.ofConfigHome(tempDir)));
        CredentialResolver.init(emptyMap());
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
        CredentialResolver.init(Map.of("OPENAI_API_KEY", "DUMMY_OPENAI_KEY_FOR_TESTS"));

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
    @DisplayName("Provider credential presence check includes AssemblyAI")
    void hasAnyProviderCredentials_whenAssemblyAiKeyExists_returnsTrue() {
        CredentialResolver.init(Map.of("ASSEMBLYAI_API_KEY", "assemblyai-test"));

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
    @DisplayName("Saved token overrides process, shell, and fallback credentials")
    void resolveRequiredApiKey_whenSavedTokenExists_returnsSavedToken() {
        CredentialResolver.configureProcessEnv(name -> "OPENAI_API_KEY".equals(name) ? "process-key" : null);
        CredentialResolver.init(Map.of("OPENAI_API_KEY", "shell-key"));
        CredentialResolver.saveTokenOverride("OPENAI_API_KEY", "saved-key".toCharArray());

        var resolved = CredentialResolver.resolveRequiredApiKey("OPENAI_API_KEY", "fallback-key");

        assertThat(resolved).isEqualTo("saved-key");
        assertThat(CredentialResolver.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.SAVED_TOKEN);
    }

    @Test
    @DisplayName("Saved token read errors fail closed instead of falling back to process, shell, or fallback credentials")
    void resolveRequiredApiKey_whenSavedTokenMasterKeyMissing_failsClosed() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        CredentialResolver.configureProcessEnv(name -> "OPENAI_API_KEY".equals(name) ? "process-key" : null);
        CredentialResolver.init(Map.of("OPENAI_API_KEY", "shell-key"));
        CredentialResolver.saveTokenOverride("OPENAI_API_KEY", "saved-key".toCharArray());
        Files.delete(storagePaths.tokenVaultMasterKeyFile());
        CredentialResolver.configureTokenVault(new ApiTokenVault(storagePaths));

        CredentialResolution resolution = CredentialResolver.resolveCredential("OPENAI_API_KEY", "fallback-key");

        assertThat(resolution.source()).isEqualTo(ApiCredentialSource.ERROR);
        assertThat(resolution.hasValue()).isFalse();
        assertThat(CredentialResolver.hasRequiredCredentials("OPENAI_API_KEY")).isFalse();
        assertThatThrownBy(() -> CredentialResolver.resolveRequiredApiKey("OPENAI_API_KEY", "fallback-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Saved API token for OPENAI_API_KEY could not be read")
                .hasMessageNotContaining("process-key")
                .hasMessageNotContaining("shell-key")
                .hasMessageNotContaining("fallback-key");
    }

    @Test
    @DisplayName("Saved token corrupt master key errors fail closed instead of falling back to raw credentials")
    void resolveRequiredApiKey_whenSavedTokenMasterKeyCorrupt_failsClosed() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        CredentialResolver.configureProcessEnv(name -> "OPENAI_API_KEY".equals(name) ? "process-key" : null);
        CredentialResolver.init(Map.of("OPENAI_API_KEY", "shell-key"));
        CredentialResolver.saveTokenOverride("OPENAI_API_KEY", "saved-key".toCharArray());
        Files.writeString(storagePaths.tokenVaultMasterKeyFile(), "not-base64");
        CredentialResolver.configureTokenVault(new ApiTokenVault(storagePaths));

        CredentialResolution resolution = CredentialResolver.resolveCredential("OPENAI_API_KEY", "fallback-key");

        assertThat(resolution.source()).isEqualTo(ApiCredentialSource.ERROR);
        assertThat(resolution.hasValue()).isFalse();
        assertThat(CredentialResolver.hasRequiredCredentials("OPENAI_API_KEY")).isFalse();
        assertThatThrownBy(() -> CredentialResolver.resolveRequiredApiKey("OPENAI_API_KEY", "fallback-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Saved API token for OPENAI_API_KEY could not be read")
                .hasMessageNotContaining("process-key")
                .hasMessageNotContaining("shell-key")
                .hasMessageNotContaining("fallback-key");
    }

    @Test
    @DisplayName("Saved token decrypt errors fail closed instead of falling back to raw credentials")
    void resolveRequiredApiKey_whenSavedTokenCannotDecrypt_failsClosed() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        CredentialResolver.configureProcessEnv(name -> "GROQ_API_KEY".equals(name) ? "process-key" : null);
        CredentialResolver.init(Map.of("GROQ_API_KEY", "shell-key"));
        CredentialResolver.saveTokenOverride("OPENAI_API_KEY", "saved-key".toCharArray());
        String json = Files.readString(storagePaths.tokenVaultFile())
                .replace("OPENAI_API_KEY", "GROQ_API_KEY");
        Files.writeString(storagePaths.tokenVaultFile(), json);
        CredentialResolver.configureTokenVault(new ApiTokenVault(storagePaths));

        CredentialResolution resolution = CredentialResolver.resolveCredential("GROQ_API_KEY", "fallback-key");

        assertThat(resolution.source()).isEqualTo(ApiCredentialSource.ERROR);
        assertThat(resolution.hasValue()).isFalse();
        assertThat(CredentialResolver.hasRequiredCredentials("GROQ_API_KEY")).isFalse();
        assertThatThrownBy(() -> CredentialResolver.resolveRequiredApiKey("GROQ_API_KEY", "fallback-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Saved API token for GROQ_API_KEY could not be read")
                .hasMessageNotContaining("process-key")
                .hasMessageNotContaining("shell-key")
                .hasMessageNotContaining("fallback-key");
    }

    @Test
    @DisplayName("Blank process environment does not mask shell environment")
    void resolveRequiredApiKey_whenProcessEnvIsBlank_returnsShellValue() {
        CredentialResolver.configureProcessEnv(name -> "OPENAI_API_KEY".equals(name) ? "  " : null);
        CredentialResolver.init(Map.of("OPENAI_API_KEY", "shell-key"));

        var resolved = CredentialResolver.resolveRequiredApiKey("OPENAI_API_KEY", null);

        assertThat(resolved).isEqualTo("shell-key");
    }

    @Test
    @DisplayName("Merged environment does not include saved tokens")
    void mergedEnvironment_whenSavedTokenExists_doesNotExposeSavedToken() {
        CredentialResolver.saveTokenOverride("OPENAI_API_KEY", "saved-secret".toCharArray());

        Map<String, String> merged = CredentialResolver.mergedEnvironment();

        assertThat(merged).doesNotContainEntry("OPENAI_API_KEY", "saved-secret");
    }

    @Test
    @DisplayName("Alias save normalizes to primary token id and clears secondary alias records")
    void saveTokenOverride_whenAliasExpressionUsed_savesPrimaryAndClearsAlias() {
        CredentialResolver.saveTokenOverride("GOOGLEAI_API_KEY", "old-alias".toCharArray());

        CredentialResolver.saveTokenOverride("GEMINI_API_KEY|GOOGLEAI_API_KEY", "new-primary".toCharArray());

        assertThat(CredentialResolver.resolveRequiredApiKey("GEMINI_API_KEY|GOOGLEAI_API_KEY", null))
                .isEqualTo("new-primary");
        assertThat(CredentialResolver.hasSavedTokenRecord("GEMINI_API_KEY")).isTrue();
        assertThat(CredentialResolver.hasSavedTokenRecord("GOOGLEAI_API_KEY")).isFalse();
    }

    @Test
    @DisplayName("Saving a token matching a lower-priority shell alias still writes an override")
    void saveTokenOverride_whenTokenMatchesLowerPriorityShellAlias_savesOverride() {
        CredentialResolver.configureProcessEnv(name -> "GEMINI_API_KEY".equals(name) ? "process-primary" : null);
        CredentialResolver.init(Map.of("GOOGLEAI_API_KEY", "shell-alias"));

        CredentialResolver.SaveTokenResult result = CredentialResolver.saveTokenOverride(
                "GEMINI_API_KEY|GOOGLEAI_API_KEY",
                "shell-alias".toCharArray()
        );

        assertThat(result.savedOverride()).isTrue();
        assertThat(result.changed()).isTrue();
        assertThat(CredentialResolver.resolveCredential("GEMINI_API_KEY|GOOGLEAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.SAVED_TOKEN);
        assertThat(CredentialResolver.resolveRequiredApiKey("GEMINI_API_KEY|GOOGLEAI_API_KEY", null))
                .isEqualTo("shell-alias");
    }

    @Test
    @DisplayName("Saving a token matching a lower-priority process alias still writes an override")
    void saveTokenOverride_whenTokenMatchesLowerPriorityProcessAlias_savesOverride() {
        CredentialResolver.configureProcessEnv(name -> switch (name) {
            case "GEMINI_API_KEY" -> "process-primary";
            case "GOOGLEAI_API_KEY" -> "process-alias";
            default -> null;
        });

        CredentialResolver.SaveTokenResult result = CredentialResolver.saveTokenOverride(
                "GEMINI_API_KEY|GOOGLEAI_API_KEY",
                "process-alias".toCharArray()
        );

        assertThat(result.savedOverride()).isTrue();
        assertThat(result.changed()).isTrue();
        assertThat(CredentialResolver.resolveRequiredApiKey("GEMINI_API_KEY|GOOGLEAI_API_KEY", null))
                .isEqualTo("process-alias");
    }

    @Test
    @DisplayName("Saving a token matching the effective raw environment does not persist a redundant override")
    void saveTokenOverride_whenTokenMatchesEffectiveRawCredential_doesNotSaveOverride() {
        CredentialResolver.configureProcessEnv(name -> "GEMINI_API_KEY".equals(name) ? "process-primary" : null);
        CredentialResolver.init(Map.of("GOOGLEAI_API_KEY", "shell-alias"));

        CredentialResolver.SaveTokenResult result = CredentialResolver.saveTokenOverride(
                "GEMINI_API_KEY|GOOGLEAI_API_KEY",
                "process-primary".toCharArray()
        );

        assertThat(result.savedOverride()).isFalse();
        assertThat(result.changed()).isFalse();
        assertThat(CredentialResolver.hasSavedTokenRecord("GEMINI_API_KEY")).isFalse();
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
