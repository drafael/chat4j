package com.github.drafael.chat4j.chat.agent;

import org.apache.commons.lang3.StringUtils;

public record McpApprovalRequest(String serverName, String toolName, String arguments) {
    public McpApprovalRequest {
        serverName = StringUtils.defaultString(serverName);
        toolName = StringUtils.defaultString(toolName);
        arguments = StringUtils.defaultString(arguments);
    }

    @Override
    public String toString() {
        return "McpApprovalRequest[serverName=%s, toolName=%s, arguments=****]"
                .formatted(serverName, toolName);
    }
}
