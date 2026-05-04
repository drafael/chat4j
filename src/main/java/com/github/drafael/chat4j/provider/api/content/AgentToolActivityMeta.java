package com.github.drafael.chat4j.provider.api.content;

import org.apache.commons.lang3.StringUtils;

public record AgentToolActivityMeta(
        String invocationId,
        String toolName,
        String status,
        String argumentsSummary,
        String message
) {

    public AgentToolActivityMeta {
        invocationId = StringUtils.defaultString(invocationId);
        toolName = StringUtils.defaultIfBlank(toolName, "unknown");
        status = StringUtils.defaultIfBlank(status, "STARTED");
        argumentsSummary = StringUtils.defaultString(argumentsSummary);
        message = StringUtils.defaultString(message);
    }

    @Override
    public String toString() {
        return "AgentToolActivityMeta[invocationId=%s, toolName=%s, status=%s]"
                .formatted(invocationId, toolName, status);
    }
}
