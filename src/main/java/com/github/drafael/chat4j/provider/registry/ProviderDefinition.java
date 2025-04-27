package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.core.ProviderModule;

import java.util.List;

record ProviderDefinition(
    ProviderDescriptor descriptor,
    ProviderModule module
) {

    String name() {
        return descriptor.name();
    }

    String envVar() {
        return descriptor.credentialEnvVar();
    }

    String baseUrl() {
        return descriptor.defaultBaseUrl();
    }

    List<String> seedModels() {
        return descriptor.seedModels();
    }
}
