package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.auth.CredentialStrategy;
import com.github.drafael.chat4j.provider.capability.auth.impl.CliOAuthRunner;

public class ProviderFacade {

    private final CredentialStrategy credentialStrategy;
    private final CliOAuthRunner cliOAuthRunner;

    public ProviderFacade(CredentialStrategy credentialStrategy) {
        this(credentialStrategy, new CliOAuthRunner());
    }

    public ProviderFacade(CredentialStrategy credentialStrategy, CliOAuthRunner cliOAuthRunner) {
        this.credentialStrategy = credentialStrategy;
        this.cliOAuthRunner = cliOAuthRunner;
    }

    public ProviderRuntime resolveRuntime(
        ProviderDescriptor descriptor,
        String envVarExpression,
        String configuredBaseUrl,
        String selectedModel
    ) {
        String effectiveEnvVar = envVarExpression == null ? descriptor.credentialEnvVar() : envVarExpression;
        String baseUrl = descriptor.normalizeBaseUrl(
                configuredBaseUrl == null || configuredBaseUrl.isBlank()
                        ? descriptor.defaultBaseUrl()
                        : configuredBaseUrl
        );

        String selectedEnvVar = descriptor.authType() == AuthType.CLI_OAUTH
                ? null
                : credentialStrategy.resolveCredentialEnvVar(effectiveEnvVar);

        String apiKey = descriptor.authType() == AuthType.CLI_OAUTH
                ? (selectedModel == null
                    ? nullableToEmpty(cliOAuthRunner.resolveBearerTokenOrNull(descriptor.oauthCliSpec()))
                    : cliOAuthRunner.resolveBearerToken(descriptor.oauthCliSpec())
                )
                : credentialStrategy.resolveApiKey(effectiveEnvVar, descriptor.fallbackApiKey());

        return new ProviderRuntime(
            descriptor,
            selectedEnvVar,
            baseUrl,
            apiKey,
            selectedModel
        );
    }

    private String nullableToEmpty(String value) {
        return value == null ? "" : value;
    }
}
