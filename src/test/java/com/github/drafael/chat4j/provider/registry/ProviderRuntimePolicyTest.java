package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.auth.impl.CliOAuthRunner;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderModule;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

        var subject = new ProviderRuntimePolicy(new CliOAuthRunner(), resolver);
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

        var subject = new ProviderRuntimePolicy(new CliOAuthRunner(), resolver);
        var providerDefinition = copilotAuthProvider("GitHub Copilot");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isTrue();
    }

    private ProviderDefinition copilotAuthProvider(String name) {
        var descriptor = new ProviderDescriptor(
                name,
                AuthType.COPILOT_OAUTH,
                null,
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
}
