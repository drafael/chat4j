package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.ModelFetcher;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDiagnosticSanitizer;
import com.github.drafael.chat4j.provider.api.ProviderFactory;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
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
            String defaultBaseUrl,
            List<String> seedModels,
            ProviderCapabilities capabilities,
            ProviderFactory factory,
            ModelFetcher fetcher
    ) {
        @Override
        public String toString() {
            return "ProviderDef[name=%s, envVar=%s, baseUrl=%s, defaultBaseUrl=%s, seedModels=%s, capabilities=%s]".formatted(
                    name,
                    envVar,
                    ProviderDiagnosticSanitizer.safeOrigin(baseUrl),
                    ProviderDiagnosticSanitizer.safeOrigin(defaultBaseUrl),
                    seedModels,
                    capabilities
            );
        }
    }

    public record ProviderRuntimeConfig(boolean enabled, String baseUrl) {
        @Override
        public String toString() {
            return "ProviderRuntimeConfig[enabled=%s, baseUrl=%s]".formatted(
                    enabled,
                    ProviderDiagnosticSanitizer.safeOrigin(baseUrl)
            );
        }
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
            @NonNull CopilotModelMetadataStore copilotModelMetadataStore,
            @NonNull CredentialResolver credentialResolver,
            @NonNull Map<String, String> subprocessEnvironment,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        this(
                new ProviderCatalog(
                        copilotAuthResolver,
                        codexAuthResolver,
                        copilotModelMetadataStore,
                        credentialResolver,
                        subprocessEnvironment,
                        attachmentSupport
                ),
                new ProviderRuntimePolicy(copilotAuthResolver, codexAuthResolver, credentialResolver)
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

    public boolean matchesRuntimeConfig(ProviderDef installed, ProviderRuntimeConfig proposed) {
        if (installed == null) {
            return false;
        }
        ProviderDefinition definition = catalog.allProviders().stream()
                .filter(candidate -> candidate.name().equals(installed.name()))
                .findFirst()
                .orElse(null);
        if (definition == null) {
            return false;
        }
        boolean enabled = proposed == null || proposed.enabled();
        String configuredBaseUrl = proposed == null || proposed.baseUrl() == null || proposed.baseUrl().isBlank()
                ? definition.baseUrl()
                : proposed.baseUrl().trim();
        String effectiveBaseUrl = definition.descriptor().normalizeBaseUrl(configuredBaseUrl);
        return enabled && installed.baseUrl().equals(effectiveBaseUrl);
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
                normalizedDefaultBaseUrl(providerDefinition),
                providerDefinition.seedModels(),
                providerDefinition.descriptor().capabilities(),
                catalog.createFactory(providerDefinition.name(), providerDefinition.envVar(), effectiveBaseUrl),
                catalog.createFetcher(providerDefinition.name(), providerDefinition.envVar(), effectiveBaseUrl)
        );
    }

    private String normalizedDefaultBaseUrl(ProviderDefinition providerDefinition) {
        return providerDefinition.descriptor().normalizeBaseUrl(providerDefinition.descriptor().defaultBaseUrl());
    }

    private ProviderDef toProviderDef(ProviderDefinition providerDefinition) {
        return new ProviderDef(
                providerDefinition.name(),
                providerDefinition.envVar(),
                providerDefinition.baseUrl(),
                normalizedDefaultBaseUrl(providerDefinition),
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
