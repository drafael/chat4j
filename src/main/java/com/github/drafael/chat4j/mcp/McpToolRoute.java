package com.github.drafael.chat4j.mcp;

import java.util.Map;

import static com.github.drafael.chat4j.chat.agent.AgentToolSchema.immutableMap;

record McpToolRoute(
        McpServerConfiguration server,
        String toolName,
        Map<String, Object> outputSchema,
        McpClientSession client
) {
    McpToolRoute {
        outputSchema = immutableMap(outputSchema);
    }
}
