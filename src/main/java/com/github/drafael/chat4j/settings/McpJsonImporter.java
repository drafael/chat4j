package com.github.drafael.chat4j.settings;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.mcp.McpTransportType;
import com.github.drafael.chat4j.provider.support.CredentialStoragePolicy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

import static java.util.Arrays.fill;
import static java.util.Collections.emptyList;

final class McpJsonImporter {

    static final int MAX_DOCUMENT_LENGTH = 1_048_576;
    private static final int MAX_SCALAR_LENGTH = 256 * 1024;
    private static final int MAX_NAME_CODE_POINTS = 256;
    private static final int MAX_CREDENTIAL_NAME_LENGTH = 256;
    private static final String DEFAULT_NAME = "Imported server";
    private static final Set<String> WRAPPERS = Set.of("mcpServers", "servers", "mcp");
    private static final Set<String> OPERATIONAL_FIELDS = Set.of(
            "name", "type", "transportType", "command", "args", "env", "environment",
            "url", "httpUrl", "serverUrl", "headers"
    );
    private static final Set<String> AUTHORITY_FIELDS = Set.of(
            "enabled", "disabled", "start_on_launch", "autoApprove", "alwaysAllow", "trust",
            "tools", "disabledTools", "includeTools", "excludeTools", "sandbox"
    );
    private static final Set<String> METADATA_FIELDS = Set.of(
            "description", "source", "timeout", "timeoutSeconds", "startupTimeout",
            "startupTimeoutMs", "startup_timeout_ms"
    );
    private static final Set<String> UNSUPPORTED_FIELDS = Set.of(
            "cwd", "workingDirectory", "working_directory", "envFile", "oauth", "oauthConfig",
            "auth", "authProvider", "authProviderType"
    );
    private static final Set<String> STDIO_LABELS = Set.of("stdio", "local");
    private static final Set<String> HTTP_LABELS = Set.of(
            "http", "streamable-http", "streamablehttp", "streamable", "remote"
    );
    private static final Pattern ASCII = Pattern.compile("[\\x00-\\x7f]{0,256}");
    private static final Pattern YOUR_PLACEHOLDER = Pattern.compile(
            "(?<![A-Z0-9_])YOUR_[A-Z][A-Z0-9_]{0,63}(?![A-Z0-9_])"
    );
    private static final Pattern BRACED_ENV_PLACEHOLDER = Pattern.compile(
            "\\$\\{[A-Za-z_][A-Za-z0-9_]{0,63}(?::-[^}\\r\\n]{0,256})?}"
    );
    private static final Pattern COLON_ENV_PLACEHOLDER = Pattern.compile(
            "\\$\\{env:[A-Za-z_][A-Za-z0-9_]{0,63}}"
    );
    private static final Pattern INPUT_PLACEHOLDER = Pattern.compile(
            "\\$\\{input:[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}}"
    );
    private static final Pattern DOLLAR_ENV_PLACEHOLDER = Pattern.compile(
            "\\$(?!\\{)[A-Za-z_][A-Za-z0-9_]{0,63}(?![A-Za-z0-9_])"
    );
    private static final Pattern PERCENT_ENV_PLACEHOLDER = Pattern.compile(
            "%[A-Za-z_][A-Za-z0-9_]{0,63}%"
    );
    private static final Pattern PLAIN_ENV_PLACEHOLDER = Pattern.compile(
            "\\{env:[A-Za-z_][A-Za-z0-9_]{0,63}}"
    );
    private static final Pattern ANGLE_PLACEHOLDER = Pattern.compile(
            "<[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}>"
    );
    private static final List<Pattern> PLACEHOLDERS = List.of(
            YOUR_PLACEHOLDER,
            BRACED_ENV_PLACEHOLDER,
            COLON_ENV_PLACEHOLDER,
            INPUT_PLACEHOLDER,
            DOLLAR_ENV_PLACEHOLDER,
            PERCENT_ENV_PLACEHOLDER,
            PLAIN_ENV_PLACEHOLDER,
            ANGLE_PLACEHOLDER
    );
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_DOCUMENT_LENGTH)
                    .maxStringLength(MAX_SCALAR_LENGTH)
                    .maxNameLength(MAX_SCALAR_LENGTH)
                    .maxNumberLength(1_000)
                    .maxNestingDepth(64)
                    .build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    ImportResult parse(String content) throws ImportException {
        if (content != null && content.length() > MAX_DOCUMENT_LENGTH) {
            throw error(ImportErrorReason.INPUT_TOO_LARGE, "$");
        }
        if (StringUtils.isBlank(content)) {
            throw error(ImportErrorReason.EMPTY_INPUT, "$");
        }

        JsonNode root = readTree(content);
        if (!root.isObject()) {
            throw error(ImportErrorReason.EXPECTED_OBJECT, "$");
        }

        EnumSet<ImportWarning> warnings = EnumSet.noneOf(ImportWarning.class);
        List<String> presentWrappers = WRAPPERS.stream().filter(root::has).toList();
        if (presentWrappers.size() > 1) {
            throw error(ImportErrorReason.MULTIPLE_WRAPPERS, "$");
        }

        JsonNode server;
        String name;
        String serverPath;
        boolean wrapped = !presentWrappers.isEmpty();
        if (wrapped) {
            String wrapper = presentWrappers.getFirst();
            validateRootSiblings(root, wrapper, warnings);
            JsonNode wrapperValue = root.get(wrapper);
            requireObject(wrapperValue, "$.%s".formatted(wrapper));
            if (wrapperValue.size() != 1) {
                throw error(ImportErrorReason.SINGLE_SERVER_REQUIRED, "$.%s".formatted(wrapper));
            }
            Iterator<String> names = wrapperValue.fieldNames();
            String memberName = names.next();
            serverPath = "$.%s.<server>".formatted(wrapper);
            server = wrapperValue.get(memberName);
            requireObject(server, serverPath);
            name = normalizeDisplayName(memberName, serverPath);
            if (server.has("name")) {
                warnings.add(ImportWarning.SOURCE_METADATA_IGNORED);
            }
        } else {
            server = root;
            serverPath = "$.server";
            name = normalizeBareName(server, serverPath);
        }

        validateServerFields(server, warnings, serverPath);
        Normalized normalized = normalizeServer(server, serverPath, warnings);
        List<ImportedCredential> credentials = allocateCredentials(normalized.credentials());
        return new ImportResult(
                name,
                normalized.transport(),
                normalized.endpoint(),
                normalized.executable(),
                normalized.arguments(),
                credentials,
                List.copyOf(warnings)
        );
    }

    private JsonNode readTree(String content) throws ImportException {
        try {
            JsonNode root = JSON.readTree(content);
            if (root == null) {
                throw error(ImportErrorReason.EMPTY_INPUT, "$");
            }
            return root;
        } catch (ImportException e) {
            throw e;
        } catch (StreamConstraintsException e) {
            throw jacksonError(ImportErrorReason.JSON_LIMIT_EXCEEDED, e.getLocation());
        } catch (JsonProcessingException e) {
            String original = StringUtils.defaultString(e.getOriginalMessage());
            ImportErrorReason reason = original.startsWith("Duplicate field")
                    ? ImportErrorReason.DUPLICATE_KEY
                    : original.contains("Trailing token")
                            ? ImportErrorReason.TRAILING_CONTENT
                            : ImportErrorReason.MALFORMED_JSON;
            throw jacksonError(reason, e.getLocation());
        }
    }

    private void validateRootSiblings(JsonNode root, String wrapper, EnumSet<ImportWarning> warnings)
            throws ImportException {
        Iterator<String> fields = root.fieldNames();
        int ordinal = 0;
        while (fields.hasNext()) {
            String field = fields.next();
            ordinal++;
            if (field.equals(wrapper)) {
                continue;
            }
            switch (field) {
                case "$schema", "inputs" -> warnings.add(ImportWarning.SOURCE_METADATA_IGNORED);
                case "sandbox" -> warnings.add(ImportWarning.SOURCE_AUTHORITY_IGNORED);
                default -> throw error(ImportErrorReason.UNKNOWN_FIELD, "$.<field %d>".formatted(ordinal));
            }
        }
    }

    private void validateServerFields(JsonNode server, EnumSet<ImportWarning> warnings, String path)
            throws ImportException {
        Iterator<String> fields = server.fieldNames();
        int ordinal = 0;
        while (fields.hasNext()) {
            String field = fields.next();
            ordinal++;
            if (OPERATIONAL_FIELDS.contains(field)) {
                continue;
            }
            if (AUTHORITY_FIELDS.contains(field)) {
                warnings.add(ImportWarning.SOURCE_AUTHORITY_IGNORED);
            } else if (METADATA_FIELDS.contains(field)) {
                warnings.add(ImportWarning.SOURCE_METADATA_IGNORED);
            } else if (UNSUPPORTED_FIELDS.contains(field)) {
                throw error(ImportErrorReason.UNSUPPORTED_FIELD, "%s.<field %d>".formatted(path, ordinal));
            } else {
                throw error(ImportErrorReason.UNKNOWN_FIELD, "%s.<field %d>".formatted(path, ordinal));
            }
        }
    }

    private Normalized normalizeServer(JsonNode server, String path, EnumSet<ImportWarning> warnings)
            throws ImportException {
        boolean commandPresent = server.has("command");
        List<String> endpointAliases = List.of("url", "httpUrl", "serverUrl").stream()
                .filter(server::has)
                .toList();
        if (commandPresent && !endpointAliases.isEmpty()) {
            throw error(ImportErrorReason.CONFLICTING_FIELDS, path);
        }
        if (!commandPresent && endpointAliases.isEmpty()) {
            throw error(ImportErrorReason.SERVER_TARGET_REQUIRED, path);
        }
        if (endpointAliases.size() > 1) {
            throw error(ImportErrorReason.CONFLICTING_FIELDS, path);
        }

        McpTransportType structural = commandPresent
                ? McpTransportType.STDIO
                : McpTransportType.STREAMABLE_HTTP;
        McpTransportType declared = declaredTransport(server, path);
        if (declared != null && declared != structural) {
            throw error(ImportErrorReason.CONFLICTING_FIELDS, path);
        }

        return structural == McpTransportType.STDIO
                ? normalizeStdio(server, path, warnings)
                : normalizeHttp(server, path, endpointAliases.getFirst(), warnings);
    }

    private McpTransportType declaredTransport(JsonNode server, String path) throws ImportException {
        McpTransportType type = transportLabel(server, "type", path);
        McpTransportType legacy = transportLabel(server, "transportType", path);
        if (type != null && legacy != null && type != legacy) {
            throw error(ImportErrorReason.CONFLICTING_FIELDS, path);
        }
        return type == null ? legacy : type;
    }

    private McpTransportType transportLabel(JsonNode server, String field, String path) throws ImportException {
        if (!server.has(field)) {
            return null;
        }
        JsonNode value = server.get(field);
        requireText(value, "%s.%s".formatted(path, field));
        String normalized = value.textValue().strip().toLowerCase(Locale.ROOT);
        if (STDIO_LABELS.contains(normalized)) {
            return McpTransportType.STDIO;
        }
        if (HTTP_LABELS.contains(normalized)) {
            return McpTransportType.STREAMABLE_HTTP;
        }
        throw error(ImportErrorReason.UNSUPPORTED_TRANSPORT, "%s.%s".formatted(path, field));
    }

    private Normalized normalizeStdio(JsonNode server, String path, EnumSet<ImportWarning> warnings)
            throws ImportException {
        if (server.has("env") && server.has("environment")) {
            throw error(ImportErrorReason.CONFLICTING_FIELDS, path);
        }
        if (server.has("headers")) {
            throw error(ImportErrorReason.CONFLICTING_FIELDS, "%s.headers".formatted(path));
        }

        JsonNode command = server.get("command");
        String executable;
        String argumentPath;
        int argumentOrdinalOffset;
        List<String> arguments = new ArrayList<>();
        if (command.isTextual()) {
            executable = command.textValue();
            argumentPath = "%s.args".formatted(path);
            argumentOrdinalOffset = 1;
            if (server.has("args")) {
                arguments.addAll(readStringArray(server.get("args"), argumentPath, false));
            }
        } else if (command.isArray()) {
            if (server.has("args")) {
                throw error(ImportErrorReason.CONFLICTING_FIELDS, path);
            }
            argumentPath = "%s.command".formatted(path);
            argumentOrdinalOffset = 2;
            List<String> commandParts = readStringArray(command, argumentPath, true);
            executable = commandParts.getFirst();
            arguments.addAll(commandParts.subList(1, commandParts.size()));
        } else {
            throw error(ImportErrorReason.EXPECTED_STRING, "%s.command".formatted(path));
        }
        if (executable.indexOf('\0') >= 0) {
            throw error(ImportErrorReason.INVALID_CHARACTER, "%s.command".formatted(path));
        }
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.indexOf('\0') >= 0) {
                throw error(
                        ImportErrorReason.INVALID_CHARACTER,
                        "%s.<entry %d>".formatted(argumentPath, index + argumentOrdinalOffset)
                );
            }
            if (containsPlaceholder(argument)) {
                warnings.add(ImportWarning.ARGUMENT_PLACEHOLDER_PRESERVED);
            }
        }

        String credentialField = server.has("env") ? "env" : server.has("environment") ? "environment" : null;
        List<CredentialValue> credentials = credentialField == null
                ? emptyList()
                : readCredentials(server.get(credentialField), false, "%s.%s".formatted(path, credentialField));
        return new Normalized(McpTransportType.STDIO, "", executable, List.copyOf(arguments), credentials);
    }

    private Normalized normalizeHttp(
            JsonNode server,
            String path,
            String endpointAlias,
            EnumSet<ImportWarning> warnings
    ) throws ImportException {
        if (server.has("env") || server.has("environment") || server.has("args")) {
            throw error(ImportErrorReason.CONFLICTING_FIELDS, path);
        }
        JsonNode endpoint = server.get(endpointAlias);
        requireText(endpoint, "%s.%s".formatted(path, endpointAlias));
        if (!server.has("type") && !server.has("transportType") && endpointAlias.equals("url")) {
            warnings.add(ImportWarning.TYPELESS_URL_AS_HTTP);
        }
        List<CredentialValue> credentials = server.has("headers")
                ? readCredentials(server.get("headers"), true, "%s.headers".formatted(path))
                : emptyList();
        return new Normalized(
                McpTransportType.STREAMABLE_HTTP,
                endpoint.textValue(),
                "",
                emptyList(),
                credentials
        );
    }

    private List<String> readStringArray(JsonNode node, String path, boolean nonempty) throws ImportException {
        if (!node.isArray()) {
            throw error(ImportErrorReason.EXPECTED_ARRAY, path);
        }
        if (nonempty && node.isEmpty()) {
            throw error(ImportErrorReason.COMMAND_REQUIRED, path);
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode value = node.get(index);
            requireText(value, "%s.<entry %d>".formatted(path, index + 1));
            values.add(value.textValue());
        }
        return values;
    }

    private List<CredentialValue> readCredentials(JsonNode node, boolean headers, String path) throws ImportException {
        requireObject(node, path);
        List<CredentialValue> values = new ArrayList<>();
        Iterator<String> names = node.fieldNames();
        int ordinal = 0;
        while (names.hasNext()) {
            String name = names.next();
            ordinal++;
            String entryPath = "%s.<entry %d>".formatted(path, ordinal);
            if (name.length() > MAX_CREDENTIAL_NAME_LENGTH || !ASCII.matcher(name).matches()) {
                throw error(ImportErrorReason.INVALID_NAME, entryPath);
            }
            JsonNode value = node.get(name);
            requireText(value, entryPath);
            String text = value.textValue();
            if (headers && (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0)
                    || !headers && text.indexOf('\0') >= 0) {
                throw error(ImportErrorReason.INVALID_CHARACTER, entryPath);
            }
            boolean missing = StringUtils.isBlank(text) || containsPlaceholder(text);
            if (!missing) {
                switch (CredentialStoragePolicy.validate(text)) {
                    case VALID -> { }
                    case MALFORMED_UTF16 -> throw error(ImportErrorReason.INVALID_CHARACTER, entryPath);
                    case TOO_LARGE -> throw error(
                            headers
                                    ? ImportErrorReason.IMPORTED_HEADERS_INVALID
                                    : ImportErrorReason.IMPORTED_ENVIRONMENT_INVALID,
                            entryPath
                    );
                }
            }
            values.add(new CredentialValue(name, missing ? null : text));
        }
        return List.copyOf(values);
    }

    private List<ImportedCredential> allocateCredentials(List<CredentialValue> values) {
        List<ImportedCredential> credentials = new ArrayList<>();
        try {
            values.forEach(value -> credentials.add(new ImportedCredential(
                    value.name(),
                    value.value() == null ? null : value.value().toCharArray()
            )));
            return List.copyOf(credentials);
        } catch (RuntimeException e) {
            credentials.forEach(ImportedCredential::wipe);
            throw e;
        }
    }

    private String normalizeBareName(JsonNode server, String path) throws ImportException {
        if (!server.has("name")) {
            return DEFAULT_NAME;
        }
        JsonNode name = server.get("name");
        requireText(name, "%s.name".formatted(path));
        return normalizeDisplayName(name.textValue(), "%s.name".formatted(path));
    }

    private String normalizeDisplayName(String source, String path) throws ImportException {
        String name = StringUtils.defaultString(source).strip();
        if (StringUtils.isBlank(name)) {
            return DEFAULT_NAME;
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS || containsPresentationControl(name)) {
            throw error(ImportErrorReason.INVALID_NAME, path);
        }
        return name;
    }

    private boolean containsPresentationControl(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint <= 0x1f
                || codePoint >= 0x7f && codePoint <= 0x9f
                || codePoint == 0x061c
                || codePoint >= 0x200e && codePoint <= 0x200f
                || codePoint >= 0x202a && codePoint <= 0x202e
                || codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    private static boolean containsPlaceholder(String value) {
        return PLACEHOLDERS.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }

    private void requireObject(JsonNode node, String path) throws ImportException {
        if (node == null || !node.isObject()) {
            throw error(ImportErrorReason.EXPECTED_OBJECT, path);
        }
    }

    private void requireText(JsonNode node, String path) throws ImportException {
        if (node == null || !node.isTextual()) {
            throw error(ImportErrorReason.EXPECTED_STRING, path);
        }
    }

    private ImportException jacksonError(ImportErrorReason reason, JsonLocation location) {
        int line = location == null ? -1 : location.getLineNr();
        int column = location == null ? -1 : location.getColumnNr();
        return new ImportException(reason, "$", line, column);
    }

    private ImportException error(ImportErrorReason reason, String path) {
        return new ImportException(reason, path, -1, -1);
    }

    enum ImportWarning {
        SOURCE_AUTHORITY_IGNORED("Source approval, trust, enablement, or sandbox settings were ignored."),
        SOURCE_METADATA_IGNORED("Source-specific metadata was ignored."),
        TYPELESS_URL_AS_HTTP("A typeless URL was treated as Streamable HTTP; SSE is unsupported."),
        ARGUMENT_PLACEHOLDER_PRESERVED("Argument placeholders were preserved literally.");

        private final String message;

        ImportWarning(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    enum ImportErrorReason {
        CLIPBOARD_UNAVAILABLE("Clipboard text is unavailable."),
        CLIPBOARD_READ_FAILED("Could not read MCP JSON from the clipboard."),
        IMPORT_TIMED_OUT("Clipboard import timed out."),
        EMPTY_INPUT("Clipboard does not contain MCP JSON text."),
        INPUT_TOO_LARGE("MCP JSON exceeds the import size limit."),
        MALFORMED_JSON("Clipboard text is not valid supported JSON/JSONC."),
        DUPLICATE_KEY("MCP JSON contains a duplicate object key."),
        TRAILING_CONTENT("MCP JSON contains trailing content."),
        JSON_LIMIT_EXCEEDED("MCP JSON exceeds a parser limit."),
        EXPECTED_OBJECT("Expected a JSON object."),
        EXPECTED_ARRAY("Expected a JSON array."),
        EXPECTED_STRING("Expected a JSON string."),
        SINGLE_SERVER_REQUIRED("Import requires exactly one MCP server."),
        MULTIPLE_WRAPPERS("MCP JSON contains more than one recognized wrapper."),
        UNKNOWN_FIELD("MCP JSON contains an unknown field."),
        UNSUPPORTED_FIELD("MCP JSON contains an unsupported field."),
        CONFLICTING_FIELDS("MCP JSON contains conflicting fields."),
        UNSUPPORTED_TRANSPORT("MCP transport is unsupported."),
        COMMAND_REQUIRED("Command array must contain an executable."),
        SERVER_TARGET_REQUIRED("Server must define exactly one command or HTTP endpoint."),
        INVALID_NAME("Imported name is invalid."),
        INVALID_CHARACTER("Imported value contains a prohibited character."),
        GENERATED_MODEL_ID_INVALID("Generated model ID is invalid."),
        IMPORTED_ENDPOINT_INVALID("Imported HTTP endpoint is invalid."),
        IMPORTED_EXECUTABLE_INVALID("Imported executable is invalid."),
        IMPORTED_HEADERS_INVALID("Imported HTTP headers are invalid."),
        IMPORTED_ENVIRONMENT_INVALID("Imported environment variables are invalid."),
        IMPORTED_TOOLS_INVALID("Imported tool settings are invalid."),
        IMPORTED_SERVER_INVALID("Imported MCP server is invalid."),
        INSTALL_FAILED("Could not add the imported MCP server.");

        private final String message;

        ImportErrorReason(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    static final class ImportException extends Exception {
        private final ImportErrorReason reason;
        private final String safePath;
        private final int line;
        private final int column;

        ImportException(ImportErrorReason reason, String safePath, int line, int column) {
            super(null, null, false, false);
            this.reason = reason;
            this.safePath = StringUtils.defaultIfBlank(safePath, "$");
            this.line = line;
            this.column = column;
        }

        ImportErrorReason reason() {
            return reason;
        }

        String safePath() {
            return safePath;
        }

        String diagnostic() {
            String location = line > 0 && column > 0 ? " (line %d, column %d)".formatted(line, column) : "";
            return "%s Path: %s%s".formatted(reason.message(), safePath, location);
        }
    }

    static final class ImportResult implements AutoCloseable {
        private final String name;
        private final McpTransportType transport;
        private final String endpoint;
        private final String executable;
        private final List<String> arguments;
        private final List<ImportWarning> warnings;
        private final int missingCredentialCount;
        private List<ImportedCredential> credentials;
        private boolean transferred;

        private ImportResult(
                String name,
                McpTransportType transport,
                String endpoint,
                String executable,
                List<String> arguments,
                List<ImportedCredential> credentials,
                List<ImportWarning> warnings
        ) {
            this.name = name;
            this.transport = transport;
            this.endpoint = endpoint;
            this.executable = executable;
            this.arguments = List.copyOf(arguments);
            this.credentials = List.copyOf(credentials);
            this.warnings = List.copyOf(warnings);
            this.missingCredentialCount = (int) credentials.stream().filter(ImportedCredential::missing).count();
        }

        String name() {
            return name;
        }

        McpTransportType transport() {
            return transport;
        }

        String endpoint() {
            return endpoint;
        }

        String executable() {
            return executable;
        }

        List<String> arguments() {
            return arguments;
        }

        List<ImportWarning> warnings() {
            return warnings;
        }

        int missingCredentialCount() {
            return missingCredentialCount;
        }

        List<CredentialDescriptor> credentialDescriptors() {
            return credentials.stream().map(credential -> new CredentialDescriptor(credential.key())).toList();
        }

        List<ImportedCredential> transferCredentials() {
            if (transferred) {
                throw new IllegalStateException("Imported credentials were already transferred.");
            }
            transferred = true;
            List<ImportedCredential> transferredCredentials = credentials;
            credentials = emptyList();
            return transferredCredentials;
        }

        @Override
        public void close() {
            credentials.forEach(ImportedCredential::wipe);
            credentials = emptyList();
        }

        @Override
        public String toString() {
            return "ImportResult[name=****, transport=%s, endpoint=****, executable=****, arguments=****, credentials=****, warnings=%s]"
                    .formatted(transport, warnings);
        }
    }

    static final class ImportedCredential {
        private final String key;
        private final char[] value;

        private ImportedCredential(String key, char[] value) {
            this.key = key;
            this.value = value;
        }

        String key() {
            return key;
        }

        char[] value() {
            return value;
        }

        boolean missing() {
            return value == null;
        }

        void wipe() {
            if (value != null) {
                fill(value, '\0');
            }
        }

        @Override
        public String toString() {
            return "ImportedCredential[key=****, value=****]";
        }
    }

    record CredentialDescriptor(String key) {
        @Override
        public String toString() {
            return "CredentialDescriptor[key=****]";
        }
    }

    private record CredentialValue(String name, String value) {
        @Override
        public String toString() {
            return "CredentialValue[name=****, value=****]";
        }
    }

    private record Normalized(
            McpTransportType transport,
            String endpoint,
            String executable,
            List<String> arguments,
            List<CredentialValue> credentials
    ) {
        @Override
        public String toString() {
            return "Normalized[transport=%s, endpoint=****, executable=****, arguments=****, credentials=****]"
                    .formatted(transport);
        }
    }
}
