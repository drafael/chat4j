package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.apache.commons.lang3.Validate;

import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.List;
import java.util.Map;

public class ProviderMenuAvailabilityRefreshCoordinator {

    private final AvailabilityResolver availabilityResolver;
    private final ProviderMenuAvailabilityApplier providerMenuAvailabilityApplier;

    public ProviderMenuAvailabilityRefreshCoordinator(
            AvailabilityResolver availabilityResolver,
            ProviderMenuAvailabilityApplier providerMenuAvailabilityApplier
    ) {
        this.availabilityResolver = Validate.notNull(availabilityResolver, "availabilityResolver must not be null");
        this.providerMenuAvailabilityApplier = Validate.notNull(
                providerMenuAvailabilityApplier,
                "providerMenuAvailabilityApplier must not be null"
        );
    }

    public void refresh(
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            Map<String, JMenuItem> providerHeaderItemsByName,
            List<ProviderRegistry.ProviderDef> providers,
            ProviderMenuAvailabilityApplier.IconResolver iconResolver
    ) {
        Validate.notNull(modelMenuItemsByKey, "modelMenuItemsByKey must not be null");
        Validate.notNull(providerHeaderItemsByName, "providerHeaderItemsByName must not be null");
        Validate.notNull(providers, "providers must not be null");
        Validate.notNull(iconResolver, "iconResolver must not be null");

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
