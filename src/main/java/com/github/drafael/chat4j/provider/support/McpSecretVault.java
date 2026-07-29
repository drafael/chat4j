package com.github.drafael.chat4j.provider.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import static java.util.Arrays.copyOf;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyMap;

@RequiredArgsConstructor
public final class McpSecretVault {

    @NonNull
    private final ApiTokenVault tokenVault;

    public ApiTokenLookup lookup(String secretId) {
        return tokenVault.readMcpTokenChars(secretId);
    }

    public Set<String> secretIds() {
        return Set.copyOf(tokenVault.mcpTokenIds());
    }

    public void publish(@NonNull Map<String, char[]> upserts, @NonNull Set<String> removals) {
        Map<String, char[]> owned = deepCopy(upserts);
        try {
            tokenVault.applyMcpBatch(owned, Set.copyOf(removals));
        } finally {
            owned.values().stream().filter(Objects::nonNull)
                    .forEach(value -> fill(value, '\0'));
        }
    }

    public void remove(@NonNull Set<String> secretIds) {
        publish(emptyMap(), secretIds);
    }

    private Map<String, char[]> deepCopy(Map<String, char[]> source) {
        Map<String, char[]> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, value == null ? null : copyOf(value, value.length)));
        return copy;
    }
}
