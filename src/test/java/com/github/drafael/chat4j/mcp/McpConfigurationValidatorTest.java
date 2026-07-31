package com.github.drafael.chat4j.mcp;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpConfigurationValidatorTest {

    @Test
    @DisplayName("Credential row identity collisions expose a general validation failure")
    void validate_whenCredentialRowsCollideAcrossConfiguration_exposesGeneralMetadata() {
        String rowId = UUID.randomUUID().toString();
        McpServerConfiguration first = httpServer(List.of(new McpSecretReference(rowId, "Authorization", "")));
        McpServerConfiguration second = stdioServer(
                List.of(new McpSecretReference(rowId, "TOKEN", "")),
                emptySet()
        );

        assertValidationFailure(
                new McpConfiguration(1, List.of(first, second)),
                "MCP credential row IDs must be nonblank and unique.",
                McpConfigurationValidator.ValidationCategory.GENERAL,
                ""
        );
    }

    @Test
    @DisplayName("Invalid secret references identify the responsible HTTP-header table")
    void validate_whenSecretReferenceHasOnlyPrefix_exposesHeaderMetadata() {
        McpServerConfiguration configured = httpServer(List.of(new McpSecretReference(
                UUID.randomUUID().toString(),
                "Authorization",
                "MCP_short"
        )));

        assertValidationFailure(
                new McpConfiguration(1, List.of(configured)),
                "Invalid MCP secret reference.",
                McpConfigurationValidator.ValidationCategory.HTTP_HEADERS,
                configured.id()
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serverLocalFailures")
    @DisplayName("Server-local validation failures expose typed presentation metadata")
    void validate_whenServerLocalFailureOccurs_exposesResponsibleControl(
            String description,
            McpServerConfiguration server,
            String message,
            McpConfigurationValidator.ValidationCategory category
    ) {
        assertValidationFailure(new McpConfiguration(1, List.of(server)), message, category, server.id());
    }

    @Test
    @DisplayName("General validation failures remain unassigned to an individual server")
    void validate_whenVersionIsUnsupported_exposesGeneralMetadata() {
        assertValidationFailure(
                new McpConfiguration(99, emptyList()),
                "Unsupported MCP configuration version: 99",
                McpConfigurationValidator.ValidationCategory.GENERAL,
                ""
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("generalFailures")
    @DisplayName("Cross-server and structural failures remain general")
    void validate_whenGeneralFailureOccurs_hasNoResponsibleServer(
            String description,
            McpConfiguration configuration,
            String message
    ) {
        assertValidationFailure(
                configuration,
                message,
                McpConfigurationValidator.ValidationCategory.GENERAL,
                ""
        );
    }

    @Test
    @DisplayName("Credential validation still runs before endpoint validation")
    void validate_whenCredentialAndEndpointAreInvalid_reportsCredentialFirst() {
        McpServerConfiguration base = httpServer(List.of(new McpSecretReference(
                UUID.randomUUID().toString(),
                "bad header",
                ""
        )));
        McpServerConfiguration configured = copyWithEndpoint(base, "not a url");

        assertValidationFailure(
                new McpConfiguration(1, List.of(configured)),
                "Invalid MCP header name: bad header",
                McpConfigurationValidator.ValidationCategory.HTTP_HEADERS,
                configured.id()
        );
    }

    @Test
    @DisplayName("A valid configuration remains accepted")
    void validate_whenConfigurationIsValid_doesNotThrow() {
        McpConfiguration configuration = new McpConfiguration(1, List.of(
                httpServer(emptyList()),
                stdioServer(emptyList(), emptySet())
        ));

        assertThatCode(() -> McpConfigurationValidator.validate(configuration)).doesNotThrowAnyException();
    }

    private static Stream<Arguments> generalFailures() {
        McpServerConfiguration malformedId = copyWithId(httpServer(emptyList()), "not-a-uuid");
        McpServerConfiguration duplicateIdFirst = httpServer(emptyList());
        McpServerConfiguration duplicateIdSecond = copyWithId(
                copyWithModelId(httpServer(emptyList()), "different_model"),
                duplicateIdFirst.id()
        );
        McpServerConfiguration duplicateModelFirst = copyWithModelId(httpServer(emptyList()), "Shared_Model");
        McpServerConfiguration duplicateModelSecond = copyWithModelId(httpServer(emptyList()), "shared_model");
        McpServerConfiguration longRunningHttp = copyWithLongRunning(httpServer(emptyList()), true);
        return Stream.of(
                Arguments.of(
                        "malformed server ID",
                        new McpConfiguration(1, List.of(malformedId)),
                        "MCP server ID must be a UUID."
                ),
                Arguments.of(
                        "duplicate server ID",
                        new McpConfiguration(1, List.of(duplicateIdFirst, duplicateIdSecond)),
                        "MCP server IDs must be unique."
                ),
                Arguments.of(
                        "case-insensitive model ID collision",
                        new McpConfiguration(1, List.of(duplicateModelFirst, duplicateModelSecond)),
                        "MCP model IDs must be unique."
                ),
                Arguments.of(
                        "long-running HTTP",
                        new McpConfiguration(1, List.of(longRunningHttp)),
                        "Long-running mode is available only for stdio MCP servers."
                )
        );
    }

    private static Stream<Arguments> serverLocalFailures() {
        McpServerConfiguration invalidModel = copyWithModelId(httpServer(emptyList()), "bad model");
        McpServerConfiguration invalidEndpoint = copyWithEndpoint(httpServer(emptyList()), "relative");
        McpServerConfiguration invalidExecutable = copyWithExecutable(stdioServer(emptyList(), emptySet()), "");
        McpServerConfiguration invalidHeader = httpServer(List.of(new McpSecretReference(
                UUID.randomUUID().toString(),
                "bad header",
                ""
        )));
        McpServerConfiguration invalidEnvironment = stdioServer(List.of(new McpSecretReference(
                UUID.randomUUID().toString(),
                "bad environment",
                ""
        )), emptySet());
        McpServerConfiguration invalidTool = stdioServer(emptyList(), Set.of(""));
        return Stream.of(
                Arguments.of(
                        "model ID",
                        invalidModel,
                        "MCP model ID must contain only letters, digits, underscores, or hyphens.",
                        McpConfigurationValidator.ValidationCategory.MODEL_ID
                ),
                Arguments.of(
                        "endpoint",
                        invalidEndpoint,
                        "MCP endpoint must be an absolute HTTP or HTTPS URL.",
                        McpConfigurationValidator.ValidationCategory.ENDPOINT
                ),
                Arguments.of(
                        "executable",
                        invalidExecutable,
                        "MCP executable must not be blank.",
                        McpConfigurationValidator.ValidationCategory.EXECUTABLE
                ),
                Arguments.of(
                        "HTTP headers",
                        invalidHeader,
                        "Invalid MCP header name: bad header",
                        McpConfigurationValidator.ValidationCategory.HTTP_HEADERS
                ),
                Arguments.of(
                        "environment",
                        invalidEnvironment,
                        "Invalid MCP environment name: bad environment",
                        McpConfigurationValidator.ValidationCategory.ENVIRONMENT
                ),
                Arguments.of(
                        "tools",
                        invalidTool,
                        "Disabled MCP tool names must not be blank.",
                        McpConfigurationValidator.ValidationCategory.TOOLS
                )
        );
    }

    private static void assertValidationFailure(
            McpConfiguration configuration,
            String message,
            McpConfigurationValidator.ValidationCategory category,
            String responsibleServerId
    ) {
        assertThatThrownBy(() -> McpConfigurationValidator.validate(configuration))
                .isInstanceOfSatisfying(McpConfigurationValidator.ValidationException.class, error -> {
                    assertThat(error).hasMessage(message);
                    assertThat(error.category()).isEqualTo(category);
                    assertThat(error.responsibleServerId()).isEqualTo(responsibleServerId);
                })
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static McpServerConfiguration httpServer(List<McpSecretReference> headers) {
        return new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "HTTP Server",
                "http_server_%s".formatted(UUID.randomUUID().toString().substring(0, 8)),
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

    private static McpServerConfiguration stdioServer(
            List<McpSecretReference> environment,
            Set<String> disabledTools
    ) {
        return new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "STDIO Server",
                "stdio_server_%s".formatted(UUID.randomUUID().toString().substring(0, 8)),
                false,
                false,
                McpTransportType.STDIO,
                "",
                "java",
                emptyList(),
                emptyList(),
                environment,
                false,
                disabledTools
        );
    }

    private static McpServerConfiguration copyWithId(McpServerConfiguration server, String id) {
        return new McpServerConfiguration(
                id, server.name(), server.modelId(), server.enabled(), server.automatic(), server.transport(),
                server.endpoint(), server.executable(), server.arguments(), server.headers(), server.environment(),
                server.longRunning(), server.disabledTools()
        );
    }

    private static McpServerConfiguration copyWithLongRunning(McpServerConfiguration server, boolean longRunning) {
        return new McpServerConfiguration(
                server.id(), server.name(), server.modelId(), server.enabled(), server.automatic(), server.transport(),
                server.endpoint(), server.executable(), server.arguments(), server.headers(), server.environment(),
                longRunning, server.disabledTools()
        );
    }

    private static McpServerConfiguration copyWithModelId(McpServerConfiguration server, String modelId) {
        return new McpServerConfiguration(
                server.id(), server.name(), modelId, server.enabled(), server.automatic(), server.transport(),
                server.endpoint(), server.executable(), server.arguments(), server.headers(), server.environment(),
                server.longRunning(), server.disabledTools()
        );
    }

    private static McpServerConfiguration copyWithEndpoint(McpServerConfiguration server, String endpoint) {
        return new McpServerConfiguration(
                server.id(), server.name(), server.modelId(), server.enabled(), server.automatic(), server.transport(),
                endpoint, server.executable(), server.arguments(), server.headers(), server.environment(),
                server.longRunning(), server.disabledTools()
        );
    }

    private static McpServerConfiguration copyWithExecutable(McpServerConfiguration server, String executable) {
        return new McpServerConfiguration(
                server.id(), server.name(), server.modelId(), server.enabled(), server.automatic(), server.transport(),
                server.endpoint(), executable, server.arguments(), server.headers(), server.environment(),
                server.longRunning(), server.disabledTools()
        );
    }

}
