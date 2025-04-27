package com.github.drafael.chat4j.provider.capability.auth;

public interface CredentialStrategy {

    String resolveCredentialEnvVar(String envVarExpression);

    String resolveApiKey(String envVarExpression, String fallbackApiKey);
}
