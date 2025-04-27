package com.github.drafael.chat4j.provider.api;

@FunctionalInterface
public interface ProviderFactory {
    ProviderService create(String model);
}
