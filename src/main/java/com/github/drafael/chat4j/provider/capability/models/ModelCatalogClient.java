package com.github.drafael.chat4j.provider.capability.models;

import com.github.drafael.chat4j.provider.api.ProviderModelInfo;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import lombok.NonNull;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;

@FunctionalInterface
public interface ModelCatalogClient {

    List<String> fetchModels(ProviderRuntime runtime) throws Exception;

    default Map<String, ProviderModelInfo> fetchModelInfos(@NonNull ProviderRuntime runtime) throws Exception {
        return emptyMap();
    }

    default List<String> fetchModels(ProviderRuntime runtime, long metadataGeneration) throws Exception {
        return fetchModels(runtime);
    }
}
