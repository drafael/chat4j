package com.github.drafael.chat4j.provider.modules;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.OAuthCliSpec;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.CodexCliChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.OpenAiChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.capability.models.impl.OpenAiModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderModule;
import com.github.drafael.chat4j.provider.support.BaseUrlNormalizer;

import static java.util.Collections.emptyList;

public class OpenAiCompatibleModule implements ProviderModule {

    private final ProviderDescriptor descriptor;
    private final ChatCompletionClient chatCompletionClient;
    private final ModelCatalogClient modelCatalogClient = new OpenAiModelCatalogClient();

    public OpenAiCompatibleModule(
        String providerName,
        String credentialEnvVar,
        String fallbackApiKey,
        String defaultBaseUrl
    ) {
        this(providerName, AuthType.ENV_VAR, credentialEnvVar, fallbackApiKey, null, defaultBaseUrl);
    }

    public OpenAiCompatibleModule(
        String providerName,
        AuthType authType,
        String credentialEnvVar,
        String fallbackApiKey,
        OAuthCliSpec oauthCliSpec,
        String defaultBaseUrl
    ) {
        this.descriptor = new ProviderDescriptor(
            providerName,
            authType,
            credentialEnvVar,
            fallbackApiKey,
            oauthCliSpec,
            defaultBaseUrl,
            emptyList(),
            declaredCapabilities(providerName),
            configuredBaseUrl -> BaseUrlNormalizer.normalize(configuredBaseUrl, defaultBaseUrl));
        this.chatCompletionClient = selectChatClient(providerName, authType);
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ChatCompletionClient chatCompletionClient() {
        return chatCompletionClient;
    }

    private ChatCompletionClient selectChatClient(String providerName, AuthType authType) {
        return authType == AuthType.CLI_OAUTH && "OpenAI Codex".equals(providerName)
                ? new CodexCliChatCompletionClient()
                : new OpenAiChatCompletionClient();
    }

    @Override
    public ModelCatalogClient modelCatalogClient() {
        return modelCatalogClient;
    }

    private ProviderCapabilities declaredCapabilities(String providerName) {
        return switch (providerName) {
            case "OpenAI", "Google AI", "OpenRouter" -> ProviderCapabilities.chatModelsAndImages();
            default -> ProviderCapabilities.chatAndModels();
        };
    }
}
