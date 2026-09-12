package com.github.drafael.chat4j.provider.support;

import java.util.Locale;
import java.util.Set;

public final class ModelFilters {

    private static final Set<String> EXCLUDED_KEYWORDS = Set.of(
        "embed",
        "whisper",
        "dall-e",
        "tts",
        "davinci",
        "babbage",
        "moderation"
    );

    private ModelFilters() {
    }

    public static boolean isSupportedChatModelId(String modelId) {
        String normalized = modelId.toLowerCase(Locale.ROOT);
        return EXCLUDED_KEYWORDS.stream().noneMatch(normalized::contains);
    }

    public static boolean isSupportedChatModelId(String providerName, String modelId) {
        boolean groqProvider = providerName != null && "Groq".equalsIgnoreCase(providerName.trim());
        return isSupportedChatModelId(modelId)
                && (!groqProvider || !modelId.toLowerCase(Locale.ROOT).startsWith("canopylabs/orpheus-"));
    }
}
