package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.provider.support.ApiCredentialSource;
import com.github.drafael.chat4j.provider.support.ApiCredentialStatus;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public interface SpeechToTextProvider {

    String id();

    String displayName();

    String requiredEnvVar();

    SpeechToTextCatalogItem defaultModel();

    List<SpeechToTextCatalogItem> bundledModels();

    default boolean supportsLocalModels() {
        return false;
    }

    default boolean available(CredentialSource credentialSource) {
        String envVar = requiredEnvVar();
        return StringUtils.isBlank(envVar) || credentialSource != null && credentialSource.hasRequiredCredentials(envVar);
    }

    default String unavailableLabel() {
        return StringUtils.isBlank(requiredEnvVar())
                ? "%s (unavailable)".formatted(displayName())
                : "%s (requires %s)".formatted(displayName(), requiredEnvVar());
    }

    default String unavailableMessage() {
        return StringUtils.isBlank(requiredEnvVar())
                ? "%s is not available.".formatted(displayName())
                : "%s requires %s.".formatted(displayName(), requiredEnvVar());
    }

    default String availableMessage() {
        if (StringUtils.isBlank(requiredEnvVar())) {
            return "Using %s.".formatted(displayName());
        }
        ApiCredentialStatus status = CredentialResolver.resolveCredentialStatus(requiredEnvVar(), null);
        if (status.source() == ApiCredentialSource.SAVED_TOKEN) {
            return "Using %s with entered/stored API token.".formatted(displayName());
        }
        if (status.source() == ApiCredentialSource.SHELL_ENV) {
            return "Using %s with shell environment variable %s.".formatted(displayName(), status.credentialId());
        }
        return "Using %s with environment variable %s.".formatted(displayName(), status.credentialId() == null ? requiredEnvVar() : status.credentialId());
    }

    default SpeechToTextCatalogItem normalizeModelSelection(SpeechToTextCatalogItem model) {
        return model;
    }

    List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception;

    SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception;
}
