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
            throw new IllegalArgumentException("Unsupported MCP configuration version: %d".formatted(configuration.version()));
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
            throw new IllegalArgumentException("MCP server ID must be a UUID.");
        }
        if (!ids.add(server.id())) {
            throw new IllegalArgumentException("MCP server IDs must be unique.");
        }
        if (!MODEL_ID.matcher(server.modelId()).matches()) {
            throw new IllegalArgumentException("MCP model ID must contain only letters, digits, underscores, or hyphens.");
        }
        if (!modelIds.add(server.modelId().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("MCP model IDs must be unique.");
        }

        validateSecretRows(server.headers(), true, credentialRowIds);
        validateSecretRows(server.environment(), false, credentialRowIds);
        if (server.transport() == McpTransportType.STREAMABLE_HTTP) {
            if (server.longRunning()) {
                throw new IllegalArgumentException("Long-running mode is available only for stdio MCP servers.");
            }
            validateEndpoint(server.endpoint());
        } else {
            validateExecutable(server.executable());
        }
        if (server.disabledTools().stream().anyMatch(StringUtils::isBlank)) {
            throw new IllegalArgumentException("Disabled MCP tool names must not be blank.");
        }
    }

    private static void validateEndpoint(String value) {
        try {
            URI endpoint = URI.create(value);
            if (!endpoint.isAbsolute() || !(Strings.CI.equals("http", endpoint.getScheme())
                    || Strings.CI.equals("https", endpoint.getScheme()))) {
                throw new IllegalArgumentException("MCP endpoint must be an absolute HTTP or HTTPS URL.");
            }
            if (endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
                throw new IllegalArgumentException("MCP endpoint must not contain user-info or a fragment.");
            }
            if (StringUtils.isBlank(endpoint.getHost())) {
                throw new IllegalArgumentException("MCP endpoint must include a host.");
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("MCP endpoint")) {
                throw e;
            }
            throw new IllegalArgumentException("MCP endpoint URL is invalid.");
        }
    }

    private static void validateExecutable(String executable) {
        if (StringUtils.isBlank(executable)) {
            throw new IllegalArgumentException("MCP executable must not be blank.");
        }
        Path path = Path.of(executable);
        boolean containsSeparator = executable.contains("/") || executable.contains("\\");
        if (containsSeparator && !path.isAbsolute()) {
            throw new IllegalArgumentException("MCP executable must be absolute or a bare PATH command.");
        }
        String normalized = executable.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".cmd") || normalized.endsWith(".bat")) {
            throw new IllegalArgumentException("Windows command scripts require an explicit native interpreter.");
        }
    }

    private static void validateSecretRows(
            List<McpSecretReference> rows,
            boolean headers,
            Set<String> credentialRowIds
    ) {
        Set<String> keys = new HashSet<>();
        rows.forEach(row -> {
            if (StringUtils.isBlank(row.rowId()) || !credentialRowIds.add(row.rowId())) {
                throw new IllegalArgumentException("MCP credential row IDs must be nonblank and unique.");
            }
            String key = row.key();
            if (headers ? !HEADER_NAME.matcher(key).matches() : !ENVIRONMENT_NAME.matcher(key).matches()) {
                throw new IllegalArgumentException("Invalid MCP %s name: %s".formatted(headers ? "header" : "environment", key));
            }
            String normalized = headers || isWindows() ? key.toLowerCase(Locale.ROOT) : key;
            if (!keys.add(normalized)) {
                throw new IllegalArgumentException("MCP %s names must be unique.".formatted(headers ? "header" : "environment"));
            }
            if (headers && RESERVED_HEADERS.contains(normalized)) {
                throw new IllegalArgumentException("MCP header is reserved: %s".formatted(key));
            }
            if (StringUtils.isNotBlank(row.secretId()) && !SECRET_ID.matcher(row.secretId()).matches()) {
                throw new IllegalArgumentException("Invalid MCP secret reference.");
            }
        });
    }

    private static boolean isWindows() {
        return Strings.CI.contains(System.getProperty("os.name", ""), "win");
    }
}
