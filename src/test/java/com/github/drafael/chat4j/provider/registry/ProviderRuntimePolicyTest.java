package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderModule;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderRuntimePolicyTest {

    @Test
    @DisplayName("Copilot auth providers are unavailable when resolver reports unauthorized")
    void hasRequiredCredentials_whenCopilotAuthResolverIsUnauthorized_returnsFalse() {
        CopilotAuthResolver resolver = new CopilotAuthResolver() {
            @Override
            public CopilotAuthResolver.CopilotAuthStatus resolveStatus() {
                return CopilotAuthResolver.CopilotAuthStatus.unauthorized("not authorized");
            }
        };

        var subject = new ProviderRuntimePolicy(resolver);
        var providerDefinition = copilotAuthProvider("GitHub Copilot");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isFalse();
    }

    @Test
    @DisplayName("Copilot auth providers are available when resolver reports authorized")
    void hasRequiredCredentials_whenCopilotAuthResolverIsAuthorized_returnsTrue() {
        CopilotAuthResolver resolver = new CopilotAuthResolver() {
            @Override
            public CopilotAuthResolver.CopilotAuthStatus resolveStatus() {
                return CopilotAuthResolver.CopilotAuthStatus.authorized("ok", "Chat4J OAuth");
            }
        };

        var subject = new ProviderRuntimePolicy(resolver);
        var providerDefinition = copilotAuthProvider("GitHub Copilot");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isTrue();
    }

    @Test
    @DisplayName("Codex auth providers are unavailable when resolver reports unauthorized")
    void hasRequiredCredentials_whenCodexAuthResolverIsUnauthorized_returnsFalse() {
        CodexAuthResolver resolver = new CodexAuthResolver() {
            @Override
            public CodexAuthResolver.CodexAuthStatus resolveStatus() {
                return CodexAuthResolver.CodexAuthStatus.unauthorized("not authorized");
            }
        };

        var subject = new ProviderRuntimePolicy(new CopilotAuthResolver(), resolver);
        var providerDefinition = codexAuthProvider("OpenAI Codex");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isFalse();
    }

    @Test
    @DisplayName("Codex auth providers are available when resolver reports authorized")
    void hasRequiredCredentials_whenCodexAuthResolverIsAuthorized_returnsTrue() {
        CodexAuthResolver resolver = new CodexAuthResolver() {
            @Override
            public CodexAuthResolver.CodexAuthStatus resolveStatus() {
                return CodexAuthResolver.CodexAuthStatus.authorized("ok", "Chat4J OAuth");
            }
        };

        var subject = new ProviderRuntimePolicy(new CopilotAuthResolver(), resolver);
        var providerDefinition = codexAuthProvider("OpenAI Codex");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isTrue();
    }

    @Test
    @DisplayName("Effective base URL trims runtime override whitespace")
    void effectiveBaseUrl_whenRuntimeBaseUrlHasWhitespace_returnsTrimmedValue() {
        var subject = new ProviderRuntimePolicy();
        ProviderDefinition providerDefinition = envVarProvider("Ollama", "http://localhost:11434/v1");
        subject.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRuntimePolicy.RuntimeConfig(true, "  http://127.0.0.1:11434/v1  ")
        ));

        String baseUrl = subject.effectiveBaseUrl(providerDefinition);

        assertThat(baseUrl).isEqualTo("http://127.0.0.1:11434/v1");
    }

    @Test
    @DisplayName("Effective base URL falls back to provider default for blank runtime override")
    void effectiveBaseUrl_whenRuntimeBaseUrlIsBlank_returnsProviderDefault() {
        var subject = new ProviderRuntimePolicy();
        ProviderDefinition providerDefinition = envVarProvider("Ollama", "http://localhost:11434/v1");
        subject.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRuntimePolicy.RuntimeConfig(true, "   ")
        ));

        String baseUrl = subject.effectiveBaseUrl(providerDefinition);

        assertThat(baseUrl).isEqualTo("http://localhost:11434/v1");
    }

    private ProviderDefinition copilotAuthProvider(String name) {
        var descriptor = new ProviderDescriptor(
                name,
                AuthType.COPILOT_OAUTH,
                null,
                null,
                "https://api.githubcopilot.com",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity()
        );

        ProviderModule module = new ProviderModule() {
            @Override
            public ProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ChatCompletionClient chatCompletionClient() {
                throw new UnsupportedOperationException("Not required in this test");
            }

            @Override
            public ModelCatalogClient modelCatalogClient() {
                throw new UnsupportedOperationException("Not required in this test");
            }
        };

        return new ProviderDefinition(descriptor, module);
    }

    private ProviderDefinition envVarProvider(String name, String baseUrl) {
        var descriptor = new ProviderDescriptor(
                name,
                AuthType.ENV_VAR,
                "API_KEY",
                null,
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity()
        );

        return providerDefinition(descriptor);
    }

    private ProviderDefinition codexAuthProvider(String name) {
        var descriptor = new ProviderDescriptor(
                name,
                AuthType.CODEX_OAUTH,
                null,
                null,
                "https://api.openai.com/v1",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity()
        );

        return providerDefinition(descriptor);
    }

    private ProviderDefinition providerDefinition(ProviderDescriptor descriptor) {
        ProviderModule module = new ProviderModule() {
            @Override
            public ProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ChatCompletionClient chatCompletionClient() {
                throw new UnsupportedOperationException("Not required in this test");
            }

            @Override
            public ModelCatalogClient modelCatalogClient() {
                throw new UnsupportedOperationException("Not required in this test");
            }
        };

        return new ProviderDefinition(descriptor, module);
    }
}
