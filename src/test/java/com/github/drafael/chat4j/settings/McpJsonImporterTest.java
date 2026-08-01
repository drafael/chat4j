package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.mcp.McpTransportType;
import com.github.drafael.chat4j.provider.support.CredentialStoragePolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.CharBuffer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.fill;
import static java.util.Arrays.stream;
import static java.util.Objects.deepEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpJsonImporterTest {

    private final McpJsonImporter subject = new McpJsonImporter();

    @Test
    @DisplayName("The three documented wrappers normalize one server")
    void parse_whenDocumentUsesSupportedWrappers_normalizesOneServer() throws Exception {
        try (var mcpServers = subject.parse("""
                {"mcpServers":{"context7":{"command":"npx","args":["-y","pkg"]}}}
                """)) {
            assertThat(mcpServers.name()).isEqualTo("context7");
            assertThat(mcpServers.transport()).isEqualTo(McpTransportType.STDIO);
            assertThat(mcpServers.arguments()).containsExactly("-y", "pkg");
        }
        try (var servers = subject.parse("""
                {"servers":{"docs":{"type":"http","url":"https://example.test/mcp"}}}
                """)) {
            assertThat(servers.name()).isEqualTo("docs");
            assertThat(servers.transport()).isEqualTo(McpTransportType.STREAMABLE_HTTP);
        }
        try (var mcp = subject.parse("""
                {"mcp":{"local":{"type":"local","command":["npx","-y","pkg"]}}}
                """)) {
            assertThat(mcp.name()).isEqualTo("local");
            assertThat(mcp.executable()).isEqualTo("npx");
            assertThat(mcp.arguments()).containsExactly("-y", "pkg");
        }
    }

    @Test
    @DisplayName("Bare objects use their name or the safe fallback")
    void parse_whenDocumentIsBare_usesNameOrFallback() throws Exception {
        try (var named = subject.parse("{\"name\":\"  Local docs  \",\"command\":\"npx\"}")) {
            assertThat(named.name()).isEqualTo("Local docs");
        }
        try (var unnamed = subject.parse("{\"command\":\"npx\"}")) {
            assertThat(unnamed.name()).isEqualTo("Imported server");
        }
    }

    @Test
    @DisplayName("Blank, scalar, array, and null roots are rejected safely")
    void parse_whenRootIsNotAUsableObject_rejectsSafely() {
        assertReason(" ", McpJsonImporter.ImportErrorReason.EMPTY_INPUT);
        assertReason("null", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
        assertReason("[]", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
        assertReason("true", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
    }

    @Test
    @DisplayName("Wrappers require exactly one object-valued server")
    void parse_whenWrapperIsInvalid_rejectsSafely() {
        assertReason("{\"mcpServers\":null}", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
        assertReason("{\"mcpServers\":[]}", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
        assertReason("{\"mcpServers\":true}", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
        assertReason("{\"mcpServers\":{}}", McpJsonImporter.ImportErrorReason.SINGLE_SERVER_REQUIRED);
        assertReason(
                "{\"mcpServers\":{\"one\":{},\"two\":{}}}",
                McpJsonImporter.ImportErrorReason.SINGLE_SERVER_REQUIRED
        );
        assertReason("{\"mcpServers\":{\"one\":null}}", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
        assertReason("{\"mcpServers\":{\"one\":[]}}", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
    }

    @Test
    @DisplayName("Recognized wrapper keys are counted by presence")
    void parse_whenMultipleWrappersArePresent_rejectsWithoutFallback() {
        assertReason(
                "{\"mcpServers\":null,\"servers\":{}}",
                McpJsonImporter.ImportErrorReason.MULTIPLE_WRAPPERS
        );
    }

    @Test
    @DisplayName("Wrapped member names remain authoritative")
    void parse_whenWrappedServerHasInnerName_ignoresInnerName() throws Exception {
        for (String innerName : List.of("\"inner\"", "null", "42", "true", "[]", "{}")) {
            try (var result = subject.parse("""
                    {"mcpServers":{"outer":{"name":%s,"command":"npx"}}}
                    """.formatted(innerName))) {
                assertThat(result.name()).isEqualTo("outer");
                assertThat(result.warnings()).containsExactly(McpJsonImporter.ImportWarning.SOURCE_METADATA_IGNORED);
            }
        }
        assertReason(
                "{\"name\":null,\"command\":\"npx\"}",
                McpJsonImporter.ImportErrorReason.EXPECTED_STRING
        );
    }

    @Test
    @DisplayName("Only documented wrapper siblings are accepted")
    void parse_whenWrapperHasSiblings_appliesClosedGrammar() throws Exception {
        try (var result = subject.parse("""
                {"$schema":{},"sandbox":true,"inputs":[],"mcpServers":{"one":{"command":"npx"}}}
                """)) {
            assertThat(result.warnings()).containsExactly(
                    McpJsonImporter.ImportWarning.SOURCE_AUTHORITY_IGNORED,
                    McpJsonImporter.ImportWarning.SOURCE_METADATA_IGNORED
            );
        }
        assertReason(
                "{\"unexpected\":true,\"mcpServers\":{\"one\":{\"command\":\"npx\"}}}",
                McpJsonImporter.ImportErrorReason.UNKNOWN_FIELD
        );
    }

    @Test
    @DisplayName("JSONC comments and trailing commas are accepted while duplicate and trailing content are rejected")
    void parse_whenJsonUsesConfiguredDialect_enforcesExactDialect() throws Exception {
        try (var result = subject.parse("""
                // one server
                {"command":"npx", /* comment */}
                """)) {
            assertThat(result.executable()).isEqualTo("npx");
        }
        assertReason(
                "{\"command\":\"npx\",\"command\":\"node\"}",
                McpJsonImporter.ImportErrorReason.DUPLICATE_KEY
        );
        assertReason(
                "{\"command\":\"npx\"} {}",
                McpJsonImporter.ImportErrorReason.TRAILING_CONTENT
        );
        assertReason("{'command':'npx'}", McpJsonImporter.ImportErrorReason.MALFORMED_JSON);
        assertReason("{command:\"npx\"}", McpJsonImporter.ImportErrorReason.MALFORMED_JSON);
        assertReason("# yaml\n{\"command\":\"npx\"}", McpJsonImporter.ImportErrorReason.MALFORMED_JSON);
    }

    @Test
    @DisplayName("Document, scalar, name, number, and nesting limits enforce exact boundaries")
    void parse_whenParserBoundaryIsReached_enforcesExactLimits() throws Exception {
        String base = "{\"command\":\"npx\"}";
        String exactDocument = base + " ".repeat(McpJsonImporter.MAX_DOCUMENT_LENGTH - base.length());
        try (var result = subject.parse(exactDocument)) {
            assertThat(result.executable()).isEqualTo("npx");
        }
        assertReason(
                exactDocument + " ",
                McpJsonImporter.ImportErrorReason.INPUT_TOO_LARGE
        );

        String exactString = "v".repeat(256 * 1024);
        try (var result = subject.parse("{\"command\":\"npx\",\"description\":\"%s\"}".formatted(exactString))) {
            assertThat(result.warnings()).containsExactly(McpJsonImporter.ImportWarning.SOURCE_METADATA_IGNORED);
        }
        assertReason(
                "{\"command\":\"npx\",\"description\":\"%sv\"}".formatted(exactString),
                McpJsonImporter.ImportErrorReason.JSON_LIMIT_EXCEEDED
        );

        String exactPropertyName = "p".repeat(256 * 1024);
        assertReason(
                "{\"command\":\"npx\",\"%s\":true}".formatted(exactPropertyName),
                McpJsonImporter.ImportErrorReason.UNKNOWN_FIELD
        );
        assertReason(
                "{\"command\":\"npx\",\"%sx\":true}".formatted(exactPropertyName),
                McpJsonImporter.ImportErrorReason.JSON_LIMIT_EXCEEDED
        );

        String exactNumber = "9".repeat(1_000);
        try (var result = subject.parse("{\"command\":\"npx\",\"timeout\":%s}".formatted(exactNumber))) {
            assertThat(result.warnings()).containsExactly(McpJsonImporter.ImportWarning.SOURCE_METADATA_IGNORED);
        }
        assertReason(
                "{\"command\":\"npx\",\"timeout\":%s9}".formatted(exactNumber),
                McpJsonImporter.ImportErrorReason.JSON_LIMIT_EXCEEDED
        );

        String acceptedNesting = "{\"command\":\"npx\",\"description\":"
                + "[".repeat(63) + "]".repeat(63) + "}";
        try (var result = subject.parse(acceptedNesting)) {
            assertThat(result.warnings()).containsExactly(McpJsonImporter.ImportWarning.SOURCE_METADATA_IGNORED);
        }
        String excessiveNesting = "{\"command\":\"npx\",\"description\":"
                + "[".repeat(64) + "]".repeat(64) + "}";
        assertReason(excessiveNesting, McpJsonImporter.ImportErrorReason.JSON_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("String commands remain one executable and arguments retain exact order")
    void parse_whenStdioUsesStringCommand_preservesExecutableAndArguments() throws Exception {
        try (var result = subject.parse("""
                {"type":"stdio","transportType":"local","command":"  /opt/my command  ","args":[" a ","${TOKEN}"]}
                """)) {
            assertThat(result.executable()).isEqualTo("  /opt/my command  ");
            assertThat(result.arguments()).containsExactly(" a ", "${TOKEN}");
            assertThat(result.warnings()).containsExactly(
                    McpJsonImporter.ImportWarning.ARGUMENT_PLACEHOLDER_PRESERVED
            );
        }
    }

    @Test
    @DisplayName("Invalid command and argument combinations are rejected")
    void parse_whenCommandShapeIsInvalid_rejectsSafely() {
        assertReason("{\"command\":[]}", McpJsonImporter.ImportErrorReason.COMMAND_REQUIRED);
        assertReason("{\"command\":[\"npx\"],\"args\":[]}", McpJsonImporter.ImportErrorReason.CONFLICTING_FIELDS);
        assertReason("{\"command\":null}", McpJsonImporter.ImportErrorReason.EXPECTED_STRING);
        assertReason("{\"command\":\"npx\",\"args\":null}", McpJsonImporter.ImportErrorReason.EXPECTED_ARRAY);
        assertReason("{\"command\":\"npx\",\"args\":[1]}", McpJsonImporter.ImportErrorReason.EXPECTED_STRING);
        assertReason("{\"command\":\"npx\",\"url\":null}", McpJsonImporter.ImportErrorReason.CONFLICTING_FIELDS);
    }

    @Test
    @DisplayName("Executable and argument NUL diagnostics retain their source paths")
    void parse_whenCommandContainsNul_reportsExactSourcePath() {
        assertFailure(
                "{\"command\":\"np\\u0000x\"}",
                McpJsonImporter.ImportErrorReason.INVALID_CHARACTER,
                "$.server.command"
        );
        assertFailure(
                "{\"command\":\"npx\",\"args\":[\"ok\",\"bad\\u0000arg\"]}",
                McpJsonImporter.ImportErrorReason.INVALID_CHARACTER,
                "$.server.args.<entry 2>"
        );
        assertFailure(
                "{\"command\":[\"npx\",\"bad\\u0000arg\"]}",
                McpJsonImporter.ImportErrorReason.INVALID_CHARACTER,
                "$.server.command.<entry 2>"
        );
    }

    @Test
    @DisplayName("Stdio credentials retain encounter order and enforce aliases")
    void parse_whenStdioContainsEnvironment_normalizesCredentialsInOrder() throws Exception {
        try (var result = subject.parse("""
                {"command":"npx","environment":{"FIRST":"one","SECOND":"two"}}
                """)) {
            var credentials = result.transferCredentials();
            try {
                assertThat(credentials.stream().map(McpJsonImporter.ImportedCredential::key))
                        .containsExactly("FIRST", "SECOND");
            } finally {
                credentials.forEach(McpJsonImporter.ImportedCredential::wipe);
            }
        }
        assertReason(
                "{\"command\":\"npx\",\"env\":{},\"environment\":{}}",
                McpJsonImporter.ImportErrorReason.CONFLICTING_FIELDS
        );
        assertReason("{\"command\":\"npx\",\"env\":null}", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
        assertReason("{\"command\":\"npx\",\"env\":[]}", McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT);
        assertReason(
                "{\"command\":\"npx\",\"env\":{\"TOKEN\":null}}",
                McpJsonImporter.ImportErrorReason.EXPECTED_STRING
        );
        assertReason(
                "{\"command\":\"npx\",\"env\":{\"TOKEN\":42}}",
                McpJsonImporter.ImportErrorReason.EXPECTED_STRING
        );
    }

    @Test
    @DisplayName("Transport-specific fields cannot be silently ignored")
    void parse_whenFieldsBelongToWrongTransport_rejectsSafely() {
        assertReason(
                "{\"command\":\"npx\",\"headers\":{}}",
                McpJsonImporter.ImportErrorReason.CONFLICTING_FIELDS
        );
        assertReason(
                "{\"url\":\"https://example.test\",\"args\":[]}",
                McpJsonImporter.ImportErrorReason.CONFLICTING_FIELDS
        );
        assertReason(
                "{\"url\":\"https://example.test\",\"env\":{}}",
                McpJsonImporter.ImportErrorReason.CONFLICTING_FIELDS
        );
    }

    @Test
    @DisplayName("HTTP endpoint aliases and transport spellings normalize to Streamable HTTP")
    void parse_whenHttpUsesSupportedAliases_normalizesTransport() throws Exception {
        for (String alias : List.of("url", "httpUrl", "serverUrl")) {
            for (String label : List.of("http", "streamable-http", "streamableHttp", "streamable", "remote")) {
                try (var result = subject.parse("{\"type\":\"%s\",\"%s\":\" https://example.test/mcp \"}"
                        .formatted(label, alias))) {
                    assertThat(result.transport()).isEqualTo(McpTransportType.STREAMABLE_HTTP);
                    assertThat(result.endpoint()).isEqualTo(" https://example.test/mcp ");
                }
            }
        }
        for (String label : List.of("stdio", "local", " STDIO ")) {
            try (var result = subject.parse("{\"transportType\":\"%s\",\"command\":\"npx\"}"
                    .formatted(label))) {
                assertThat(result.transport()).isEqualTo(McpTransportType.STDIO);
            }
        }
        assertReason(
                "{\"type\":\"http\",\"transportType\":\"local\",\"url\":\"https://example.test\"}",
                McpJsonImporter.ImportErrorReason.CONFLICTING_FIELDS
        );
        assertReason(
                "{\"type\":null,\"url\":\"https://example.test\"}",
                McpJsonImporter.ImportErrorReason.EXPECTED_STRING
        );
        assertReason(
                "{\"type\":\"sse\",\"url\":\"https://example.test\"}",
                McpJsonImporter.ImportErrorReason.UNSUPPORTED_TRANSPORT
        );
        assertReason(
                "{\"type\":\"websocket\",\"url\":\"https://example.test\"}",
                McpJsonImporter.ImportErrorReason.UNSUPPORTED_TRANSPORT
        );
        assertReason(
                "{\"url\":\"https://example.test\",\"httpUrl\":\"https://example.test\"}",
                McpJsonImporter.ImportErrorReason.CONFLICTING_FIELDS
        );
    }

    @Test
    @DisplayName("HTTP credentials require one object of string values")
    void parse_whenHttpHeadersHaveWrongShape_rejectsSafely() {
        assertReason(
                "{\"url\":\"https://example.test\",\"headers\":null}",
                McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT
        );
        assertReason(
                "{\"url\":\"https://example.test\",\"headers\":[]}",
                McpJsonImporter.ImportErrorReason.EXPECTED_OBJECT
        );
        assertReason(
                "{\"url\":\"https://example.test\",\"headers\":{\"X-Key\":null}}",
                McpJsonImporter.ImportErrorReason.EXPECTED_STRING
        );
        assertReason(
                "{\"url\":\"https://example.test\",\"headers\":{\"X-Key\":true}}",
                McpJsonImporter.ImportErrorReason.EXPECTED_STRING
        );
    }

    @Test
    @DisplayName("Credential prohibited characters are rejected without an owned result")
    void parse_whenCredentialContainsProhibitedCharacter_rejectsSafely() {
        assertReason(
                "{\"url\":\"https://example.test\",\"headers\":{\"Authorization\":\"line\\nvalue\"}}",
                McpJsonImporter.ImportErrorReason.INVALID_CHARACTER
        );
        assertReason(
                "{\"command\":\"npx\",\"env\":{\"TOKEN\":\"value\\u0000tail\"}}",
                McpJsonImporter.ImportErrorReason.INVALID_CHARACTER
        );
    }

    @Test
    @DisplayName("Exact placeholders become Missing while near misses remain owned values")
    void parse_whenCredentialsContainPlaceholders_classifiesExactMarkers() throws Exception {
        String environment64 = "A".repeat(64);
        String id64 = "a".repeat(64);
        List<String> placeholders = List.of(
                "YOUR_TOKEN",
                "YOUR_%s".formatted(environment64),
                "Bearer ${TOKEN}",
                "${%s}".formatted(environment64),
                "${TOKEN:-default value}",
                "${TOKEN:-%s}".formatted("d".repeat(256)),
                "${env:TOKEN}",
                "${env:%s}".formatted(environment64),
                "${input:key-id}",
                "${input:%s}".formatted(id64),
                "$TOKEN",
                "$%s".formatted(environment64),
                "%TOKEN%",
                "%%%s%%".formatted(environment64),
                "{env:TOKEN}",
                "{env:%s}".formatted(environment64),
                "<TOKEN>",
                "<%s>".formatted(id64),
                "   "
        );
        for (String placeholder : placeholders) {
            try (var result = subject.parse(credentialJson(placeholder))) {
                assertThat(result.missingCredentialCount()).isEqualTo(1);
            }
        }

        String environment65 = "A".repeat(65);
        String id65 = "a".repeat(65);
        String longDefault = "d".repeat(257);
        List<String> nearMisses = List.of(
                "your_TOKEN",
                "AYOUR_TOKEN",
                "YOUR_%s".formatted(environment65),
                "${}",
                "${1TOKEN}",
                "${%s}".formatted(environment65),
                "${TOKEN:-%s}".formatted(longDefault),
                "${ENV:TOKEN}",
                "${env:token-name}",
                "${env:%s}".formatted(environment65),
                "${input:}",
                "${input:.bad}",
                "${input:%s}".formatted(id65),
                "$1TOKEN",
                "$%s".formatted(environment65),
                "%TOKEN",
                "%1TOKEN%",
                "%%%s%%".formatted(environment65),
                "{ENV:TOKEN}",
                "{env:token-name}",
                "{env:%s}".formatted(environment65),
                "<bad token>",
                "<%s>".formatted(id65)
        );
        for (int index = 0; index < nearMisses.size(); index++) {
            String nearMiss = nearMisses.get(index);
            try (var result = subject.parse(credentialJson(nearMiss))) {
                assertThat(result.missingCredentialCount()).as("near-miss marker %d", index).isZero();
                List<McpJsonImporter.ImportedCredential> credentials = result.transferCredentials();
                char[] expected = nearMiss.toCharArray();
                try {
                    assertThat(deepEquals(credentials.getFirst().value(), expected))
                            .as("near-miss marker %d should remain exact", index)
                            .isTrue();
                } finally {
                    fill(expected, '\0');
                    credentials.forEach(McpJsonImporter.ImportedCredential::wipe);
                }
            }
        }
    }

    @Test
    @DisplayName("Argument placeholder detection preserves every argument literally")
    void parse_whenArgumentsContainPlaceholderMarkers_preservesLiteralArguments() throws Exception {
        List<String> arguments = List.of(
                "YOUR_TOKEN", "${TOKEN}", "${TOKEN:-default}", "${env:TOKEN}",
                "${input:key}", "$TOKEN", "%TOKEN%", "{env:TOKEN}", "<TOKEN>"
        );
        String jsonArguments = arguments.stream()
                .map(value -> "\"%s\"".formatted(value.replace("\\", "\\\\").replace("\"", "\\\"")))
                .reduce((first, second) -> "%s,%s".formatted(first, second))
                .orElse("");
        try (var result = subject.parse("{\"command\":\"npx\",\"args\":[%s]}".formatted(jsonArguments))) {
            assertThat(result.arguments()).containsExactlyElementsOf(arguments);
            assertThat(result.warnings()).containsExactly(
                    McpJsonImporter.ImportWarning.ARGUMENT_PLACEHOLDER_PRESERVED
            );
        }
    }

    @Test
    @DisplayName("Actual credentials must fit strict UTF-8 secure storage")
    void parse_whenCredentialExceedsStorageContract_rejectsBeforeAllocation() throws Exception {
        String exactAscii = "a".repeat(CredentialStoragePolicy.MAX_UTF8_BYTES);
        try (var result = subject.parse(credentialJson(exactAscii))) {
            List<McpJsonImporter.ImportedCredential> credentials = result.transferCredentials();
            try {
                assertThat(credentials.getFirst().value().length)
                        .isEqualTo(CredentialStoragePolicy.MAX_UTF8_BYTES);
            } finally {
                credentials.forEach(McpJsonImporter.ImportedCredential::wipe);
            }
        }
        assertFailure(
                credentialJson("a".repeat(CredentialStoragePolicy.MAX_UTF8_BYTES + 1)),
                McpJsonImporter.ImportErrorReason.IMPORTED_ENVIRONMENT_INVALID,
                "$.server.env.<entry 1>"
        );
        assertFailure(
                "{\"url\":\"https://example.test\",\"headers\":{\"X-Key\":\"%s\"}}"
                        .formatted("é".repeat(CredentialStoragePolicy.MAX_UTF8_BYTES / 2 + 1)),
                McpJsonImporter.ImportErrorReason.IMPORTED_HEADERS_INVALID,
                "$.server.headers.<entry 1>"
        );
        assertFailure(
                "{\"command\":\"npx\",\"env\":{\"TOKEN\":\"\\uD800\"}}",
                McpJsonImporter.ImportErrorReason.INVALID_CHARACTER,
                "$.server.env.<entry 1>"
        );
        assertFailure(
                "{\"command\":\"npx\",\"env\":{\"TOKEN\":\"\\uDC00\"}}",
                McpJsonImporter.ImportErrorReason.INVALID_CHARACTER,
                "$.server.env.<entry 1>"
        );
        try (var result = subject.parse(credentialJson("${TOKEN}" + "a".repeat(70 * 1024)))) {
            assertThat(result.missingCredentialCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Actual credential values retain nonblank whitespace exactly")
    void parse_whenCredentialIsActual_preservesCharacters() throws Exception {
        try (var result = subject.parse("{\"command\":\"npx\",\"env\":{\"TOKEN\":\"  value  \"}}")) {
            var credentials = result.transferCredentials();
            char[] expected = "  value  ".toCharArray();
            try {
                assertThat(deepEquals(credentials.getFirst().value(), expected))
                        .as("credential value should be preserved")
                        .isTrue();
            } finally {
                fill(expected, '\0');
                credentials.forEach(McpJsonImporter.ImportedCredential::wipe);
            }
        }
    }

    @Test
    @DisplayName("Warning evidence is deduplicated and rendered in fixed enum order")
    void parse_whenMultipleWarningFieldsArePresent_deduplicatesInFixedOrder() throws Exception {
        try (var http = subject.parse("""
                {"mcpServers":{"one":{"url":"https://example.test","enabled":true,"trust":true,"description":"x"}}}
                """)) {
            assertThat(http.warnings()).containsExactly(
                    McpJsonImporter.ImportWarning.SOURCE_AUTHORITY_IGNORED,
                    McpJsonImporter.ImportWarning.SOURCE_METADATA_IGNORED,
                    McpJsonImporter.ImportWarning.TYPELESS_URL_AS_HTTP
            );
            assertThat(http.warnings().stream().map(McpJsonImporter.ImportWarning::message)).containsExactly(
                    "Source approval, trust, enablement, or sandbox settings were ignored.",
                    "Source-specific metadata was ignored.",
                    "A typeless URL was treated as Streamable HTTP; SSE is unsupported."
            );
        }
        try (var stdio = subject.parse("""
                {"command":"npx","args":["${TOKEN}"],"disabled":false,"source":{}}
                """)) {
            assertThat(stdio.warnings()).containsExactly(
                    McpJsonImporter.ImportWarning.SOURCE_AUTHORITY_IGNORED,
                    McpJsonImporter.ImportWarning.SOURCE_METADATA_IGNORED,
                    McpJsonImporter.ImportWarning.ARGUMENT_PLACEHOLDER_PRESERVED
            );
        }
    }

    @Test
    @DisplayName("Ignored and unsupported fields follow the closed catalog")
    void parse_whenServerContainsSourceFields_appliesClosedCatalog() throws Exception {
        for (String field : List.of(
                "enabled", "disabled", "start_on_launch", "autoApprove", "alwaysAllow", "trust",
                "tools", "disabledTools", "includeTools", "excludeTools", "sandbox"
        )) {
            try (var result = subject.parse("{\"command\":\"npx\",\"%s\":null}".formatted(field))) {
                assertThat(result.warnings()).containsExactly(
                        McpJsonImporter.ImportWarning.SOURCE_AUTHORITY_IGNORED
                );
            }
        }
        for (String field : List.of(
                "description", "source", "timeout", "timeoutSeconds", "startupTimeout",
                "startupTimeoutMs", "startup_timeout_ms"
        )) {
            try (var result = subject.parse("{\"command\":\"npx\",\"%s\":null}".formatted(field))) {
                assertThat(result.warnings()).containsExactly(
                        McpJsonImporter.ImportWarning.SOURCE_METADATA_IGNORED
                );
            }
        }
        for (String field : List.of(
                "cwd", "workingDirectory", "working_directory", "envFile", "oauth", "oauthConfig",
                "auth", "authProvider", "authProviderType"
        )) {
            assertReason(
                    "{\"command\":\"npx\",\"%s\":null}".formatted(field),
                    McpJsonImporter.ImportErrorReason.UNSUPPORTED_FIELD
            );
        }
        assertReason("{\"command\":\"npx\",\"Enabled\":true}", McpJsonImporter.ImportErrorReason.UNKNOWN_FIELD);
    }

    @Test
    @DisplayName("Display and credential presentation bounds are enforced")
    void parse_whenPresentationBoundsAreReached_enforcesExactLimits() throws Exception {
        String exactName = "名".repeat(256);
        try (var result = subject.parse("{\"name\":\"%s\",\"command\":\"npx\"}".formatted(exactName))) {
            assertThat(result.name()).isEqualTo(exactName);
        }
        try (var result = subject.parse("{\"name\":\"   \",\"command\":\"npx\"}")) {
            assertThat(result.name()).isEqualTo("Imported server");
        }
        String longName = "a".repeat(257);
        assertReason(
                "{\"name\":\"%s\",\"command\":\"npx\"}".formatted(longName),
                McpJsonImporter.ImportErrorReason.INVALID_NAME
        );
        assertReason(
                "{\"name\":\"bad\\u202ename\",\"command\":\"npx\"}",
                McpJsonImporter.ImportErrorReason.INVALID_NAME
        );
        String exactCredential = "A".repeat(256);
        try (var result = subject.parse(
                "{\"command\":\"npx\",\"env\":{\"%s\":\"x\"}}".formatted(exactCredential)
        )) {
            assertThat(result.credentialDescriptors().getFirst().key()).isEqualTo(exactCredential);
        }
        String longCredential = "A".repeat(257);
        assertReason(
                "{\"command\":\"npx\",\"env\":{\"%s\":\"x\"}}".formatted(longCredential),
                McpJsonImporter.ImportErrorReason.INVALID_NAME
        );
        assertReason(
                "{\"command\":\"npx\",\"env\":{\"NÁME\":\"x\"}}",
                McpJsonImporter.ImportErrorReason.INVALID_NAME
        );
    }

    @Test
    @DisplayName("The warning catalog has fixed complete rendering order")
    void warningCatalog_whenRendered_usesFixedMessages() {
        assertThat(stream(McpJsonImporter.ImportWarning.values()).map(McpJsonImporter.ImportWarning::message))
                .containsExactly(
                        "Source approval, trust, enablement, or sandbox settings were ignored.",
                        "Source-specific metadata was ignored.",
                        "A typeless URL was treated as Streamable HTTP; SSE is unsupported.",
                        "Argument placeholders were preserved literally."
                );
    }

    @Test
    @DisplayName("Failures contain only fixed safe diagnostics")
    void parse_whenSourceIsInvalid_diagnosticDoesNotLeakSource() {
        String sentinel = "DO_NOT_PRINT_SENTINEL";

        try {
            subject.parse("{\"mcpServers\":{\"%s\":{\"command\":\"npx\",\"secretField\":\"%s\"}}}"
                    .formatted(sentinel, sentinel));
            throw new AssertionError("Expected import failure");
        } catch (McpJsonImporter.ImportException e) {
            assertThat(e.getCause()).isNull();
            assertThat(e.getMessage()).isNull();
            assertThat(e.diagnostic().contains(sentinel)).as("diagnostic should not contain source text").isFalse();
            assertThat(e.safePath().contains(sentinel)).as("path should not contain source text").isFalse();
        }

        try {
            subject.parse("{\"%s\":1,\"%s\":2}".formatted(sentinel, sentinel));
            throw new AssertionError("Expected duplicate-key failure");
        } catch (McpJsonImporter.ImportException e) {
            assertThat(e.reason()).isEqualTo(McpJsonImporter.ImportErrorReason.DUPLICATE_KEY);
            assertThat(e.diagnostic().contains(sentinel)).as("Jackson diagnostic should not contain source text")
                    .isFalse();
            assertThat(e.diagnostic()).contains("line 1").contains("column");
        }
    }

    @Test
    @DisplayName("Closing before transfer wipes values and transfer is one shot")
    void close_whenResultOwnsSecrets_wipesBeforeTransferAndRejectsSecondTransfer() throws Exception {
        try (var owned = subject.parse("{\"command\":\"npx\",\"env\":{\"TOKEN\":\"secret\"}}")) {
            List<McpJsonImporter.ImportedCredential> ownedCredentials = field(owned, "credentials");
            char[] ownedValue = ownedCredentials.getFirst().value();
            try {
                owned.close();
                assertThat(allNul(ownedValue)).as("closing should wipe a result-owned credential").isTrue();
            } finally {
                fill(ownedValue, '\0');
            }
        }

        try (var transferred = subject.parse("{\"command\":\"npx\",\"env\":{\"TOKEN\":\"secret\"}}")) {
            List<McpJsonImporter.ImportedCredential> credentials = transferred.transferCredentials();
            try {
                char[] value = credentials.getFirst().value();
                char[] expected = "secret".toCharArray();
                try {
                    transferred.close();
                    assertThat(deepEquals(value, expected))
                            .as("closing after transfer should not wipe caller ownership")
                            .isTrue();
                    assertThatThrownBy(transferred::transferCredentials)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("Imported credentials were already transferred.");
                } finally {
                    fill(expected, '\0');
                }
            } finally {
                credentials.forEach(McpJsonImporter.ImportedCredential::wipe);
            }
        }
    }

    @Test
    @DisplayName("Result and staging diagnostics mask source-controlled fields")
    void toString_whenImporterTypesContainSourceText_masksFields() throws Exception {
        try (var result = subject.parse("{\"name\":\"sentinel\",\"command\":\"sentinel\",\"env\":{\"sentinel\":\"sentinel\"}}")) {
            assertThat(result.toString().contains("sentinel"))
                    .as("result diagnostic should not contain source text")
                    .isFalse();
            assertThat(result.credentialDescriptors().getFirst().toString().contains("sentinel"))
                    .as("descriptor diagnostic should not contain source text")
                    .isFalse();
            List<McpJsonImporter.ImportedCredential> credentials = result.transferCredentials();
            try {
                assertThat(credentials.getFirst().toString().contains("sentinel"))
                        .as("credential diagnostic should not contain source text")
                        .isFalse();
            } finally {
                credentials.forEach(McpJsonImporter.ImportedCredential::wipe);
            }
        }

        Object credentialValue = constructStagingType(
                "CredentialValue",
                new Class<?>[]{String.class, String.class},
                "sentinel",
                "sentinel"
        );
        Object normalized = constructStagingType(
                "Normalized",
                new Class<?>[]{McpTransportType.class, String.class, String.class, List.class, List.class},
                McpTransportType.STDIO,
                "sentinel",
                "sentinel",
                List.of("sentinel"),
                List.of(credentialValue)
        );
        assertThat(credentialValue.toString().contains("sentinel"))
                .as("credential staging diagnostic should not contain source text")
                .isFalse();
        assertThat(normalized.toString().contains("sentinel"))
                .as("normalized staging diagnostic should not contain source text")
                .isFalse();
    }

    private String credentialJson(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"command\":\"npx\",\"env\":{\"TOKEN\":\"%s\"}}".formatted(escaped);
    }

    private void assertReason(String content, McpJsonImporter.ImportErrorReason reason) {
        assertThatThrownBy(() -> subject.parse(content))
                .isInstanceOfSatisfying(McpJsonImporter.ImportException.class, error -> {
                    assertThat(error.reason()).isEqualTo(reason);
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getMessage()).isNull();
                });
    }

    private void assertFailure(String content, McpJsonImporter.ImportErrorReason reason, String safePath) {
        assertThatThrownBy(() -> subject.parse(content))
                .isInstanceOfSatisfying(McpJsonImporter.ImportException.class, error -> {
                    assertThat(error.reason()).isEqualTo(reason);
                    assertThat(error.safePath()).isEqualTo(safePath);
                });
    }

    private Object constructStagingType(String simpleName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Class<?> type = stream(McpJsonImporter.class.getDeclaredClasses())
                    .filter(candidate -> candidate.getSimpleName().equals(simpleName))
                    .findFirst()
                    .orElseThrow();
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<McpJsonImporter.ImportedCredential> field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (List<McpJsonImporter.ImportedCredential>) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private boolean allNul(char[] value) {
        return value != null && value.length > 0
                && CharBuffer.wrap(value).chars().allMatch(character -> character == '\0');
    }
}
