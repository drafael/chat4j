package com.github.drafael.chat4j.provider.modules;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.chat.impl.AnthropicChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.capability.models.impl.AnthropicModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderModule;
import com.github.drafael.chat4j.provider.support.BaseUrlNormalizer;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;

import static java.util.Collections.emptyList;

public class AnthropicModule implements ProviderModule {

    private final ProviderDescriptor descriptor;
    private final ChatCompletionClient chatCompletionClient;
    private final ModelCatalogClient modelCatalogClient = new AnthropicModelCatalogClient();

    public AnthropicModule(
            String providerName,
            String credentialEnvVar,
            String defaultBaseUrl,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        descriptor = new ProviderDescriptor(
            providerName,
            AuthType.ENV_VAR,
            credentialEnvVar,
            null,
            defaultBaseUrl,
            emptyList(),
            ProviderCapabilities.chatModelsImagesAndFiles(),
            BaseUrlNormalizer::normalizeAnthropicBaseUrl
        );
        chatCompletionClient = new AnthropicChatCompletionClient(attachmentSupport);
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ChatCompletionClient chatCompletionClient() {
        return chatCompletionClient;
    }

    @Override
    public ModelCatalogClient modelCatalogClient() {
        return modelCatalogClient;
    }
}
