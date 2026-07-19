package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.ModelFetcher;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderFactory;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toMap;

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

    public record ProviderRuntimeConfig(boolean enabled, String baseUrl) {
    }

    public record ProviderStatus(
            String name,
            boolean enabled,
            boolean credentialReady,
            boolean available
    ) {
    }

    private final ProviderCatalog catalog;
    private final ProviderRuntimePolicy runtimePolicy;

    public ProviderRegistry(
            @NonNull CopilotAuthResolver copilotAuthResolver,
            @NonNull CodexAuthResolver codexAuthResolver,
            @NonNull CopilotModelMetadataStore copilotModelMetadataStore
    ) {
        this(
                new ProviderCatalog(copilotAuthResolver, codexAuthResolver, copilotModelMetadataStore),
                new ProviderRuntimePolicy(copilotAuthResolver, codexAuthResolver)
        );
    }

    ProviderRegistry(@NonNull ProviderCatalog catalog, @NonNull ProviderRuntimePolicy runtimePolicy) {
        this.catalog = catalog;
        this.runtimePolicy = runtimePolicy;
    }

    public void applyRuntimeConfig(Map<String, ProviderRuntimeConfig> runtimeConfig) {
        Map<String, ProviderRuntimePolicy.RuntimeConfig> mapped = runtimeConfig == null
                ? emptyMap()
                : runtimeConfig.entrySet().stream()
                        .collect(toMap(
                                Map.Entry::getKey,
                                entry -> new ProviderRuntimePolicy.RuntimeConfig(
                                        entry.getValue().enabled(),
                                        entry.getValue().baseUrl()
                                )
                        ));

        runtimePolicy.applyRuntimeConfig(mapped);
    }

    public List<ProviderDef> allProviders() {
        return catalog.allProviders().stream()
                .map(this::toProviderDef)
                .toList();
    }

    public List<ProviderDef> availableProviders() {
        List<ProviderDefinition> all = catalog.allProviders();
        runtimePolicy.warmOAuthStatusCache(all);
        return all.stream()
                .filter(runtimePolicy::isEnabled)
                .filter(runtimePolicy::hasRequiredCredentials)
                .map(this::toEffectiveProvider)
                .toList();
    }

    public void invalidateAuthStatus(String providerName) {
        runtimePolicy.invalidateAuthStatus(providerName);
    }

    public void setAuthStatusRefreshListener(@NonNull Runnable listener) {
        runtimePolicy.setAuthStatusRefreshListener(listener);
    }

    public List<ProviderStatus> providerStatuses() {
        List<ProviderDefinition> all = catalog.allProviders();
        runtimePolicy.warmOAuthStatusCache(all);

        return all.stream()
                .map(providerDefinition -> {
                    boolean enabled = runtimePolicy.isEnabled(providerDefinition);
                    boolean credentialReady = runtimePolicy.hasRequiredCredentials(providerDefinition);
                    return new ProviderStatus(
                            providerDefinition.name(),
                            enabled,
                            credentialReady,
                            enabled && credentialReady
                    );
                })
                .toList();
    }

    private ProviderDef toEffectiveProvider(ProviderDefinition providerDefinition) {
        String effectiveBaseUrl = runtimePolicy.effectiveBaseUrl(providerDefinition);
        return new ProviderDef(
                providerDefinition.name(),
                providerDefinition.envVar(),
                effectiveBaseUrl,
                providerDefinition.seedModels(),
                providerDefinition.descriptor().capabilities(),
                catalog.createFactory(providerDefinition.name(), providerDefinition.envVar(), effectiveBaseUrl),
                catalog.createFetcher(providerDefinition.name(), providerDefinition.envVar(), effectiveBaseUrl)
        );
    }

    private ProviderDef toProviderDef(ProviderDefinition providerDefinition) {
        return new ProviderDef(
                providerDefinition.name(),
                providerDefinition.envVar(),
                providerDefinition.baseUrl(),
                providerDefinition.seedModels(),
                providerDefinition.descriptor().capabilities(),
                catalog.createFactory(
                        providerDefinition.name(),
                        providerDefinition.envVar(),
                        providerDefinition.baseUrl()
                ),
                catalog.createFetcher(
                        providerDefinition.name(),
                        providerDefinition.envVar(),
                        providerDefinition.baseUrl()
                )
        );
    }
}
