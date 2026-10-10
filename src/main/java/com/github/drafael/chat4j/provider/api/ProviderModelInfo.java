package com.github.drafael.chat4j.provider.api;

import java.math.BigDecimal;
import lombok.Builder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.Validate;

/** Provider-reported metadata. Null fields mean unknown, not zero or unsupported. */
@Builder
public record ProviderModelInfo(
        String modelId,
        String displayName,
        String description,
        Long contextWindowTokens,
        Long maxInputTokens,
        Long maxOutputTokens,
        BigDecimal inputUsdPerMillion,
        BigDecimal outputUsdPerMillion
) {
    public ProviderModelInfo {
        Validate.notBlank(modelId, "modelId should not be blank");
        displayName = StringUtils.trimToNull(displayName);
        description = StringUtils.trimToNull(description);
        contextWindowTokens = positive(contextWindowTokens);
        maxInputTokens = positive(maxInputTokens);
        maxOutputTokens = positive(maxOutputTokens);
        inputUsdPerMillion = nonNegative(inputUsdPerMillion);
        outputUsdPerMillion = nonNegative(outputUsdPerMillion);
    }

    public boolean hasDetails() {
        return (displayName != null && !Strings.CS.equals(displayName, modelId))
                || description != null || contextWindowTokens != null || maxInputTokens != null
                || maxOutputTokens != null || inputUsdPerMillion != null || outputUsdPerMillion != null;
    }

    private static Long positive(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return value != null && value.signum() >= 0 ? value : null;
    }
}
