package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

public final class CredentialResolver {

    private static final String ENV_VAR_SEPARATOR = "|";

    private static final List<String> SUPPORTED_PROVIDER_ENV_VARS = List.of(
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "OPENROUTER_API_KEY",
        "GROQ_API_KEY",
        "DEEPSEEK_API_KEY",
        "MISTRAL_API_KEY",
        "XAI_API_KEY",
        "GEMINI_API_KEY",
        "GOOGLEAI_API_KEY",
        "GOOGLE_AI_API_KEY"
    );

    private static volatile Map<String, String> shellEnv = emptyMap();

    private CredentialResolver() {
    }

    public static void init(Map<String, String> env) {
        shellEnv = env == null ? emptyMap() : Map.copyOf(env);
    }

    public static String getenv(String name) {
        String value = System.getenv(name);
        return (value != null) ? value : shellEnv.get(name);
    }

    public static boolean hasAnyProviderCredentials() {
        return SUPPORTED_PROVIDER_ENV_VARS.stream()
                .map(CredentialResolver::getenv)
                .anyMatch(StringUtils::isNotBlank);
    }

    public static List<String> supportedProviderEnvVars() {
        return SUPPORTED_PROVIDER_ENV_VARS;
    }

    public static boolean hasRequiredCredentials(String envVar) {
        if (envVar == null) {
            return true;
        }
        return firstConfiguredEnvVar(envVar) != null;
    }

    public static String resolveRequiredApiKey(String envVar, String fallbackApiKey) {
        String apiKey = resolveApiKey(envVar, fallbackApiKey);
        if (StringUtils.isBlank(apiKey)) {
            String missing = envVar != null ? envVar : "API key";
            throw new IllegalStateException("%s not set".formatted(missing));
        }
        return apiKey;
    }

    public static String resolveApiKey(String envVar, String fallbackApiKey) {
        String configuredEnvVar = firstConfiguredEnvVar(envVar);
        if (configuredEnvVar != null) {
            return getenv(configuredEnvVar);
        }
        return fallbackApiKey;
    }

    public static String firstConfiguredEnvVar(String envVar) {
        return envVarCandidates(envVar).stream()
                .filter(candidate -> StringUtils.isNotBlank(getenv(candidate)))
                .findFirst()
                .orElse(null);
    }

    public static Map<String, String> mergedEnvironment() {
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        merged.putAll(shellEnv);
        return Map.copyOf(merged);
    }

    public static List<String> envVarCandidates(String envVar) {
        if (StringUtils.isBlank(envVar)) {
            return emptyList();
        }

        return Arrays.stream(envVar.split(Pattern.quote(ENV_VAR_SEPARATOR)))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }
}
