package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import org.apache.commons.lang3.Validate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ProviderModelsResolver {

    private final ProviderModelCacheService modelCacheService;

    public ProviderModelsResolver(ProviderModelCacheService modelCacheService) {
        this.modelCacheService = Validate.notNull(modelCacheService, "modelCacheService must not be null");
    }

    public Map<String, List<String>> resolve(List<ProviderRegistry.ProviderDef> providers) {
        Validate.notNull(providers, "providers must not be null");

        return providers.stream()
                .collect(Collectors.toMap(
                        ProviderRegistry.ProviderDef::name,
                        provider -> {
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
        return ModelOrdering.sanitizeAndSortByProvider(providerName, modelIds);
    }
}
