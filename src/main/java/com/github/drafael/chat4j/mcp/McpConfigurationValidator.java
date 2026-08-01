package com.github.drafael.chat4j.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;

public final class McpConfigurationValidator {

    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9_-]{1,48}");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SECRET_ID = Pattern.compile("MCP_[A-F0-9]{32}");
    private static final Set<String> RESERVED_HEADERS = Set.of(
            "host",
            "content-length",
            "content-type",
            "mcp-protocol-version",
            "mcp-session-id",
            "last-event-id"
    );

    private McpConfigurationValidator() {
    }

    public static void validate(@NonNull McpConfiguration configuration) {
        if (configuration.version() != McpConfiguration.CURRENT_VERSION) {
            throw failure(
                    "Unsupported MCP configuration version: %d".formatted(configuration.version()),
                    ValidationCategory.GENERAL,
                    ""
            );
        }

        Set<String> ids = new HashSet<>();
        Set<String> modelIds = new HashSet<>();
        Set<String> credentialRowIds = new HashSet<>();
        configuration.servers().forEach(server -> validateServer(server, ids, modelIds, credentialRowIds));
    }

    private static void validateServer(
            McpServerConfiguration server,
            Set<String> ids,
            Set<String> modelIds,
            Set<String> credentialRowIds
    ) {
        try {
            UUID.fromString(server.id());
        } catch (IllegalArgumentException e) {
            throw failure("MCP server ID must be a UUID.", ValidationCategory.GENERAL, "");
        }
        if (!ids.add(server.id())) {
            throw failure("MCP server IDs must be unique.", ValidationCategory.GENERAL, "");
        }
        if (!MODEL_ID.matcher(server.modelId()).matches()) {
            throw failure(
                    "MCP model ID must contain only letters, digits, underscores, or hyphens.",
                    ValidationCategory.MODEL_ID,
                    server.id()
            );
        }
        if (!modelIds.add(server.modelId().toLowerCase(Locale.ROOT))) {
            throw failure("MCP model IDs must be unique.", ValidationCategory.GENERAL, "");
        }

        validateSecretRows(
                server.headers(),
                true,
                credentialRowIds,
                server.id(),
                ValidationCategory.HTTP_HEADERS
        );
        validateSecretRows(
                server.environment(),
                false,
                credentialRowIds,
                server.id(),
                ValidationCategory.ENVIRONMENT
        );
        if (server.transport() == McpTransportType.STREAMABLE_HTTP) {
            if (server.longRunning()) {
                throw failure(
                        "Long-running mode is available only for stdio MCP servers.",
                        ValidationCategory.GENERAL,
                        ""
                );
            }
            validateEndpoint(server.endpoint(), server.id());
        } else {
            validateExecutable(server.executable(), server.id());
        }
        if (server.disabledTools().stream().anyMatch(StringUtils::isBlank)) {
            throw failure("Disabled MCP tool names must not be blank.", ValidationCategory.TOOLS, server.id());
        }
    }

    private static void validateEndpoint(String value, String serverId) {
        try {
            URI endpoint = URI.create(value);
            if (!endpoint.isAbsolute() || !(Strings.CI.equals("http", endpoint.getScheme())
                    || Strings.CI.equals("https", endpoint.getScheme()))) {
                throw failure(
                        "MCP endpoint must be an absolute HTTP or HTTPS URL.",
                        ValidationCategory.ENDPOINT,
                        serverId
                );
            }
            if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
                throw failure(
                        "MCP endpoint must not contain user-info or a fragment.",
                        ValidationCategory.ENDPOINT,
                        serverId
                );
            }
            if (StringUtils.isBlank(endpoint.getHost())) {
                throw failure("MCP endpoint must include a host.", ValidationCategory.ENDPOINT, serverId);
            }
        } catch (ValidationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw failure("MCP endpoint URL is invalid.", ValidationCategory.ENDPOINT, serverId);
        }
    }

    private static void validateExecutable(String executable, String serverId) {
        if (StringUtils.isBlank(executable)) {
            throw failure("MCP executable must not be blank.", ValidationCategory.EXECUTABLE, serverId);
        }
        Path path;
        try {
            path = Path.of(executable);
        } catch (IllegalArgumentException e) {
            throw failure(e.getMessage(), ValidationCategory.EXECUTABLE, serverId);
        }
        boolean containsSeparator = executable.contains("/") || executable.contains("\\");
        if (containsSeparator && !path.isAbsolute()) {
            throw failure(
                    "MCP executable must be absolute or a bare PATH command.",
                    ValidationCategory.EXECUTABLE,
                    serverId
            );
        }
        String normalized = executable.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".cmd") || normalized.endsWith(".bat")) {
            throw failure(
                    "Windows command scripts require an explicit native interpreter.",
                    ValidationCategory.EXECUTABLE,
                    serverId
            );
        }
    }

    private static void validateSecretRows(
            List<McpSecretReference> rows,
            boolean headers,
            Set<String> credentialRowIds,
            String serverId,
            ValidationCategory category
    ) {
        Set<String> keys = new HashSet<>();
        rows.forEach(row -> {
            if (StringUtils.isBlank(row.rowId())) {
                throw failure(
                        "MCP credential row IDs must be nonblank and unique.",
                        category,
                        serverId
                );
            }
            if (!credentialRowIds.add(row.rowId())) {
                throw failure(
                        "MCP credential row IDs must be nonblank and unique.",
                        ValidationCategory.GENERAL,
                        ""
                );
            }
            String key = row.key();
            if (headers ? !HEADER_NAME.matcher(key).matches() : !ENVIRONMENT_NAME.matcher(key).matches()) {
                throw failure(
                        "Invalid MCP %s name: %s".formatted(headers ? "header" : "environment", key),
                        category,
                        serverId
                );
            }
            String normalized = headers || IS_OS_WINDOWS ? key.toLowerCase(Locale.ROOT) : key;
            if (!keys.add(normalized)) {
                throw failure(
                        "MCP %s names must be unique.".formatted(headers ? "header" : "environment"),
                        category,
                        serverId
                );
            }
            if (headers && RESERVED_HEADERS.contains(normalized)) {
                throw failure("MCP header is reserved: %s".formatted(key), category, serverId);
            }
            if (StringUtils.isNotBlank(row.secretId()) && !SECRET_ID.matcher(row.secretId()).matches()) {
                throw failure("Invalid MCP secret reference.", category, serverId);
            }
        });
    }

    private static ValidationException failure(String message, ValidationCategory category, String responsibleServerId) {
        return new ValidationException(message, category, responsibleServerId);
    }

    public enum ValidationCategory {
        GENERAL,
        MODEL_ID,
        ENDPOINT,
        EXECUTABLE,
        HTTP_HEADERS,
        ENVIRONMENT,
        TOOLS
    }

    public static final class ValidationException extends IllegalArgumentException {
        private final ValidationCategory category;
        private final String responsibleServerId;

        private ValidationException(String message, ValidationCategory category, String responsibleServerId) {
            super(message);
            this.category = category;
            this.responsibleServerId = StringUtils.defaultString(responsibleServerId);
        }

        public ValidationCategory category() {
            return category;
        }

        public String responsibleServerId() {
            return responsibleServerId;
        }
    }
}
