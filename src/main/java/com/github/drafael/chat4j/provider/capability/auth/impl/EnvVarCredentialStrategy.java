package com.github.drafael.chat4j.provider.capability.auth.impl;

import com.github.drafael.chat4j.provider.capability.auth.CredentialStrategy;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class EnvVarCredentialStrategy implements CredentialStrategy {

    @NonNull
    private final CredentialResolver credentialResolver;

    @Override
    public String resolveCredentialEnvVar(String envVarExpression) {
        return credentialResolver.firstConfiguredCredentialId(envVarExpression);
    }

    @Override
    public String resolveApiKey(String envVarExpression, String fallbackApiKey) {
        return credentialResolver.resolveRequiredApiKey(envVarExpression, fallbackApiKey);
    }
}
