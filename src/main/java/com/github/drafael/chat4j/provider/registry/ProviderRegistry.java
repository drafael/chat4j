package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.ModelFetcher;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ProviderRegistry {

    public record ProviderDef(
        String name,
        String envVar,
        String baseUrl,
        List<String> seedModels,
        ProviderCapabilities capabilities,
        ProviderFactory factory,
        ModelFetcher fetcher
    ) {
    }

    public record ProviderRuntimeConfig(
        boolean enabled,
        String baseUrl
    ) {
    }

    private static final ProviderCatalog CATALOG = new ProviderCatalog();
    private static final ProviderRuntimePolicy RUNTIME_POLICY = new ProviderRuntimePolicy();

    public static void applyRuntimeConfig(Map<String, ProviderRuntimeConfig> runtimeConfig) {
        Map<String, ProviderRuntimePolicy.RuntimeConfig> mapped = runtimeConfig == null
                ? Map.of()
                : runtimeConfig.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new ProviderRuntimePolicy.RuntimeConfig(
                                entry.getValue().enabled(),
                                entry.getValue().baseUrl()
                        )
                ));

        RUNTIME_POLICY.applyRuntimeConfig(mapped);
    }

    public static List<ProviderDef> allProviders() {
        return CATALOG.allProviders().stream()
                .map(ProviderRegistry::toProviderDef)
                .toList();
    }

    public static List<ProviderDef> availableProviders() {
        List<ProviderDefinition> all = CATALOG.allProviders();
        RUNTIME_POLICY.warmOAuthStatusCache(all);
        return all.stream()
                .filter(RUNTIME_POLICY::isEnabled)
                .filter(RUNTIME_POLICY::hasRequiredCredentials)
                .map(ProviderRegistry::toEffectiveProvider)
                .toList();
    }

    private static ProviderDef toEffectiveProvider(ProviderDefinition providerDefinition) {
        String effectiveBaseUrl = RUNTIME_POLICY.effectiveBaseUrl(providerDefinition);
        return new ProviderDef(
            providerDefinition.name(),
            providerDefinition.envVar(),
            effectiveBaseUrl,
            providerDefinition.seedModels(),
            providerDefinition.descriptor().capabilities(),
            CATALOG.createFactory(providerDefinition.name(), providerDefinition.envVar(), effectiveBaseUrl),
            CATALOG.createFetcher(
                providerDefinition.name(),
                providerDefinition.envVar(),
                effectiveBaseUrl,
                providerDefinition.seedModels()
            ));
    }

    private static ProviderDef toProviderDef(ProviderDefinition providerDefinition) {
        return new ProviderDef(
            providerDefinition.name(),
            providerDefinition.envVar(),
            providerDefinition.baseUrl(),
            providerDefinition.seedModels(),
            providerDefinition.descriptor().capabilities(),
            CATALOG.createFactory(
                providerDefinition.name(),
                providerDefinition.envVar(),
                providerDefinition.baseUrl()
            ),
            CATALOG.createFetcher(
                providerDefinition.name(),
                providerDefinition.envVar(),
                providerDefinition.baseUrl(),
                providerDefinition.seedModels()
            ));
    }
}
