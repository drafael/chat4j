package com.github.drafael.chat4j.mcp;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public sealed interface McpConfigurationLoadResult {

    record Missing(@NonNull McpConfiguration configuration) implements McpConfigurationLoadResult {
        public Missing() {
            this(McpConfiguration.empty());
        }
    }

    record Valid(@NonNull McpConfiguration configuration) implements McpConfigurationLoadResult {
    }

    record Invalid(String message) implements McpConfigurationLoadResult {
        public Invalid {
            message = StringUtils.defaultIfBlank(message, "MCP configuration is invalid.");
        }
    }
}
