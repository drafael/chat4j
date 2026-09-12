package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderMenuStructureRebuilderTest {

    @Test
    @DisplayName("Rebuild clears menu state and adds no-providers placeholder when snapshot is empty")
    void rebuild_whenProvidersEmpty_clearsStateAndAddsNoProvidersItem() throws Exception {
        var favoritesAppender = new StubProviderFavoritesSectionAppender();
        var catalogAppender = new StubProviderCatalogSectionAppender();
        var subject = new ProviderMenuStructureRebuilder(
                favoritesAppender,
                catalogAppender,
                new ProviderMenuEmptyStateFactory()
        );
        var menuData = new ProviderMenuDataResolver.ProviderMenuData(
                emptyList(),
                emptyMap(),
                emptyMap(),
                emptyList()
        );

        runOnEdt(() -> {
            JMenu modelsMenu = new JMenu("Model");
            modelsMenu.add(new JMenuItem("stale"));
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey = new LinkedHashMap<>();
            modelMenuItemsByKey.put("stale", new JRadioButtonMenuItem("stale"));
            Map<String, JMenuItem> providerHeaderItemsByName = new LinkedHashMap<>();
            providerHeaderItemsByName.put("stale", new JMenuItem("stale"));

            subject.rebuild(
                    modelsMenu,
                    modelMenuItemsByKey,
                    providerHeaderItemsByName,
                    menuData,
                    modelKey -> {
                    }
            );

            assertThat(modelsMenu.getItemCount()).isEqualTo(1);
            assertThat(modelsMenu.getItem(0).getText()).isEqualTo("No providers available");
            assertThat(modelsMenu.getItem(0).isEnabled()).isFalse();
            assertThat(modelMenuItemsByKey).isEmpty();
            assertThat(providerHeaderItemsByName).isEmpty();
        });
        assertThat(favoritesAppender.calls.get()).isZero();
        assertThat(catalogAppender.calls.get()).isZero();
    }

    @Test
    @DisplayName("Rebuild renders a resolved snapshot through favorites and catalog appenders")
    void rebuild_whenProvidersPresent_delegatesSnapshotToAppenders() throws Exception {
        var providers = List.of(provider("OpenAI"));
        var modelsByProvider = Map.of("OpenAI", List.of("gpt-4.1"));
        var providerSelectable = Map.of("OpenAI", true);
        var favorites = List.of(new ModelSelectionCodec.ModelSelection("OpenAI", "gpt-4.1"));
        var menuData = new ProviderMenuDataResolver.ProviderMenuData(
                providers,
                modelsByProvider,
                providerSelectable,
                favorites
        );
        var favoritesAppender = new StubProviderFavoritesSectionAppender();
        var catalogAppender = new StubProviderCatalogSectionAppender();
        var subject = new ProviderMenuStructureRebuilder(
                favoritesAppender,
                catalogAppender,
                new ProviderMenuEmptyStateFactory()
        );

        runOnEdt(() -> subject.rebuild(
                new JMenu("Model"),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                menuData,
                modelKey -> {
                }
        ));

        assertThat(favoritesAppender.calls.get()).isEqualTo(1);
        assertThat(favoritesAppender.lastFavorites).isEqualTo(favorites);
        assertThat(favoritesAppender.lastProviderSelectable).isEqualTo(providerSelectable);
        assertThat(catalogAppender.calls.get()).isEqualTo(1);
        assertThat(catalogAppender.lastProviders).isEqualTo(providers);
        assertThat(catalogAppender.lastModelsByProvider).isEqualTo(modelsByProvider);
        assertThat(catalogAppender.lastProviderSelectable).isEqualTo(providerSelectable);
    }

    private ProviderRegistry.ProviderDef provider(String name) {
        return new ProviderRegistry.ProviderDef(
                name,
                "API_KEY",
                "https://example.invalid",
                "https://example.invalid",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> null,
                () -> emptyList()
        );
    }

    private static class StubProviderFavoritesSectionAppender extends ProviderFavoritesSectionAppender {

        private final AtomicInteger calls = new AtomicInteger();
        private List<ModelSelectionCodec.ModelSelection> lastFavorites;
        private Map<String, Boolean> lastProviderSelectable;

        private StubProviderFavoritesSectionAppender() {
            super(new ProviderModelMenuItemFactory(
                    new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), StubProviderFavoritesSectionAppender.class)
            ));
        }

        @Override
        public boolean append(
                JMenu modelsMenu,
                ButtonGroup group,
                Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
                List<ModelSelectionCodec.ModelSelection> favorites,
                Map<String, Boolean> providerSelectable,
                Consumer<String> onModelSelected
        ) {
            calls.incrementAndGet();
            lastFavorites = favorites;
            lastProviderSelectable = providerSelectable;
            return !favorites.isEmpty();
        }
    }

    private static class StubProviderCatalogSectionAppender extends ProviderCatalogSectionAppender {

        private final AtomicInteger calls = new AtomicInteger();
        private List<ProviderRegistry.ProviderDef> lastProviders;
        private Map<String, List<String>> lastModelsByProvider;
        private Map<String, Boolean> lastProviderSelectable;

        private StubProviderCatalogSectionAppender() {
            super(
                    new ProviderAvailabilityLabelFormatter(),
                    new ProviderHeaderMenuItemFactory((providerName, item, enabled) -> null),
                    new ProviderFavoritesResolver(ModelFavoritesService.createInMemory()),
                    new ProviderMenuEmptyStateFactory(),
                    new ProviderModelMenuItemFactory(
                            new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), StubProviderCatalogSectionAppender.class)
                    )
            );
        }

        @Override
        public boolean append(
                JMenu modelsMenu,
                ButtonGroup group,
                Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
                Map<String, JMenuItem> providerHeaderItemsByName,
                List<ProviderRegistry.ProviderDef> providers,
                Map<String, List<String>> modelsByProvider,
                Map<String, Boolean> providerSelectable,
                Consumer<String> onModelSelected
        ) {
            calls.incrementAndGet();
            lastProviders = providers;
            lastModelsByProvider = modelsByProvider;
            lastProviderSelectable = providerSelectable;
            return true;
        }
    }

    private void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
