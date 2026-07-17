package com.github.drafael.chat4j.provider.modules;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.CodexCliChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.GoogleAiGenerateContentClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.OpenAiChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.PerplexityChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.capability.models.impl.OpenAiModelCatalogClient;
import com.github.drafael.chat4j.provider.capability.models.impl.PerplexityModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderModule;
import com.github.drafael.chat4j.provider.support.BaseUrlNormalizer;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;

import java.util.List;

import static java.util.Collections.emptyList;

public class OpenAiCompatibleModule implements ProviderModule {

    private final ProviderDescriptor descriptor;
    private final ChatCompletionClient chatCompletionClient;
    private final ModelCatalogClient modelCatalogClient;

    public OpenAiCompatibleModule(
        String providerName,
        String credentialEnvVar,
        String fallbackApiKey,
        String defaultBaseUrl
    ) {
        this(providerName, AuthType.ENV_VAR, credentialEnvVar, fallbackApiKey, defaultBaseUrl, CopilotModelMetadataStore.sharedDefault());
    }

    public OpenAiCompatibleModule(
        String providerName,
        AuthType authType,
        String credentialEnvVar,
        String fallbackApiKey,
        String defaultBaseUrl,
        CopilotModelMetadataStore copilotModelMetadataStore
    ) {
        this(
                providerName,
                authType,
                credentialEnvVar,
                fallbackApiKey,
                defaultBaseUrl,
                copilotModelMetadataStore,
                emptyList(),
                declaredCapabilities(providerName)
        );
    }

    public OpenAiCompatibleModule(
        String providerName,
        AuthType authType,
        String credentialEnvVar,
        String fallbackApiKey,
        String defaultBaseUrl,
        CopilotModelMetadataStore copilotModelMetadataStore,
        List<String> seedModels,
        ProviderCapabilities capabilities
    ) {
        this.descriptor = new ProviderDescriptor(
            providerName,
            authType,
            credentialEnvVar,
            fallbackApiKey,
            defaultBaseUrl,
            seedModels,
            capabilities,
            configuredBaseUrl -> BaseUrlNormalizer.normalize(configuredBaseUrl, defaultBaseUrl));
        this.chatCompletionClient = selectChatClient(providerName);
        this.modelCatalogClient = selectModelCatalogClient(providerName, seedModels, copilotModelMetadataStore);
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ChatCompletionClient chatCompletionClient() {
        return chatCompletionClient;
    }

    private ChatCompletionClient selectChatClient(String providerName) {
        return switch (providerName) {
            case "OpenAI Codex" -> new CodexCliChatCompletionClient();
            case "Perplexity" -> new PerplexityChatCompletionClient();
            case "Google AI" -> new GoogleAiGenerateContentClient(new OpenAiChatCompletionClient());
            default -> new OpenAiChatCompletionClient();
        };
    }

    @Override
    public ModelCatalogClient modelCatalogClient() {
        return modelCatalogClient;
    }

    private ModelCatalogClient selectModelCatalogClient(
            String providerName,
            List<String> seedModels,
            CopilotModelMetadataStore copilotModelMetadataStore
    ) {
        return "Perplexity".equals(providerName)
                ? new PerplexityModelCatalogClient()
                : new OpenAiModelCatalogClient(copilotModelMetadataStore);
    }

    private static ProviderCapabilities declaredCapabilities(String providerName) {
        return switch (providerName) {
            case "OpenAI", "OpenRouter" -> ProviderCapabilities.chatModelsAndImages();
            default -> ProviderCapabilities.chatAndModels();
        };
    }
}
