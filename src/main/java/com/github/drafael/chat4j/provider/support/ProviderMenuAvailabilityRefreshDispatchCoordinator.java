package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.apache.commons.lang3.Validate;

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
            RefreshAction refreshAction,
            ProvidersSupplier providersSupplier
    ) {
        this.refreshAction = Validate.notNull(refreshAction, "refreshAction must not be null");
        this.providersSupplier = Validate.notNull(providersSupplier, "providersSupplier must not be null");
    }

    public void refresh(
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            Map<String, JMenuItem> providerHeaderItemsByName,
            ProviderMenuIconResolver providerMenuIconResolver
    ) {
        Validate.notNull(modelMenuItemsByKey, "modelMenuItemsByKey must not be null");
        Validate.notNull(providerHeaderItemsByName, "providerHeaderItemsByName must not be null");
        Validate.notNull(providerMenuIconResolver, "providerMenuIconResolver must not be null");

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
