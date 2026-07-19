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

                            return modelCacheService.findUsableModels(provider.name(), provider.baseUrl())
                                    .filter(models -> !models.isEmpty())
                                    .orElseGet(() -> modelCacheService.modelsWithLocalOverlay(
                                            provider.name(),
                                            provider.seedModels()
                                    ));
                        },
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
    }
}
