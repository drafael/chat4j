package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.ModelFetcher;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;

public interface ProviderModule {

    ProviderDescriptor descriptor();

    ChatCompletionClient chatCompletionClient();

    ModelCatalogClient modelCatalogClient();

    default ProviderService createService(ProviderRuntime runtime) {
        return new CapabilityProviderService(
            runtime,
            chatCompletionClient(),
            modelCatalogClient());
    }

    default ModelFetcher createModelFetcher(ProviderRuntime runtime, long metadataGeneration) {
        return () -> modelCatalogClient().fetchModels(runtime, metadataGeneration);
    }
}
