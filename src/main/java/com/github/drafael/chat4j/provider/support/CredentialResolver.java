package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Collections.emptyMap;

public final class CredentialResolver {

    private static volatile Map<String, String> shellEnv = emptyMap();
    private static volatile Function<String, String> processEnv = System::getenv;
    private static volatile ApiTokenVault tokenVault = ApiTokenVault.defaultVault();

    private CredentialResolver() {
    }

    public static void init(Map<String, String> env) {
        shellEnv = env == null ? emptyMap() : Map.copyOf(env);
        tokenVault.refreshFromDiskReadOnly();
    }

    public static void configureTokenVault(ApiTokenVault vault) {
        tokenVault = vault == null ? ApiTokenVault.defaultVault() : vault;
        tokenVault.refreshFromDiskReadOnly();
    }

    static void configureProcessEnv(Function<String, String> processEnvSupplier) {
        processEnv = processEnvSupplier == null ? System::getenv : processEnvSupplier;
    }

    public static String getenv(String name) {
        String processValue = processEnvValue(name);
        return StringUtils.isNotBlank(processValue) ? processValue : shellEnvValue(name);
    }

    public static boolean hasAnyProviderCredentials() {
        return supportedProviderEnvVars().stream()
                .map(tokenId -> resolveCredential(tokenId, null))
                .anyMatch(CredentialResolution::hasValue);
    }

    public static List<String> supportedProviderEnvVars() {
        return CredentialTokenIds.supportedProviderEnvVars();
    }

    public static boolean hasRequiredCredentials(String envVar) {
        if (envVar == null) {
            return true;
        }
        return resolveCredential(envVar, null).hasValue();
    }

    public static String resolveRequiredApiKey(String envVar, String fallbackApiKey) {
        CredentialResolution resolution = resolveCredential(envVar, fallbackApiKey);
        if (resolution.source() == ApiCredentialSource.ERROR) {
            throw new IllegalStateException(StringUtils.defaultIfBlank(
                    resolution.errorMessage(),
                    "Saved API token could not be read. Repair/recreate the token vault or clear the saved token."
            ));
        }
        if (StringUtils.isBlank(resolution.value())) {
            String missing = envVar != null ? envVar : "API key";
            throw new IllegalStateException("%s not set".formatted(missing));
        }
        return resolution.value();
    }

    public static String resolveApiKey(String envVar, String fallbackApiKey) {
        return resolveCredential(envVar, fallbackApiKey).value();
    }

    public static CredentialResolution resolveCredential(String envVar, String fallbackApiKey) {
        List<String> candidates = envVarCandidates(envVar);
        for (String candidate : candidates) {
            if (!CredentialTokenIds.supported(candidate)) {
                continue;
            }
            try (ApiTokenLookup lookup = tokenVault.readTokenChars(candidate)) {
                if (lookup.source() == ApiCredentialSource.ERROR) {
                    return CredentialResolution.error(candidate, savedTokenReadError(candidate));
                }
                if (lookup.present()) {
                    char[] token = lookup.token();
                    try {
                        return CredentialResolution.of(ApiCredentialSource.SAVED_TOKEN, candidate, new String(token));
                    } finally {
                        Arrays.fill(token, '\0');
                    }
                }
            }
        }
        CredentialResolution rawEnvironmentCredential = resolveRawEnvironmentCredential(candidates);
        if (rawEnvironmentCredential.hasValue()) {
            return rawEnvironmentCredential;
        }
        if (StringUtils.isNotBlank(fallbackApiKey)) {
            return CredentialResolution.of(ApiCredentialSource.FALLBACK, null, fallbackApiKey);
        }
        return CredentialResolution.missing();
    }

    public static ApiCredentialStatus resolveCredentialStatus(String envVar, String fallbackApiKey) {
        List<String> candidates = envVarCandidates(envVar);
        for (String candidate : candidates) {
            if (!CredentialTokenIds.supported(candidate)) {
                continue;
            }
            ApiCredentialStatus savedStatus = tokenVault.status(candidate);
            if (savedStatus.source() == ApiCredentialSource.SAVED_TOKEN || savedStatus.source() == ApiCredentialSource.ERROR) {
                return savedStatus;
            }
        }
        CredentialResolution rawEnvironmentCredential = resolveRawEnvironmentCredential(candidates);
        if (rawEnvironmentCredential.hasValue()) {
            return new ApiCredentialStatus(rawEnvironmentCredential.source(), rawEnvironmentCredential.credentialId(), "");
        }
        return StringUtils.isNotBlank(fallbackApiKey)
                ? new ApiCredentialStatus(ApiCredentialSource.FALLBACK, null, "")
                : new ApiCredentialStatus(ApiCredentialSource.MISSING, null, "");
    }

    public static String firstConfiguredEnvVar(String envVar) {
        return envVarCandidates(envVar).stream()
                .filter(candidate -> StringUtils.isNotBlank(getenv(candidate)))
                .findFirst()
                .orElse(null);
    }

    public static String firstConfiguredCredentialId(String envVar) {
        CredentialResolution resolution = resolveCredential(envVar, null);
        return resolution.hasValue() ? resolution.credentialId() : null;
    }

    public static boolean hasSavedTokenRecord(String tokenId) {
        return tokenVault.hasRecord(tokenId);
    }

    public static SaveTokenResult saveTokenOverride(String envVarExpression, char[] token) {
        List<String> candidates = envVarCandidates(envVarExpression);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No API token id is configured.");
        }
        String canonicalTokenId = candidates.getFirst();
        boolean savedRecordExists = candidates.stream().anyMatch(CredentialResolver::hasSavedTokenRecord);
        if (isBlank(token)) {
            if (savedRecordExists) {
                tokenVault.deleteTokens(candidates);
                tokenVault.refreshFromDiskReadOnly();
                return new SaveTokenResult(canonicalTokenId, true, false, true);
            }
            tokenVault.refreshFromDiskReadOnly();
            return new SaveTokenResult(canonicalTokenId, false, false, false);
        }
        if (matchesEffectiveRawEnvironment(candidates, token)) {
            if (savedRecordExists) {
                tokenVault.deleteTokens(candidates);
                tokenVault.refreshFromDiskReadOnly();
                return new SaveTokenResult(canonicalTokenId, true, false, true);
            }
            tokenVault.refreshFromDiskReadOnly();
            return new SaveTokenResult(canonicalTokenId, false, false, false);
        }
        tokenVault.saveToken(canonicalTokenId, token);
        candidates.stream()
                .skip(1)
                .forEach(tokenVault::deleteToken);
        tokenVault.refreshFromDiskReadOnly();
        return new SaveTokenResult(canonicalTokenId, false, true, true);
    }

    public static void recreateTokenVault() {
        tokenVault.recreateVault();
        tokenVault.refreshFromDiskReadOnly();
    }

    public static Map<String, String> mergedEnvironment() {
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        merged.putAll(shellEnv);
        return Map.copyOf(merged);
    }

    public static List<String> envVarCandidates(String envVar) {
        return CredentialTokenIds.candidates(envVar);
    }

    public static String canonicalTokenId(String envVar) {
        return CredentialTokenIds.canonicalTokenId(envVar);
    }

    private static boolean matchesEffectiveRawEnvironment(List<String> candidates, char[] token) {
        CredentialResolution rawCredential = resolveRawEnvironmentCredential(candidates);
        return rawCredential.hasValue() && equalsChars(rawCredential.value(), token);
    }

    private static CredentialResolution resolveRawEnvironmentCredential(List<String> candidates) {
        for (String candidate : candidates) {
            String processValue = processEnvValue(candidate);
            if (StringUtils.isNotBlank(processValue)) {
                return CredentialResolution.of(ApiCredentialSource.PROCESS_ENV, candidate, processValue);
            }
        }
        for (String candidate : candidates) {
            String shellValue = shellEnvValue(candidate);
            if (StringUtils.isNotBlank(shellValue)) {
                return CredentialResolution.of(ApiCredentialSource.SHELL_ENV, candidate, shellValue);
            }
        }
        return CredentialResolution.missing();
    }

    private static String savedTokenReadError(String credentialId) {
        return "Saved API token for %s could not be read. Repair/recreate the token vault or clear the saved token."
                .formatted(credentialId);
    }

    private static boolean equalsChars(String value, char[] token) {
        if (value == null || token == null || value.length() != token.length) {
            return false;
        }
        for (int i = 0; i < token.length; i++) {
            if (value.charAt(i) != token[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(char[] token) {
        if (token == null || token.length == 0) {
            return true;
        }
        for (char c : token) {
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static String processEnvValue(String name) {
        return StringUtils.trimToNull(processEnv.apply(name));
    }

    private static String shellEnvValue(String name) {
        return StringUtils.trimToNull(shellEnv.get(name));
    }

    public record SaveTokenResult(String canonicalTokenId, boolean cleared, boolean savedOverride, boolean changed) {
    }
}
