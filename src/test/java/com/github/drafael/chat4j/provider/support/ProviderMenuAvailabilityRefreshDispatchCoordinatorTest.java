package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Collections.emptyList;

class ProviderMenuAvailabilityRefreshDispatchCoordinatorTest {

    @Test
    @DisplayName("Refresh obtains providers and delegates to refresh action")
    void refresh_whenCalled_resolvesProvidersAndDelegates() {
        Map<String, JRadioButtonMenuItem> modelItems = new LinkedHashMap<>();
        Map<String, JMenuItem> headerItems = new LinkedHashMap<>();
        ProviderMenuIconResolver iconResolver = new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), Object.class);

        var capturedProviders = new AtomicReference<List<ProviderRegistry.ProviderDef>>();
        List<ProviderRegistry.ProviderDef> suppliedProviders = emptyList();

        var subject = new ProviderMenuAvailabilityRefreshDispatchCoordinator(
                (modelMenuItemsByKey, providerHeaderItemsByName, providers, resolver) -> {
                    capturedProviders.set(providers);
                    assertThat(modelMenuItemsByKey).isSameAs(modelItems);
                    assertThat(providerHeaderItemsByName).isSameAs(headerItems);
                    assertThat(resolver).isSameAs(iconResolver);
                },
                () -> suppliedProviders
        );

        subject.refresh(modelItems, headerItems, iconResolver);

        assertThat(capturedProviders.get()).isSameAs(suppliedProviders);
    }

    @Test
    @DisplayName("Refresh validates required arguments and constructor dependencies")
    void refresh_whenInvalidInput_throwsException() {
        var subject = new ProviderMenuAvailabilityRefreshDispatchCoordinator(
                (modelMenuItemsByKey, providerHeaderItemsByName, providers, providerMenuIconResolver) -> {
                },
                List::of
        );

        assertThatThrownBy(() -> subject.refresh(null, new LinkedHashMap<>(),
                new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), Object.class)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelMenuItemsByKey must not be null");

        assertThatThrownBy(() -> new ProviderMenuAvailabilityRefreshDispatchCoordinator(
                (ProviderMenuAvailabilityRefreshDispatchCoordinator.RefreshAction) null,
                List::of
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refreshAction must not be null");
    }
}
