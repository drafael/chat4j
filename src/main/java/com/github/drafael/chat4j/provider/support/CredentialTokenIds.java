package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toUnmodifiableSet;

public final class CredentialTokenIds {

    private static final String ENV_VAR_SEPARATOR = "|";
    private static final String GEMINI_API_KEY = "GEMINI_API_KEY";
    private static final String GOOGLE_AI_API_KEY = "GOOGLEAI_API_KEY";
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
            GEMINI_API_KEY,
            GOOGLE_AI_API_KEY
    );
    private static final Set<String> SUPPORTED_PROVIDER_ENV_VAR_SET = SUPPORTED_PROVIDER_ENV_VARS.stream()
            .collect(toUnmodifiableSet());
    private static final Map<String, String> CANONICAL_TOKEN_IDS = Map.of(GOOGLE_AI_API_KEY, GEMINI_API_KEY);
    private static final Map<String, List<String>> SUPPORTED_ALIASES = Map.of(
            GEMINI_API_KEY,
            List.of(GEMINI_API_KEY, GOOGLE_AI_API_KEY)
    );

    private CredentialTokenIds() {
    }

    public static List<String> supportedProviderEnvVars() {
        return SUPPORTED_PROVIDER_ENV_VARS;
    }

    public static Set<String> supportedCanonicalTokenIds() {
        return SUPPORTED_PROVIDER_ENV_VARS.stream()
                .map(CredentialTokenIds::canonicalSupportedTokenId)
                .collect(toUnmodifiableSet());
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
        return candidates(envVarExpression).stream()
                .findFirst()
                .map(tokenId -> supported(tokenId) ? canonicalSupportedTokenId(tokenId) : tokenId)
                .orElse(null);
    }

    static List<String> credentialCandidates(String envVarExpression) {
        return candidates(envVarExpression).stream()
                .flatMap(tokenId -> supportedAliases(tokenId).stream())
                .distinct()
                .toList();
    }

    private static List<String> supportedAliases(String tokenId) {
        if (!supported(tokenId)) {
            return List.of(tokenId);
        }
        String canonicalTokenId = canonicalSupportedTokenId(tokenId);
        return SUPPORTED_ALIASES.getOrDefault(canonicalTokenId, List.of(canonicalTokenId));
    }

    public static boolean supported(String tokenId) {
        return SUPPORTED_PROVIDER_ENV_VAR_SET.contains(tokenId);
    }

    public static void validateSupportedTokenId(String tokenId) {
        if (StringUtils.isBlank(tokenId)) {
            throw new IllegalArgumentException("Token id is required.");
        }
        String trimmed = tokenId.trim();
        if (!tokenId.equals(trimmed) || !SAFE_TOKEN_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Unsupported token id format.");
        }
        if (!supported(trimmed)) {
            throw new IllegalArgumentException("Unsupported token id: %s".formatted(trimmed));
        }
    }

    static MutationTokenIds mutationTokenIds(String envVarExpression) {
        List<String> requestedTokenIds = candidates(envVarExpression);
        if (requestedTokenIds.isEmpty()) {
            throw new IllegalArgumentException("No API token id is configured.");
        }
        requestedTokenIds.forEach(CredentialTokenIds::validateSupportedTokenId);
        List<String> canonicalTokenIds = requestedTokenIds.stream()
                .map(CredentialTokenIds::canonicalSupportedTokenId)
                .distinct()
                .toList();
        if (canonicalTokenIds.size() != 1) {
            throw new IllegalArgumentException("API token expression contains unrelated token ids.");
        }
        String canonicalTokenId = canonicalTokenIds.getFirst();
        return new MutationTokenIds(
                canonicalTokenId,
                SUPPORTED_ALIASES.getOrDefault(canonicalTokenId, List.of(canonicalTokenId))
        );
    }

    private static String canonicalSupportedTokenId(String tokenId) {
        return CANONICAL_TOKEN_IDS.getOrDefault(tokenId, tokenId);
    }

    record MutationTokenIds(String canonicalTokenId, List<String> aliases) {
    }
}
