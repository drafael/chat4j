package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.provider.support.ApiCredentialSource;
import com.github.drafael.chat4j.provider.support.ApiCredentialStatus;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public interface CredentialSource {

    static CredentialSource from(@NonNull CredentialResolver credentialResolver) {
        return new CredentialSource() {
            @Override
            public boolean hasRequiredCredentials(String envVar) {
                return credentialResolver.hasRequiredCredentials(envVar);
            }

            @Override
            public String resolveRequiredApiKey(String envVar) {
                return credentialResolver.resolveRequiredApiKey(envVar, null);
            }

            @Override
            public ApiCredentialStatus credentialStatus(String envVar) {
                return credentialResolver.resolveCredentialStatus(envVar, null);
            }
        };
    }

    boolean hasRequiredCredentials(String envVar);

    String resolveRequiredApiKey(String envVar);

    default ApiCredentialStatus credentialStatus(String envVar) {
        return hasRequiredCredentials(envVar)
                ? new ApiCredentialStatus(ApiCredentialSource.PROCESS_ENV, envVar, "")
                : new ApiCredentialStatus(ApiCredentialSource.MISSING, envVar, "");
    }

    default String requiredApiKeyOrBlank(String envVar) {
        return StringUtils.isBlank(envVar) || !hasRequiredCredentials(envVar) ? "" : resolveRequiredApiKey(envVar);
    }
}
