package com.github.drafael.chat4j.mcp;

import org.apache.commons.lang3.StringUtils;

public record McpInvocationTarget(String serverName, String toolName, boolean automatic) {
    public McpInvocationTarget {
        serverName = StringUtils.defaultString(serverName);
        toolName = StringUtils.defaultString(toolName);
    }
}
