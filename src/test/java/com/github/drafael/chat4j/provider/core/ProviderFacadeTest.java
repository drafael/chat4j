package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.auth.CredentialStrategy;
import com.github.drafael.chat4j.provider.capability.auth.impl.CliOAuthRunner;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderFacadeTest {

    @Test
    @DisplayName("Copilot auth resolves API key from Chat4J Copilot auth resolver")
    void resolveRuntime_whenProviderUsesCopilotAuth_usesCopilotAuthResolver() {
        CredentialStrategy credentialStrategy = new CredentialStrategy() {
            @Override
            public String resolveCredentialEnvVar(String envVarExpression) {
                return null;
            }

            @Override
            public String resolveApiKey(String envVarExpression, String fallbackApiKey) {
                return "unexpected";
            }
        };

        CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver() {
            @Override
            public String resolveBearerToken() {
                return "copilot-token-required";
            }

            @Override
            public String resolveBearerTokenOrNull() {
                return "copilot-token-optional";
            }
        };

        var subject = new ProviderFacade(credentialStrategy, new CliOAuthRunner(), copilotAuthResolver);
        var descriptor = new ProviderDescriptor(
                "GitHub Copilot",
                AuthType.COPILOT_OAUTH,
                null,
                null,
                null,
                "https://api.githubcopilot.com",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity()
        );

        var runtimeWithoutModel = subject.resolveRuntime(descriptor, null, null, null);
        var runtimeWithModel = subject.resolveRuntime(descriptor, null, null, "gpt-4.1");

        assertThat(runtimeWithoutModel.apiKey()).isEqualTo("copilot-token-optional");
        assertThat(runtimeWithModel.apiKey()).isEqualTo("copilot-token-required");
    }

    @Test
    @DisplayName("Copilot auth keeps resolver token unchanged for runtime usage")
    void resolveRuntime_whenProviderUsesCopilotAuth_keepsTokenAsResolved() {
        CredentialStrategy credentialStrategy = new CredentialStrategy() {
            @Override
            public String resolveCredentialEnvVar(String envVarExpression) {
                return null;
            }

            @Override
            public String resolveApiKey(String envVarExpression, String fallbackApiKey) {
                return "unexpected";
            }
        };

        CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver() {
            @Override
            public String resolveBearerToken() {
                return "gho_source_token";
            }

            @Override
            public String resolveBearerTokenOrNull() {
                return "gho_source_token";
            }
        };

        var subject = new ProviderFacade(credentialStrategy, new CliOAuthRunner(), copilotAuthResolver);
        var descriptor = new ProviderDescriptor(
                "GitHub Copilot",
                AuthType.COPILOT_OAUTH,
                null,
                null,
                null,
                "https://api.githubcopilot.com",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity()
        );

        var runtime = subject.resolveRuntime(descriptor, null, null, "claude-sonnet-4.6");

        assertThat(runtime.apiKey()).isEqualTo("gho_source_token");
    }
}
