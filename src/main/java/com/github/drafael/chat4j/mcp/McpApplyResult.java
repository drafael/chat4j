package com.github.drafael.chat4j.mcp;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public record McpApplyResult(
        @NonNull McpApplyOutcome outcome,
        long generation,
        @NonNull McpConfiguration configuration,
        String message
) {
    public McpApplyResult {
        message = StringUtils.defaultString(message);
    }
}
