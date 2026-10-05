package com.github.drafael.chat4j.provider.api;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import java.util.List;

/** Supported choices and the resolved fallback for one model's reasoning control. */
public record ReasoningOptions(@NonNull List<ReasoningLevel> levels, @NonNull ReasoningLevel defaultLevel) {

    public static final ReasoningOptions UNAVAILABLE = new ReasoningOptions(List.of(ReasoningLevel.OFF), ReasoningLevel.OFF);

    public ReasoningOptions {
        Validate.notEmpty(levels, "levels must contain at least one supported reasoning level");
        levels = List.copyOf(levels).stream().distinct().sorted().toList();
        Validate.isTrue(levels.contains(defaultLevel), "defaultLevel must be a supported reasoning level");
    }

    public static ReasoningOptions of(@NonNull List<ReasoningLevel> levels) {
        return of(levels, ReasoningLevel.MEDIUM);
    }

    public static ReasoningOptions of(@NonNull List<ReasoningLevel> levels, @NonNull ReasoningLevel recommended) {
        ReasoningLevel fallback = levels.contains(recommended) ? recommended
                : levels.contains(ReasoningLevel.MEDIUM) ? ReasoningLevel.MEDIUM
                : levels.stream().filter(ReasoningLevel::enabled).min(ReasoningLevel::compareTo).orElse(ReasoningLevel.OFF);
        return new ReasoningOptions(levels, fallback);
    }

    public ReasoningLevel select(@NonNull ReasoningLevel current) {
        return levels.contains(current) ? current : defaultLevel;
    }
}
