package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.Map;

@Slf4j
public class ProviderSettingsApplyCoordinator {

    private final RuntimeSettingsResolver runtimeSettingsResolver;
    private final RuntimeConfigApplier runtimeConfigApplier;

    public ProviderSettingsApplyCoordinator(@NonNull ProviderRuntimeSettingsResolver providerRuntimeSettingsResolver) {
        this(
                providerRuntimeSettingsResolver::resolveAll,
                ProviderRegistry::applyRuntimeConfig
        );
    }

    ProviderSettingsApplyCoordinator(
            RuntimeSettingsResolver runtimeSettingsResolver,
            RuntimeConfigApplier runtimeConfigApplier
    ) {
        this.runtimeSettingsResolver = Validate.notNull(runtimeSettingsResolver, "runtimeSettingsResolver must not be null");
        this.runtimeConfigApplier = Validate.notNull(runtimeConfigApplier, "runtimeConfigApplier must not be null");
    }

    public void apply(
            @NonNull List<ProviderRegistry.ProviderDef> providers,
            @NonNull Runnable refreshProviders,
            @NonNull Runnable markModelsMenuDirty
    ) {
        Map<String, ProviderRegistry.ProviderRuntimeConfig> runtimeConfigByProvider =
                runtimeSettingsResolver.resolve(providers);
        runtimeConfigApplier.apply(runtimeConfigByProvider);
        long enabledCount = runtimeConfigByProvider.values().stream()
                .filter(ProviderRegistry.ProviderRuntimeConfig::enabled)
                .count();
        log.info("Applied provider runtime config: providers={} enabled={} disabled={}",
                runtimeConfigByProvider.size(),
                enabledCount,
                runtimeConfigByProvider.size() - enabledCount);
        refreshProviders.run();
        markModelsMenuDirty.run();
    }

    @FunctionalInterface
    interface RuntimeSettingsResolver {
        Map<String, ProviderRegistry.ProviderRuntimeConfig> resolve(List<ProviderRegistry.ProviderDef> providers);
    }

    @FunctionalInterface
    interface RuntimeConfigApplier {
        void apply(Map<String, ProviderRegistry.ProviderRuntimeConfig> runtimeConfigByProvider);
    }
}
