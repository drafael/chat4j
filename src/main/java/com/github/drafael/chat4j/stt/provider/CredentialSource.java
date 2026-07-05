package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.provider.support.CredentialResolver;
import org.apache.commons.lang3.StringUtils;

public interface CredentialSource {

    CredentialSource SYSTEM = new CredentialSource() {
        @Override
        public boolean hasRequiredCredentials(String envVar) {
            return CredentialResolver.hasRequiredCredentials(envVar);
        }

        @Override
        public String resolveRequiredApiKey(String envVar) {
            return CredentialResolver.resolveRequiredApiKey(envVar, null);
        }
    };

    boolean hasRequiredCredentials(String envVar);

    String resolveRequiredApiKey(String envVar);

    default String requiredApiKeyOrBlank(String envVar) {
        return StringUtils.isBlank(envVar) || !hasRequiredCredentials(envVar) ? "" : resolveRequiredApiKey(envVar);
    }
}
