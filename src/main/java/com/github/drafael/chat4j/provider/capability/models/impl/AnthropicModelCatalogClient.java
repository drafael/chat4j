package com.github.drafael.chat4j.provider.capability.models.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.models.ModelInfo;
import com.anthropic.models.models.ModelListPage;
import com.github.drafael.chat4j.provider.api.ProviderModelInfo;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.ModelOrdering;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

import static java.util.stream.Collectors.toMap;

public class AnthropicModelCatalogClient implements ModelCatalogClient {

    @Override
    public Map<String, ProviderModelInfo> fetchModelInfos(@NonNull ProviderRuntime runtime) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(runtime.apiKey()).baseUrl(runtime.baseUrl())
                .timeout(Duration.ofSeconds(4)).maxRetries(0).build();
        try {
            return fetchCatalog(client).stream()
                    .map(model -> ProviderModelInfo.builder()
                            .modelId(model.id()).displayName(model.displayName())
                            .maxInputTokens(model.maxInputTokens().orElse(null))
                            .maxOutputTokens(model.maxTokens().orElse(null)).build())
                    .filter(ProviderModelInfo::hasDetails)
                    .collect(toMap(ProviderModelInfo::modelId, info -> info, (first, ignored) -> first));
        } finally {
            client.close();
        }
    }

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(runtime.apiKey())
                .baseUrl(runtime.baseUrl())
                .build();

        try {
            return fetchCatalog(client).stream()
                    .sorted((left, right) -> {
                        int byRecency = ModelOrdering.compareByRecency(left.id(), right.id());
                        return byRecency != 0
                                ? byRecency
                                : right.createdAt().compareTo(left.createdAt());
                    })
                    .map(ModelInfo::id)
                    .toList();
        } finally {
            client.close();
        }
    }

    private List<ModelInfo> fetchCatalog(AnthropicClient client) {
        List<ModelInfo> models = new ArrayList<>();
        ModelListPage page = client.models().list();
        while (!Thread.currentThread().isInterrupted()) {
            models.addAll(page.data());
            // SDK 2.57.0's auto-pager ignores has_more when the final page has a last_id.
            if (!page.hasMore().orElseGet(page::hasNextPage)) {
                break;
            }
            page = page.nextPage();
        }
        return models;
    }
}
