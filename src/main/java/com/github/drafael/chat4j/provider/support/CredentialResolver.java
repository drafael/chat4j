package com.github.drafael.chat4j.provider.support;

import com.formdev.flatlaf.util.SystemInfo;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;

public final class CredentialResolver {

    private final ApiTokenVault tokenVault;
    private final Map<String, String> processEnvironment;
    private final Map<String, String> shellEnvironment;

    public CredentialResolver(
            @NonNull ApiTokenVault tokenVault,
            Map<String, String> processEnvironment,
            Map<String, String> shellEnvironment
    ) {
        this.tokenVault = tokenVault;
        this.processEnvironment = processEnvironment == null ? emptyMap() : Map.copyOf(processEnvironment);
        this.shellEnvironment = shellEnvironment == null ? emptyMap() : Map.copyOf(shellEnvironment);
    }

    public boolean hasAnyProviderCredentials() {
        return CredentialTokenIds.supportedProviderEnvVars().stream()
                .map(tokenId -> resolveCredential(tokenId, null))
                .anyMatch(CredentialResolution::hasValue);
    }

    public boolean hasRequiredCredentials(String envVar) {
        return envVar == null || resolveCredential(envVar, null).hasValue();
    }

    public String resolveRequiredApiKey(String envVar, String fallbackApiKey) {
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

    public String resolveApiKey(String envVar, String fallbackApiKey) {
        return resolveCredential(envVar, fallbackApiKey).value();
    }

    public CredentialResolution resolveCredential(String envVar, String fallbackApiKey) {
        List<String> candidates = CredentialTokenIds.credentialCandidates(envVar);
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

    public ApiCredentialStatus resolveCredentialStatus(String envVar, String fallbackApiKey) {
        List<String> candidates = CredentialTokenIds.credentialCandidates(envVar);
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

    public String firstConfiguredCredentialId(String envVar) {
        CredentialResolution resolution = resolveCredential(envVar, null);
        return resolution.hasValue() ? resolution.credentialId() : null;
    }

    public static List<String> envVarCandidates(String envVar) {
        return CredentialTokenIds.candidates(envVar);
    }

    public static String canonicalTokenId(String envVar) {
        return CredentialTokenIds.canonicalTokenId(envVar);
    }

    boolean matchesEffectiveRawEnvironment(List<String> candidates, char[] token) {
        CredentialResolution rawCredential = resolveRawEnvironmentCredential(candidates);
        return rawCredential.hasValue() && equalsChars(rawCredential.value(), token);
    }

    private CredentialResolution resolveRawEnvironmentCredential(List<String> candidates) {
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

    private String processEnvValue(String name) {
        String value = processEnvironment.get(name);
        if (value == null && SystemInfo.isWindows) {
            value = processEnvironment.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        return StringUtils.trimToNull(value);
    }

    private String shellEnvValue(String name) {
        return StringUtils.trimToNull(shellEnvironment.get(name));
    }
}
