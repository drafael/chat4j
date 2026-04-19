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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderMenuDataResolverTest {

    @Test
    @DisplayName("Resolve composes models, selectable flags, and favorites from collaborators")
    void resolve_whenCalled_composesMenuDataFromCollaborators() throws Exception {
        List<ProviderRegistry.ProviderDef> providers = List.of(provider("OpenAI"), provider("Ollama"));

        Map<String, List<String>> modelsByProvider = new LinkedHashMap<>();
        modelsByProvider.put("OpenAI", List.of("gpt-4.1"));
        modelsByProvider.put("Ollama", List.of("llama3.2"));

        var modelsResolver = new StubProviderModelsResolver(modelsByProvider);
        var favoritesResolver = new StubProviderFavoritesResolver(List.of(
                new ModelSelectionCodec.ModelSelection("OpenAI", "gpt-4.1")
        ));
        var selectableResolver = new ProviderSelectableResolver();
        var availabilityResolver = new StubProviderAvailabilityResolver(settingsRepo("provider-menu-data"));

        var subject = new ProviderMenuDataResolver(
                modelsResolver,
                selectableResolver,
                favoritesResolver,
                availabilityResolver
        );

        ProviderMenuDataResolver.ProviderMenuData menuData = subject.resolve(providers);

        assertThat(modelsResolver.lastProviders).isEqualTo(providers);
        assertThat(favoritesResolver.lastProviders).isEqualTo(providers);
        assertThat(favoritesResolver.lastModelsByProvider).isEqualTo(modelsByProvider);

        assertThat(menuData.modelsByProvider()).isEqualTo(modelsByProvider);
        assertThat(menuData.providerSelectable()).containsEntry("OpenAI", true).containsEntry("Ollama", false);
        assertThat(menuData.favorites())
                .containsExactly(new ModelSelectionCodec.ModelSelection("OpenAI", "gpt-4.1"));
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
             )
        ) {
            statement.execute();
        }
    }

    private static class StubProviderModelsResolver extends ProviderModelsResolver {

        private final Map<String, List<String>> modelsByProvider;
        private List<ProviderRegistry.ProviderDef> lastProviders;

        private StubProviderModelsResolver(Map<String, List<String>> modelsByProvider) {
            super(new ProviderModelCacheService(new ModelCache(StoragePaths.defaultPaths())));
            this.modelsByProvider = modelsByProvider;
        }

        @Override
        public Map<String, List<String>> resolve(List<ProviderRegistry.ProviderDef> providers) {
            this.lastProviders = providers;
            return modelsByProvider;
        }
    }

    private static class StubProviderFavoritesResolver extends ProviderFavoritesResolver {

        private final List<ModelSelectionCodec.ModelSelection> favorites;
        private List<ProviderRegistry.ProviderDef> lastProviders;
        private Map<String, List<String>> lastModelsByProvider;

        private StubProviderFavoritesResolver(List<ModelSelectionCodec.ModelSelection> favorites) {
            super(ModelFavoritesService.createInMemory());
            this.favorites = favorites;
        }

        @Override
        public List<ModelSelectionCodec.ModelSelection> resolveFavoriteSelections(
                List<ProviderRegistry.ProviderDef> providers,
                Map<String, List<String>> modelsByProvider
        ) {
            this.lastProviders = providers;
            this.lastModelsByProvider = modelsByProvider;
            return favorites;
        }
    }

    private static class StubProviderAvailabilityResolver extends ProviderAvailabilityResolver {

        private StubProviderAvailabilityResolver(SettingsRepo settingsRepo) {
            super(settingsRepo);
        }

        @Override
        public boolean isModelSelectionEnabled(ProviderRegistry.ProviderDef providerDef) {
            return !"Ollama".equals(providerDef.name());
        }
    }
}
