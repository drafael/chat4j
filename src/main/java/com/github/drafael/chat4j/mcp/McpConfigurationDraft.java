package com.github.drafael.chat4j.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.NonNull;

import static java.util.Arrays.copyOf;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyMap;

public record McpConfigurationDraft(
        @NonNull McpConfiguration configuration,
        @NonNull Map<String, char[]> replacementSecrets
) {

    public McpConfigurationDraft {
        replacementSecrets = copySecrets(replacementSecrets);
    }

    public static McpConfigurationDraft withoutSecretChanges(@NonNull McpConfiguration configuration) {
        return new McpConfigurationDraft(configuration, emptyMap());
    }

    public McpConfigurationDraft copy() {
        return new McpConfigurationDraft(configuration, replacementSecrets);
    }

    public void clearSecrets() {
        replacementSecrets.values().forEach(value -> fill(value, '\0'));
    }

    @Override
    public String toString() {
        return "McpConfigurationDraft[configuration=%s, replacementSecrets=****]".formatted(configuration);
    }

    private static Map<String, char[]> copySecrets(Map<String, char[]> source) {
        Map<String, char[]> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, value == null ? null : copyOf(value, value.length)));
        return copy;
    }
}
