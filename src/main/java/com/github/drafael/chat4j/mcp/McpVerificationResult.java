package com.github.drafael.chat4j.mcp;

import java.util.List;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public record McpVerificationResult(
        @NonNull McpApplyResult applyResult,
        String serverId,
        @NonNull List<McpDiscoveredTool> tools,
        String verificationError
) {
    public McpVerificationResult {
        tools = List.copyOf(tools);
        verificationError = StringUtils.defaultString(verificationError);
    }

    public static McpVerificationResult successful(
            @NonNull McpApplyResult applyResult,
            String serverId,
            @NonNull List<McpDiscoveredTool> tools
    ) {
        return new McpVerificationResult(applyResult, serverId, tools, "");
    }

    public static McpVerificationResult failed(@NonNull McpApplyResult applyResult, String serverId, String error) {
        return new McpVerificationResult(
                applyResult,
                serverId,
                emptyList(),
                StringUtils.defaultIfBlank(error, "MCP verification failed.")
        );
    }

    public boolean verified() {
        return applyResult.outcome().applied() && StringUtils.isBlank(verificationError);
    }
}
