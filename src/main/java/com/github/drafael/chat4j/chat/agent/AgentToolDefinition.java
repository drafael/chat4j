package com.github.drafael.chat4j.chat.agent;

import java.util.Map;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static com.github.drafael.chat4j.chat.agent.AgentToolSchema.immutableMap;

public record AgentToolDefinition(
        String name,
        String description,
        @NonNull Map<String, Object> inputSchema,
        @NonNull AgentToolSource source
) {

    public AgentToolDefinition {
        name = StringUtils.defaultString(name);
        description = StringUtils.defaultString(description);
        inputSchema = immutableMap(inputSchema);
    }
}
