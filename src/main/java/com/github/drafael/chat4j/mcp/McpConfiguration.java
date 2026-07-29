package com.github.drafael.chat4j.mcp;

import java.util.List;

import static java.util.Collections.emptyList;

public record McpConfiguration(int version, List<McpServerConfiguration> servers) {

    public static final int CURRENT_VERSION = 1;

    public McpConfiguration {
        servers = servers == null ? emptyList() : List.copyOf(servers);
    }

    public static McpConfiguration empty() {
        return new McpConfiguration(CURRENT_VERSION, emptyList());
    }
}
