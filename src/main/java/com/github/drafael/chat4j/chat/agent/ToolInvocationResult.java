package com.github.drafael.chat4j.chat.agent;

import lombok.NonNull;

import org.apache.commons.lang3.StringUtils;

public record ToolInvocationResult(String id, String name, boolean success, String output, String error) {

    public ToolInvocationResult {
        id = StringUtils.defaultString(id);
        name = StringUtils.defaultString(name);
        output = StringUtils.defaultString(output);
        error = StringUtils.defaultString(error);
    }

    public static ToolInvocationResult success(@NonNull ToolInvocationRequest request, String output) {
        return new ToolInvocationResult(request.id(), request.name(), true, output, "");
    }

    public static ToolInvocationResult failure(@NonNull ToolInvocationRequest request, String error) {
        return new ToolInvocationResult(request.id(), request.name(), false, "", StringUtils.defaultIfBlank(error, "Tool execution failed"));
    }

    @Override
    public String toString() {
        return "ToolInvocationResult[id=%s, name=%s, success=%s, output=****, error=****]"
                .formatted(id, name, success);
    }
}
