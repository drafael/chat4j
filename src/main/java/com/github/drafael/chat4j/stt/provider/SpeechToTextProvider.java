package com.github.drafael.chat4j.stt.provider;

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
        return StringUtils.isBlank(requiredEnvVar())
                ? "Using %s.".formatted(displayName())
                : "Using %s with environment variable %s.".formatted(displayName(), requiredEnvVar());
    }

    default SpeechToTextCatalogItem normalizeModelSelection(SpeechToTextCatalogItem model) {
        return model;
    }

    List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception;

    SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception;
}
