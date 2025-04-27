package com.github.drafael.chat4j.provider.api;

import java.util.List;

@FunctionalInterface
public interface ModelFetcher {
    List<String> fetchModels() throws Exception;
}
