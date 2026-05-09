package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import lombok.NonNull;

import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.List;
import java.util.Map;

public class ProviderMenuAvailabilityRefreshDispatchCoordinator {

    private final RefreshAction refreshAction;
    private final ProvidersSupplier providersSupplier;

    public ProviderMenuAvailabilityRefreshDispatchCoordinator(
            ProviderMenuAvailabilityRefreshCoordinator providerMenuAvailabilityRefreshCoordinator
    ) {
        this(providerMenuAvailabilityRefreshCoordinator::refresh, ProviderRegistry::allProviders);
    }

    ProviderMenuAvailabilityRefreshDispatchCoordinator(
            @NonNull RefreshAction refreshAction,
            @NonNull ProvidersSupplier providersSupplier
    ) {
        this.refreshAction = refreshAction;
        this.providersSupplier = providersSupplier;
    }

    public void refresh(
            @NonNull Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            @NonNull Map<String, JMenuItem> providerHeaderItemsByName,
            @NonNull ProviderMenuIconResolver providerMenuIconResolver
    ) {

        refreshAction.refresh(
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providersSupplier.get(),
                providerMenuIconResolver
        );
    }

    @FunctionalInterface
    interface RefreshAction {
        void refresh(
                Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
                Map<String, JMenuItem> providerHeaderItemsByName,
                List<ProviderRegistry.ProviderDef> providers,
                ProviderMenuIconResolver providerMenuIconResolver
        );
    }

    @FunctionalInterface
    interface ProvidersSupplier {
        List<ProviderRegistry.ProviderDef> get();
    }
}
