package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import lombok.NonNull;

import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.List;
import java.util.Map;

public class ProviderMenuAvailabilityRefreshCoordinator {

    private final AvailabilityResolver availabilityResolver;
    private final ProviderMenuAvailabilityApplier providerMenuAvailabilityApplier;

    public ProviderMenuAvailabilityRefreshCoordinator(
            @NonNull AvailabilityResolver availabilityResolver,
            @NonNull ProviderMenuAvailabilityApplier providerMenuAvailabilityApplier
    ) {
        this.availabilityResolver = availabilityResolver;
        this.providerMenuAvailabilityApplier = providerMenuAvailabilityApplier;
    }

    public void refresh(
            @NonNull Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            @NonNull Map<String, JMenuItem> providerHeaderItemsByName,
            @NonNull List<ProviderRegistry.ProviderDef> providers,
            @NonNull ProviderMenuAvailabilityApplier.IconResolver iconResolver
    ) {

        if (modelMenuItemsByKey.isEmpty()) {
            return;
        }

        Map<String, Boolean> providerEnabledByName = availabilityResolver.resolve(providers);
        providerMenuAvailabilityApplier.apply(
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providerEnabledByName,
                iconResolver
        );
    }

    @FunctionalInterface
    public interface AvailabilityResolver {
        Map<String, Boolean> resolve(List<ProviderRegistry.ProviderDef> providers);
    }
}
