package com.github.drafael.chat4j.provider.capability.models;

import com.github.drafael.chat4j.provider.core.ProviderRuntime;

import java.util.List;

@FunctionalInterface
public interface ModelCatalogClient {

    List<String> fetchModels(ProviderRuntime runtime) throws Exception;

    default List<String> fetchModels(ProviderRuntime runtime, long metadataGeneration) throws Exception {
        return fetchModels(runtime);
    }
}
