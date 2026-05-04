package com.github.drafael.chat4j.chat.agent;

import org.apache.commons.lang3.StringUtils;

public record AgentToolActivity(
        String invocationId,
        String toolName,
        Status status,
        String argumentsSummary,
        String message
) {

    public AgentToolActivity {
        invocationId = StringUtils.defaultString(invocationId);
        toolName = StringUtils.defaultIfBlank(toolName, "unknown");
        status = status == null ? Status.STARTED : status;
        argumentsSummary = StringUtils.defaultString(argumentsSummary);
        message = StringUtils.defaultString(message);
    }

    @Override
    public String toString() {
        return "AgentToolActivity[invocationId=%s, toolName=%s, status=%s]".formatted(invocationId, toolName, status);
    }

    public enum Status {
        STARTED,
        SUCCEEDED,
        FAILED,
        SKIPPED
    }
}
