package com.github.drafael.chat4j.mcp;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpConfigurationValidatorTest {

    @Test
    @DisplayName("Credential row identities are unique across transports and servers")
    void validate_whenCredentialRowsCollideAcrossConfiguration_rejectsConfiguration() {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration first = server(List.of(new McpSecretReference(rowId, "Authorization", "")));
        McpServerConfiguration second = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Second",
                "server_two",
                false,
                false,
                McpTransportType.STDIO,
                "",
                "java",
                emptyList(),
                emptyList(),
                List.of(new McpSecretReference(rowId, "TOKEN", "")),
                false,
                emptySet()
        );

        assertThatThrownBy(() -> McpConfigurationValidator.validate(new McpConfiguration(1, List.of(first, second))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("row IDs");
    }

    @Test
    @DisplayName("Secret references must use the exact MCP vault identifier format")
    void validate_whenSecretReferenceHasOnlyPrefix_rejectsConfiguration() {
        McpServerConfiguration configured = server(List.of(new McpSecretReference(
                UUID.randomUUID().toString(),
                "Authorization",
                "MCP_short"
        )));

        assertThatThrownBy(() -> McpConfigurationValidator.validate(new McpConfiguration(1, List.of(configured))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret reference");
    }

    private McpServerConfiguration server(List<McpSecretReference> headers) {
        return new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Server",
                "server_one",
                false,
                false,
                McpTransportType.STREAMABLE_HTTP,
                "https://example.test/mcp",
                "",
                emptyList(),
                headers,
                emptyList(),
                false,
                emptySet()
        );
    }
}
