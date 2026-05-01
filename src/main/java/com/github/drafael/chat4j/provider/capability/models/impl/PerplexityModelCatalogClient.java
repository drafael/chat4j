package com.github.drafael.chat4j.provider.capability.models.impl;

import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.PerplexityModelIds;

import java.util.List;

public class PerplexityModelCatalogClient implements ModelCatalogClient {

    @Override
    public List<String> fetchModels(ProviderRuntime runtime) {
        return PerplexityModelIds.SONAR_MODELS;
    }
}
