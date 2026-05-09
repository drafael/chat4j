package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static java.util.stream.Collectors.toMap;

public class ProviderModelsResolver {

    private final ProviderModelCacheService modelCacheService;

    public ProviderModelsResolver(@NonNull ProviderModelCacheService modelCacheService) {
        this.modelCacheService = modelCacheService;
    }

    public Map<String, List<String>> resolve(@NonNull List<ProviderRegistry.ProviderDef> providers) {

        return providers.stream()
                .collect(toMap(
                        ProviderRegistry.ProviderDef::name,
                        provider -> {
                            if (Strings.CS.equals(provider.name(), "Perplexity")) {
                                return provider.seedModels();
                            }

                            List<String> models = sanitize(provider.name(), modelCacheService.getModels(provider.name()));
                            return models.isEmpty()
                                    ? sanitize(provider.name(), provider.seedModels())
                                    : models;
                        },
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
    }

    private static List<String> sanitize(String providerName, List<String> modelIds) {
        return CodexLocalModelCache.mergeIfCodexProvider(providerName, modelIds);
    }
}
