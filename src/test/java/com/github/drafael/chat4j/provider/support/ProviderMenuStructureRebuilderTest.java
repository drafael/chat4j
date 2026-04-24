package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.ModelCache;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.storage.StoragePaths;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderMenuStructureRebuilderTest {

    @Test
    @DisplayName("Rebuild clears menu state and adds no-providers placeholder when providers list is empty")
    void rebuild_whenProvidersEmpty_clearsStateAndAddsNoProvidersItem() {
        var menuDataResolver = new ThrowingProviderMenuDataResolver();
        var favoritesAppender = new StubProviderFavoritesSectionAppender();
        var catalogAppender = new StubProviderCatalogSectionAppender();
        var subject = new ProviderMenuStructureRebuilder(
                menuDataResolver,
                favoritesAppender,
                catalogAppender,
                new ProviderMenuEmptyStateFactory()
        );

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
                emptyList(),
                modelKey -> {
                }
        );

        assertThat(modelsMenu.getItemCount()).isEqualTo(1);
        assertThat(modelsMenu.getItem(0).getText()).isEqualTo("No providers available");
        assertThat(modelsMenu.getItem(0).isEnabled()).isFalse();
        assertThat(modelMenuItemsByKey).isEmpty();
        assertThat(providerHeaderItemsByName).isEmpty();
        assertThat(menuDataResolver.calls.get()).isZero();
        assertThat(favoritesAppender.calls.get()).isZero();
        assertThat(catalogAppender.calls.get()).isZero();
    }

    @Test
    @DisplayName("Rebuild resolves menu data and delegates favorites/catalog appenders when providers exist")
    void rebuild_whenProvidersPresent_resolvesDataAndDelegatesToAppenders() throws Exception {
        var providers = List.of(provider("OpenAI"));
        var modelsByProvider = Map.of("OpenAI", List.of("gpt-4.1"));
        var providerSelectable = Map.of("OpenAI", true);
        var favorites = List.of(new ModelSelectionCodec.ModelSelection("OpenAI", "gpt-4.1"));

        var menuDataResolver = new StubProviderMenuDataResolver(
                new ProviderMenuDataResolver.ProviderMenuData(modelsByProvider, providerSelectable, favorites)
        );
        var favoritesAppender = new StubProviderFavoritesSectionAppender();
        var catalogAppender = new StubProviderCatalogSectionAppender();
        var subject = new ProviderMenuStructureRebuilder(
                menuDataResolver,
                favoritesAppender,
                catalogAppender,
                new ProviderMenuEmptyStateFactory()
        );

        JMenu modelsMenu = new JMenu("Model");
        Map<String, JRadioButtonMenuItem> modelMenuItemsByKey = new LinkedHashMap<>();
        Map<String, JMenuItem> providerHeaderItemsByName = new LinkedHashMap<>();

        subject.rebuild(
                modelsMenu,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providers,
                modelKey -> {
                }
        );

        assertThat(menuDataResolver.calls.get()).isEqualTo(1);
        assertThat(menuDataResolver.lastProviders).isEqualTo(providers);

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
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> null,
                () -> emptyList()
        );
    }

    private SettingsRepo settingsRepo(String dbName) throws SQLException {
        DataSource dataSource = createDataSource(dbName);
        createSettingsTable(dataSource);
        return new SettingsRepo(dataSource);
    }

    private DataSource createDataSource(String dbName) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(dbName));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void createSettingsTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS settings (\"key\" VARCHAR(100) PRIMARY KEY, \"value\" VARCHAR(500))"
             )) {
            statement.execute();
        }
    }

    private class ThrowingProviderMenuDataResolver extends ProviderMenuDataResolver {

        private final AtomicInteger calls = new AtomicInteger();

        private ThrowingProviderMenuDataResolver() {
            super(
                    new ProviderModelsResolver(new ProviderModelCacheService(new ModelCache(StoragePaths.defaultPaths()))),
                    new ProviderSelectableResolver(),
                    new ProviderFavoritesResolver(ModelFavoritesService.createInMemory()),
                    new ProviderAvailabilityResolver(settingsRepoQuietly())
            );
        }

        @Override
        public ProviderMenuData resolve(List<ProviderRegistry.ProviderDef> providers) {
            calls.incrementAndGet();
            throw new AssertionError("resolve should not be called when providers are empty");
        }
    }

    private class StubProviderMenuDataResolver extends ProviderMenuDataResolver {

        private final AtomicInteger calls = new AtomicInteger();
        private final ProviderMenuData result;
        private List<ProviderRegistry.ProviderDef> lastProviders;

        private StubProviderMenuDataResolver(ProviderMenuData result) {
            super(
                    new ProviderModelsResolver(new ProviderModelCacheService(new ModelCache(StoragePaths.defaultPaths()))),
                    new ProviderSelectableResolver(),
                    new ProviderFavoritesResolver(ModelFavoritesService.createInMemory()),
                    new ProviderAvailabilityResolver(settingsRepoQuietly())
            );
            this.result = result;
        }

        @Override
        public ProviderMenuData resolve(List<ProviderRegistry.ProviderDef> providers) {
            calls.incrementAndGet();
            lastProviders = providers;
            return result;
        }
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
                java.util.function.Consumer<String> onModelSelected
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
                java.util.function.Consumer<String> onModelSelected
        ) {
            calls.incrementAndGet();
            lastProviders = providers;
            lastModelsByProvider = modelsByProvider;
            lastProviderSelectable = providerSelectable;
            return true;
        }
    }

    private SettingsRepo settingsRepoQuietly() {
        try {
            return settingsRepo("provider-menu-structure-%s".formatted(System.nanoTime()));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
