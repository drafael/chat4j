package com.github.drafael.chat4j.mcp;

import java.util.Map;
import lombok.NonNull;

import static com.github.drafael.chat4j.chat.agent.AgentToolSchema.immutableMap;
import static java.util.Collections.emptyMap;

public record McpDiscoveredTool(
        String name,
        String title,
        String description,
        @NonNull Map<String, Object> inputSchema,
        Map<String, Object> outputSchema
) {
    public McpDiscoveredTool {
        inputSchema = immutableMap(inputSchema);
        outputSchema = outputSchema == null ? emptyMap() : immutableMap(outputSchema);
    }
}
