package com.github.drafael.chat4j.chat.agent;

import org.apache.commons.lang3.StringUtils;

import java.util.UUID;

public record ToolInvocationRequest(String id, String name, String argumentsJson) {

    public ToolInvocationRequest {
        id = StringUtils.defaultIfBlank(id, UUID.randomUUID().toString());
        name = StringUtils.defaultString(name).trim();
        argumentsJson = StringUtils.defaultIfBlank(argumentsJson, "{}");
    }
}
