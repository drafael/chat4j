package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.StoragePaths;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSecretVaultTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("MCP records remain isolated from provider credential APIs")
    void publish_whenMcpRecordIsStored_keepsProviderAndMcpNamespacesIsolated() {
        var tokenVault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDirectory));
        var subject = new McpSecretVault(tokenVault);
        String secretId = "MCP_0123456789ABCDEF0123456789ABCDEF";
        char[] value = "secret-value".toCharArray();

        subject.publish(Map.of(secretId, value), emptySet());

        assertThat(subject.secretIds()).containsExactly(secretId);
        try (var lookup = subject.lookup(secretId)) {
            assertThat(lookup.present()).isTrue();
            assertThat(lookup.token()).containsExactly(value);
            assertThat(lookup.toString()).doesNotContain("secret-value");
        }
        try (var providerLookup = tokenVault.readTokenChars(secretId)) {
            assertThat(providerLookup.present()).isFalse();
            assertThat(providerLookup.errorMessage()).contains("Unsupported");
        }
        assertThatThrownBy(() -> tokenVault.hasRecord(secretId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MCP removal cannot remove provider credential records")
    void remove_whenProviderRecordExists_leavesProviderRecordUntouched() {
        var tokenVault = new ApiTokenVault(StoragePaths.ofConfigHome(tempDirectory));
        char[] providerValue = "provider-value".toCharArray();
        tokenVault.applyTokenMutation("OPENAI_API_KEY", emptyList(), providerValue);
        var subject = new McpSecretVault(tokenVault);

        subject.remove(emptySet());

        assertThat(tokenVault.hasRecord("OPENAI_API_KEY")).isTrue();
        assertThat(subject.secretIds()).isEmpty();
    }
}
