package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Collections.emptyMap;

class ProviderMenuAvailabilityRefreshCoordinatorTest {

    @Test
    @DisplayName("Refresh skips resolver and applier when model menu items are empty")
    void refresh_whenModelItemsEmpty_skipsResolverAndApplier() {
        AtomicInteger resolverCalls = new AtomicInteger();
        var applier = new RecordingApplier();
        var subject = new ProviderMenuAvailabilityRefreshCoordinator(
                providers -> {
                    resolverCalls.incrementAndGet();
                    return emptyMap();
                },
                applier
        );

        subject.refresh(emptyMap(), emptyMap(), List.of(provider("OpenAI")), new NoOpIconResolver());

        assertThat(resolverCalls.get()).isZero();
        assertThat(applier.applyCalls.get()).isZero();
    }

    @Test
    @DisplayName("Refresh resolves availability and delegates to applier")
    void refresh_whenModelItemsPresent_resolvesAvailabilityAndDelegatesToApplier() {
        AtomicInteger resolverCalls = new AtomicInteger();
        var applier = new RecordingApplier();
        Map<String, Boolean> availability = Map.of("OpenAI", true);
        var subject = new ProviderMenuAvailabilityRefreshCoordinator(
                providers -> {
                    resolverCalls.incrementAndGet();
                    return availability;
                },
                applier
        );

        Map<String, JRadioButtonMenuItem> modelItems = new LinkedHashMap<>();
        modelItems.put("OpenAI > gpt-4.1", new JRadioButtonMenuItem("gpt-4.1"));
        Map<String, JMenuItem> headerItems = new LinkedHashMap<>();
        headerItems.put("OpenAI", new JMenuItem("OpenAI"));
        var iconResolver = new NoOpIconResolver();

        subject.refresh(modelItems, headerItems, List.of(provider("OpenAI")), iconResolver);

        assertThat(resolverCalls.get()).isEqualTo(1);
        assertThat(applier.applyCalls.get()).isEqualTo(1);
        assertThat(applier.lastModelItems).isSameAs(modelItems);
        assertThat(applier.lastHeaderItems).isSameAs(headerItems);
        assertThat(applier.lastAvailability).isSameAs(availability);
        assertThat(applier.lastIconResolver).isSameAs(iconResolver);
    }

    private ProviderRegistry.ProviderDef provider(String name) {
        return new ProviderRegistry.ProviderDef(
                name,
                "API_KEY",
                "https://example.invalid",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> null,
                () -> emptyList()
        );
    }

    private static class RecordingApplier extends ProviderMenuAvailabilityApplier {

        private final AtomicInteger applyCalls = new AtomicInteger();
        private Map<String, JRadioButtonMenuItem> lastModelItems;
        private Map<String, JMenuItem> lastHeaderItems;
        private Map<String, Boolean> lastAvailability;
        private IconResolver lastIconResolver;

        @Override
        public void apply(
                Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
                Map<String, JMenuItem> providerHeaderItemsByName,
                Map<String, Boolean> providerEnabledByName,
                IconResolver iconResolver
        ) {
            applyCalls.incrementAndGet();
            lastModelItems = modelMenuItemsByKey;
            lastHeaderItems = providerHeaderItemsByName;
            lastAvailability = providerEnabledByName;
            lastIconResolver = iconResolver;
        }
    }

    private static class NoOpIconResolver implements ProviderMenuAvailabilityApplier.IconResolver {
        @Override
        public Icon resolveModelIcon(String providerName, JRadioButtonMenuItem item, boolean enabled) {
            return null;
        }

        @Override
        public Icon resolveHeaderIcon(String providerName, JMenuItem item, boolean enabled) {
            return null;
        }
    }
}
