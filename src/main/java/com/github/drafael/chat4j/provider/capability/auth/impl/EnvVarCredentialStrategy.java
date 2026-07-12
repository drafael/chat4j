package com.github.drafael.chat4j.provider.capability.auth.impl;

import com.github.drafael.chat4j.provider.capability.auth.CredentialStrategy;

import com.github.drafael.chat4j.provider.support.CredentialResolver;

public class EnvVarCredentialStrategy implements CredentialStrategy {

    @Override
    public String resolveCredentialEnvVar(String envVarExpression) {
        return CredentialResolver.firstConfiguredCredentialId(envVarExpression);
    }

    @Override
    public String resolveApiKey(String envVarExpression, String fallbackApiKey) {
        return CredentialResolver.resolveRequiredApiKey(envVarExpression, fallbackApiKey);
    }
}
