package com.github.drafael.chat4j.provider.capability.models.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.models.ModelInfo;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.ModelOrdering;

import java.util.List;

public class AnthropicModelCatalogClient implements ModelCatalogClient {

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(runtime.apiKey())
                .baseUrl(runtime.baseUrl())
                .build();

        return client.models().list().data().stream()
                .sorted((left, right) -> {
                    int byRecency = ModelOrdering.compareByRecency(left.id(), right.id());
                    return byRecency != 0
                            ? byRecency
                            : right.createdAt().compareTo(left.createdAt());
                })
                .map(ModelInfo::id)
                .toList();
    }
}
