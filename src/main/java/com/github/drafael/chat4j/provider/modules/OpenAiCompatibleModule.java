package com.github.drafael.chat4j.provider.modules;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.CodexCliChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.DeepSeekChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.GoogleAiGenerateContentClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.MistralChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.OpenAiChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.PerplexityChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.capability.models.impl.OpenAiModelCatalogClient;
import com.github.drafael.chat4j.provider.capability.models.impl.PerplexityModelCatalogClient;
import com.github.drafael.chat4j.provider.capability.models.impl.TogetherModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderModule;
import com.github.drafael.chat4j.provider.support.BaseUrlNormalizer;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.GeneratedImageAttachmentWriter;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

public class OpenAiCompatibleModule implements ProviderModule {

    private final ProviderDescriptor descriptor;
    private final ChatCompletionClient chatCompletionClient;
    private final ModelCatalogClient modelCatalogClient;

    public OpenAiCompatibleModule(
            String providerName,
            @NonNull AuthType authType,
            String credentialEnvVar,
            String fallbackApiKey,
            String defaultBaseUrl,
            @NonNull CopilotModelMetadataStore copilotModelMetadataStore,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        this(
                providerName,
                authType,
                credentialEnvVar,
                fallbackApiKey,
                defaultBaseUrl,
                copilotModelMetadataStore,
                emptyList(),
                declaredCapabilities(providerName),
                emptyMap(),
                attachmentSupport
        );
    }

    public OpenAiCompatibleModule(
            String providerName,
            @NonNull AuthType authType,
            String credentialEnvVar,
            String fallbackApiKey,
            String defaultBaseUrl,
            @NonNull CopilotModelMetadataStore copilotModelMetadataStore,
            @NonNull List<String> seedModels,
            @NonNull ProviderCapabilities capabilities,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        this(
                providerName,
                authType,
                credentialEnvVar,
                fallbackApiKey,
                defaultBaseUrl,
                copilotModelMetadataStore,
                seedModels,
                capabilities,
                emptyMap(),
                attachmentSupport
        );
    }

    public OpenAiCompatibleModule(
            String providerName,
            @NonNull AuthType authType,
            String credentialEnvVar,
            String fallbackApiKey,
            String defaultBaseUrl,
            @NonNull CopilotModelMetadataStore copilotModelMetadataStore,
            @NonNull List<String> seedModels,
            @NonNull ProviderCapabilities capabilities,
            @NonNull Map<String, String> subprocessEnvironment,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        descriptor = new ProviderDescriptor(
                providerName,
                authType,
                credentialEnvVar,
                fallbackApiKey,
                defaultBaseUrl,
                seedModels,
                capabilities,
                configuredBaseUrl -> BaseUrlNormalizer.normalize(configuredBaseUrl, defaultBaseUrl)
        );
        chatCompletionClient = selectChatClient(providerName, subprocessEnvironment, attachmentSupport);
        modelCatalogClient = selectModelCatalogClient(providerName, copilotModelMetadataStore);
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ChatCompletionClient chatCompletionClient() {
        return chatCompletionClient;
    }

    private ChatCompletionClient selectChatClient(
            String providerName,
            Map<String, String> subprocessEnvironment,
            ProviderAttachmentSupport attachmentSupport
    ) {
        return switch (providerName) {
            case "OpenAI Codex" -> new CodexCliChatCompletionClient(subprocessEnvironment, attachmentSupport);
            case "Perplexity" -> new PerplexityChatCompletionClient(attachmentSupport);
            case "Google AI" -> new GoogleAiGenerateContentClient(
                    new OpenAiChatCompletionClient(attachmentSupport),
                    attachmentSupport,
                    new GeneratedImageAttachmentWriter(attachmentSupport)
            );
            case "DeepSeek" -> new DeepSeekChatCompletionClient(attachmentSupport);
            case "Mistral" -> new MistralChatCompletionClient(attachmentSupport);
            default -> new OpenAiChatCompletionClient(attachmentSupport);
        };
    }

    @Override
    public ModelCatalogClient modelCatalogClient() {
        return modelCatalogClient;
    }

    private ModelCatalogClient selectModelCatalogClient(
            String providerName,
            CopilotModelMetadataStore copilotModelMetadataStore
    ) {
        return switch (providerName) {
            case "Perplexity" -> new PerplexityModelCatalogClient();
            case "Together" -> new TogetherModelCatalogClient();
            default -> new OpenAiModelCatalogClient(copilotModelMetadataStore);
        };
    }

    private static ProviderCapabilities declaredCapabilities(String providerName) {
        return switch (providerName) {
            case "OpenAI", "OpenRouter" -> ProviderCapabilities.chatModelsAndImages();
            default -> ProviderCapabilities.chatAndModels();
        };
    }
}
