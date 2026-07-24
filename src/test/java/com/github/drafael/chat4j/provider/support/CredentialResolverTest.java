package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialResolverTest {

    @TempDir
    Path tempDir;

    private StoragePaths storagePaths;
    private ApiTokenVault vault;

    @BeforeEach
    void setUp() {
        storagePaths = StoragePaths.ofConfigHome(tempDir);
        vault = new ApiTokenVault(storagePaths);
    }

    @Test
    @DisplayName("Known provider credentials are detected in the shell snapshot")
    void hasAnyProviderCredentials_whenKnownProviderKeyExistsInShellEnv_returnsTrue() {
        var subject = resolver(emptyMap(), Map.of("OPENAI_API_KEY", "shell-key"));

        assertThat(subject.hasAnyProviderCredentials()).isTrue();
    }

    @Test
    @DisplayName("Canonical Google credentials resolve through the legacy alias expression")
    void resolveRequiredApiKey_whenOnlyCanonicalAliasIsConfigured_returnsCanonicalValue() {
        var subject = resolver(Map.of("GEMINI_API_KEY", "gemini-key"), emptyMap());

        String result = subject.resolveRequiredApiKey("GOOGLEAI_API_KEY", null);

        assertThat(result).isEqualTo("gemini-key");
    }

    @Test
    @DisplayName("No required variable is treated as credential-ready")
    void hasRequiredCredentials_whenEnvVarIsNull_returnsTrue() {
        var subject = resolver(emptyMap(), emptyMap());

        assertThat(subject.hasRequiredCredentials(null)).isTrue();
    }

    @Test
    @DisplayName("Missing required credentials are rejected")
    void resolveRequiredApiKey_whenRequiredVariableIsMissing_throwsIllegalStateException() {
        var subject = resolver(emptyMap(), emptyMap());

        assertThatThrownBy(() -> subject.resolveRequiredApiKey("OPENAI_API_KEY", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENAI_API_KEY not set");
    }

    @Test
    @DisplayName("Saved token overrides process, shell, and fallback credentials")
    void resolveRequiredApiKey_whenSavedTokenExists_returnsSavedToken() {
        CredentialTestSupport.saveToken(vault, "OPENAI_API_KEY", "saved-key".toCharArray());
        var subject = resolver(
                Map.of("OPENAI_API_KEY", "process-key"),
                Map.of("OPENAI_API_KEY", "shell-key")
        );

        var resolved = subject.resolveRequiredApiKey("OPENAI_API_KEY", "fallback-key");

        assertThat(resolved).isEqualTo("saved-key");
        assertThat(subject.resolveCredentialStatus("OPENAI_API_KEY", null).source())
                .isEqualTo(ApiCredentialSource.SAVED_TOKEN);
    }

    @Test
    @DisplayName("Clearing a saved token reveals process, shell, then fallback values")
    void resolveRequiredApiKey_whenSavedTokenCleared_revealsFallbackChain() {
        CredentialTestSupport.saveToken(vault, "OPENAI_API_KEY", "saved-key".toCharArray());
        CredentialTestSupport.deleteToken(vault, "OPENAI_API_KEY");

        assertThat(resolver(Map.of("OPENAI_API_KEY", "process"), Map.of("OPENAI_API_KEY", "shell"))
                .resolveRequiredApiKey("OPENAI_API_KEY", "fallback")).isEqualTo("process");
        assertThat(resolver(emptyMap(), Map.of("OPENAI_API_KEY", "shell"))
                .resolveRequiredApiKey("OPENAI_API_KEY", "fallback")).isEqualTo("shell");
        assertThat(resolver(emptyMap(), emptyMap())
                .resolveRequiredApiKey("OPENAI_API_KEY", "fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("Blank process values do not mask shell credentials")
    void resolveRequiredApiKey_whenProcessEnvIsBlank_returnsShellValue() {
        var subject = resolver(
                Map.of("OPENAI_API_KEY", "  "),
                Map.of("OPENAI_API_KEY", "shell-key")
        );

        assertThat(subject.resolveRequiredApiKey("OPENAI_API_KEY", null)).isEqualTo("shell-key");
    }

    @Test
    @DisplayName("Missing master key fails closed instead of using raw credentials")
    void resolveRequiredApiKey_whenSavedTokenMasterKeyMissing_failsClosed() throws Exception {
        CredentialTestSupport.saveToken(vault, "OPENAI_API_KEY", "saved-key".toCharArray());
        Files.delete(storagePaths.tokenVaultMasterKeyFile());
        var subject = new CredentialResolver(
                new ApiTokenVault(storagePaths),
                Map.of("OPENAI_API_KEY", "process-key"),
                Map.of("OPENAI_API_KEY", "shell-key")
        );

        CredentialResolution resolution = subject.resolveCredential("OPENAI_API_KEY", "fallback-key");

        assertThat(resolution.source()).isEqualTo(ApiCredentialSource.ERROR);
        assertThat(subject.hasRequiredCredentials("OPENAI_API_KEY")).isFalse();
        assertThatThrownBy(() -> subject.resolveRequiredApiKey("OPENAI_API_KEY", "fallback-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Saved API token for OPENAI_API_KEY could not be read")
                .hasMessageNotContaining("process-key")
                .hasMessageNotContaining("shell-key")
                .hasMessageNotContaining("fallback-key");
    }

    @Test
    @DisplayName("Corrupt master key fails closed instead of using raw credentials")
    void resolveRequiredApiKey_whenSavedTokenMasterKeyCorrupt_failsClosed() throws Exception {
        CredentialTestSupport.saveToken(vault, "OPENAI_API_KEY", "saved-key".toCharArray());
        Files.writeString(storagePaths.tokenVaultMasterKeyFile(), "not-base64");
        var subject = new CredentialResolver(
                new ApiTokenVault(storagePaths),
                Map.of("OPENAI_API_KEY", "process-key"),
                Map.of("OPENAI_API_KEY", "shell-key")
        );

        assertThat(subject.resolveCredential("OPENAI_API_KEY", "fallback-key").source())
                .isEqualTo(ApiCredentialSource.ERROR);
    }

    @Test
    @DisplayName("Credential snapshots are copied defensively")
    void constructor_whenSourceMapMutatesLater_returnsOriginalValues() {
        var shellEnvironment = new HashMap<String, String>();
        shellEnvironment.put("OPENAI_API_KEY", "initial-value");
        var subject = resolver(emptyMap(), shellEnvironment);

        shellEnvironment.put("OPENAI_API_KEY", "mutated-value");

        assertThat(subject.resolveRequiredApiKey("OPENAI_API_KEY", null)).isEqualTo("initial-value");
    }

    @Test
    @DisplayName("Independent resolver assemblies do not share environment or vault state")
    void constructor_whenTwoAssembliesExist_keepsStateIsolated() {
        Path secondHome = tempDir.resolve("second");
        var first = resolver(Map.of("OPENAI_API_KEY", "first"), emptyMap());
        var second = new CredentialResolver(
                new ApiTokenVault(StoragePaths.ofConfigHome(secondHome)),
                Map.of("OPENAI_API_KEY", "second"),
                emptyMap()
        );

        assertThat(first.resolveRequiredApiKey("OPENAI_API_KEY", null)).isEqualTo("first");
        assertThat(second.resolveRequiredApiKey("OPENAI_API_KEY", null)).isEqualTo("second");
    }

    private CredentialResolver resolver(Map<String, String> processEnvironment, Map<String, String> shellEnvironment) {
        return new CredentialResolver(vault, processEnvironment, shellEnvironment);
    }
}
