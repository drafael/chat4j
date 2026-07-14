package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import org.apache.commons.lang3.Strings;

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

                            if (modelCacheService.isInvalidated(provider.name())) {
                                return modelCacheService.modelsWithLocalOverlay(provider.name(), provider.seedModels());
                            }

                            List<String> models = modelCacheService.getModels(provider.name());
                            return models.isEmpty()
                                    ? modelCacheService.modelsWithLocalOverlay(provider.name(), provider.seedModels())
                                    : models;
                        },
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
    }
}
