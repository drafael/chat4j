package com.github.drafael.chat4j.mcp;

import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

import static java.lang.String.join;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;

public record McpServerConfiguration(
        String id,
        String name,
        String modelId,
        boolean enabled,
        boolean automatic,
        McpTransportType transport,
        String endpoint,
        String executable,
        List<String> arguments,
        List<McpSecretReference> headers,
        List<McpSecretReference> environment,
        boolean longRunning,
        Set<String> disabledTools
) {

    public McpServerConfiguration {
        id = StringUtils.defaultString(id);
        name = StringUtils.defaultString(name);
        modelId = StringUtils.defaultString(modelId);
        transport = transport == null ? McpTransportType.STDIO : transport;
        endpoint = StringUtils.defaultString(endpoint);
        executable = StringUtils.defaultString(executable);
        arguments = arguments == null ? emptyList() : List.copyOf(arguments);
        headers = headers == null ? emptyList() : List.copyOf(headers);
        environment = environment == null ? emptyList() : List.copyOf(environment);
        disabledTools = disabledTools == null ? emptySet() : Set.copyOf(disabledTools);
    }

    public String displayName() {
        return StringUtils.defaultIfBlank(name, modelId);
    }

    @Override
    public String toString() {
        return join(
                "",
                "McpServerConfiguration[id=%s, name=****, modelId=****, enabled=%s, automatic=%s, transport=%s, ",
                "endpoint=****, executable=****, arguments=****, headers=%d, environment=%d, longRunning=%s, ",
                "disabledTools=****]"
        ).formatted(
                        id,
                        enabled,
                        automatic,
                        transport,
                        headers.size(),
                        environment.size(),
                        longRunning
                );
    }
}
