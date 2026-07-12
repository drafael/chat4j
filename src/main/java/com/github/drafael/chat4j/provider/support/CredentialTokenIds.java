package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toUnmodifiableSet;

public final class CredentialTokenIds {

    private static final String ENV_VAR_SEPARATOR = "|";
    private static final Pattern SAFE_TOKEN_ID = Pattern.compile("[A-Z][A-Z0-9_]*");

    private static final List<String> SUPPORTED_PROVIDER_ENV_VARS = List.of(
            "ANTHROPIC_API_KEY",
            "OPENAI_API_KEY",
            "OPENROUTER_API_KEY",
            "GROQ_API_KEY",
            "ELEVENLABS_API_KEY",
            "DEEPGRAM_API_KEY",
            "ASSEMBLYAI_API_KEY",
            "DEEPSEEK_API_KEY",
            "MISTRAL_API_KEY",
            "XAI_API_KEY",
            "PERPLEXITY_API_KEY",
            "GEMINI_API_KEY",
            "GOOGLEAI_API_KEY"
    );
    private static final Set<String> SUPPORTED_PROVIDER_ENV_VAR_SET = SUPPORTED_PROVIDER_ENV_VARS.stream()
            .collect(toUnmodifiableSet());

    private CredentialTokenIds() {
    }

    public static List<String> supportedProviderEnvVars() {
        return SUPPORTED_PROVIDER_ENV_VARS;
    }

    public static List<String> candidates(String envVarExpression) {
        return StringUtils.isBlank(envVarExpression)
                ? emptyList()
                : Arrays.stream(envVarExpression.split(Pattern.quote(ENV_VAR_SEPARATOR)))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    public static String canonicalTokenId(String envVarExpression) {
        return candidates(envVarExpression).stream().findFirst().orElse(null);
    }

    public static boolean supported(String tokenId) {
        return SUPPORTED_PROVIDER_ENV_VAR_SET.contains(tokenId);
    }

    public static void validateSupportedTokenId(String tokenId) {
        if (StringUtils.isBlank(tokenId)) {
            throw new IllegalArgumentException("Token id is required.");
        }
        String trimmed = tokenId.trim();
        if (!SAFE_TOKEN_ID.matcher(trimmed).matches() || trimmed.contains("/") || trimmed.contains("\\")) {
            throw new IllegalArgumentException("Unsupported token id format.");
        }
        if (!supported(trimmed)) {
            throw new IllegalArgumentException("Unsupported token id: %s".formatted(trimmed));
        }
    }
}
