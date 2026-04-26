package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.auth.CredentialStrategy;
import com.github.drafael.chat4j.provider.capability.auth.impl.CliOAuthRunner;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderFacadeTest {

    @Test
    @DisplayName("Copilot auth resolves API key from Chat4J Copilot auth resolver")
    void resolveRuntime_whenProviderUsesCopilotAuth_usesCopilotAuthResolver() throws Exception {
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

        var subject = new ProviderFacade(
                credentialStrategy,
                new CliOAuthRunner(),
                copilotAuthResolver,
                new CopilotModelMetadataStore(Files.createTempDirectory("copilot-metadata-store"))
        );
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
        assertThat(runtimeWithModel.selectedModelSupportedEndpoints()).isEmpty();
    }

    @Test
    @DisplayName("Copilot auth keeps resolver token unchanged for runtime usage")
    void resolveRuntime_whenProviderUsesCopilotAuth_keepsTokenAsResolved() throws Exception {
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

        var metadataStore = new CopilotModelMetadataStore(Files.createTempDirectory("copilot-metadata-store"));
        metadataStore.update(
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("claude-sonnet-4.6", List.of("/chat/completions")))
        );

        var subject = new ProviderFacade(credentialStrategy, new CliOAuthRunner(), copilotAuthResolver, metadataStore);
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
        assertThat(runtime.selectedModelSupportedEndpoints()).containsExactly("/chat/completions");
    }

    @Test
    @DisplayName("Codex auth resolves API key from Chat4J Codex auth resolver")
    void resolveRuntime_whenProviderUsesCodexAuth_usesCodexAuthResolver() throws Exception {
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
                return "copilot-token";
            }

            @Override
            public String resolveBearerTokenOrNull() {
                return "copilot-token";
            }
        };

        CodexAuthResolver codexAuthResolver = new CodexAuthResolver() {
            @Override
            public String resolveBearerToken() {
                return "codex-token-required";
            }

            @Override
            public String resolveBearerTokenOrNull() {
                return "codex-token-optional";
            }
        };

        var subject = new ProviderFacade(
                credentialStrategy,
                new CliOAuthRunner(),
                copilotAuthResolver,
                codexAuthResolver,
                new CopilotModelMetadataStore(Files.createTempDirectory("copilot-metadata-store"))
        );
        var descriptor = new ProviderDescriptor(
                "OpenAI Codex",
                AuthType.CODEX_OAUTH,
                null,
                null,
                null,
                "https://api.openai.com/v1",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity()
        );

        var runtimeWithoutModel = subject.resolveRuntime(descriptor, null, null, null);
        var runtimeWithModel = subject.resolveRuntime(descriptor, null, null, "codex-mini-latest");

        assertThat(runtimeWithoutModel.apiKey()).isEqualTo("codex-token-optional");
        assertThat(runtimeWithModel.apiKey()).isEqualTo("codex-token-required");
    }
}
