package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern RETIRED_GPT4O = Pattern.compile(
            "(?:openai/)?(?:gpt-4o(?:[-_]20\\d{2}[-_]\\d{2}[-_]\\d{2})?|chatgpt-4o-latest)(?::[^/]+)?",
            Pattern.CASE_INSENSITIVE
    );

    private ModelFilters() {
    }

    public static boolean isSupportedChatModelId(String modelId) {
        String normalized = modelId.toLowerCase(Locale.ROOT);
        return !isRetiredChatModelId(modelId) && EXCLUDED_KEYWORDS.stream().noneMatch(normalized::contains);
    }

    public static boolean isRetiredChatModelId(String modelId) {
        return RETIRED_GPT4O.matcher(StringUtils.trimToEmpty(modelId)).matches();
    }

    public static boolean isSupportedChatModelId(String providerName, String modelId) {
        boolean groqProvider = providerName != null && "Groq".equalsIgnoreCase(providerName.trim());
        return isSupportedChatModelId(modelId)
                && (!groqProvider || !modelId.toLowerCase(Locale.ROOT).startsWith("canopylabs/orpheus-"));
    }
}
