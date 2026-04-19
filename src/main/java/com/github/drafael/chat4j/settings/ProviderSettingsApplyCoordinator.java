package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.apache.commons.lang3.Validate;

import java.util.List;
import java.util.Map;

public class ProviderSettingsApplyCoordinator {

    private final RuntimeSettingsResolver runtimeSettingsResolver;
    private final RuntimeConfigApplier runtimeConfigApplier;

    public ProviderSettingsApplyCoordinator(ProviderRuntimeSettingsResolver providerRuntimeSettingsResolver) {
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

    public void apply(List<ProviderRegistry.ProviderDef> providers, Runnable refreshProviders, Runnable markModelsMenuDirty) {
        Validate.notNull(providers, "providers must not be null");
        Validate.notNull(refreshProviders, "refreshProviders must not be null");
        Validate.notNull(markModelsMenuDirty, "markModelsMenuDirty must not be null");

        Map<String, ProviderRegistry.ProviderRuntimeConfig> runtimeConfigByProvider =
                runtimeSettingsResolver.resolve(providers);
        runtimeConfigApplier.apply(runtimeConfigByProvider);
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
