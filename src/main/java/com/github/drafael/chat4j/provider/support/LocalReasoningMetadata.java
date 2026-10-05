package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ReasoningOptions;

import java.util.Optional;

/** Distinguishes absent legacy choice metadata from advertised choices that cannot be resolved. */
public record LocalReasoningMetadata(ReasoningOptions resolvedOptions, boolean allowsLegacyFallback) {

    public static final LocalReasoningMetadata ABSENT = new LocalReasoningMetadata(null, true);
    public static final LocalReasoningMetadata UNRESOLVED = new LocalReasoningMetadata(null, false);

    public Optional<ReasoningOptions> options() {
        return Optional.ofNullable(resolvedOptions);
    }
}
