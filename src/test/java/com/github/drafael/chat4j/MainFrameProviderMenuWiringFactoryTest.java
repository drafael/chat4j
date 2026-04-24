package com.github.drafael.chat4j;

import com.github.drafael.chat4j.provider.support.ProviderAvailabilityLabelFormatter;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityApplier;
import com.github.drafael.chat4j.provider.support.ProviderMenuEmptyStateFactory;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconTintResolver;
import com.github.drafael.chat4j.provider.support.ProviderFavoritesSectionAppender;
import com.github.drafael.chat4j.provider.support.ProviderHeaderMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderModelMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderSelectableResolver;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameProviderMenuWiringFactoryTest {

    @Test
    @DisplayName("Create builds non-null provider-menu wiring graph")
    void create_whenCalled_buildsProviderMenuWiring() throws Exception {
        var subject = new MainFrameProviderMenuWiringFactory();
        var settingsRepo = settingsRepo("mainframe-provider-menu-wiring");
        var modelCacheService = new ProviderModelCacheService(new ModelCache(StoragePaths.defaultPaths()));
        var modelFavoritesService = ModelFavoritesService.createInMemory();
        var providerSelectableResolver = new ProviderSelectableResolver();
        var providerMenuAvailabilityApplier = new ProviderMenuAvailabilityApplier();
        var providerAvailabilityLabelFormatter = new ProviderAvailabilityLabelFormatter();
        var providerMenuIconResolver =
                new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), MainFrameProviderMenuWiringFactoryTest.class);
        var providerHeaderMenuItemFactory = new ProviderHeaderMenuItemFactory(providerMenuIconResolver::resolveHeaderIcon);
        var providerMenuEmptyStateFactory = new ProviderMenuEmptyStateFactory();
        var providerModelMenuItemFactory = new ProviderModelMenuItemFactory(providerMenuIconResolver);
        var providerFavoritesSectionAppender =
                new ProviderFavoritesSectionAppender(providerModelMenuItemFactory);

        MainFrameProviderMenuWiringFactory.ProviderMenuWiring wiring = subject.create(
                settingsRepo,
                modelCacheService,
                modelFavoritesService,
                providerSelectableResolver,
                providerMenuAvailabilityApplier,
                providerAvailabilityLabelFormatter,
                providerHeaderMenuItemFactory,
                providerMenuEmptyStateFactory,
                providerModelMenuItemFactory,
                providerFavoritesSectionAppender
        );

        assertThat(wiring.providerSettingsApplyCoordinator()).isNotNull();
        assertThat(wiring.providerModelsResolver()).isNotNull();
        assertThat(wiring.providerFavoritesResolver()).isNotNull();
        assertThat(wiring.providerAvailabilityResolver()).isNotNull();
        assertThat(wiring.providerMenuDataResolver()).isNotNull();
        assertThat(wiring.providerMenuAvailabilityRefreshCoordinator()).isNotNull();
        assertThat(wiring.providerMenuAvailabilityRefreshDispatchCoordinator()).isNotNull();
        assertThat(wiring.providerCatalogSectionAppender()).isNotNull();
        assertThat(wiring.providerMenuStructureRebuilder()).isNotNull();
        assertThat(wiring.modelMenuStructureRebuildCoordinator()).isNotNull();
    }

    @Test
    @DisplayName("Create validates required dependencies")
    void create_whenRequiredDependencyMissing_throwsException() throws Exception {
        var subject = new MainFrameProviderMenuWiringFactory();
        var settingsRepo = settingsRepo("mainframe-provider-menu-wiring-validation");
        var modelCacheService = new ProviderModelCacheService(new ModelCache(StoragePaths.defaultPaths()));
        var modelFavoritesService = ModelFavoritesService.createInMemory();
        var providerSelectableResolver = new ProviderSelectableResolver();
        var providerMenuAvailabilityApplier = new ProviderMenuAvailabilityApplier();
        var providerAvailabilityLabelFormatter = new ProviderAvailabilityLabelFormatter();
        var providerMenuIconResolver =
                new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), MainFrameProviderMenuWiringFactoryTest.class);
        var providerHeaderMenuItemFactory = new ProviderHeaderMenuItemFactory(providerMenuIconResolver::resolveHeaderIcon);
        var providerMenuEmptyStateFactory = new ProviderMenuEmptyStateFactory();
        var providerModelMenuItemFactory = new ProviderModelMenuItemFactory(providerMenuIconResolver);
        var providerFavoritesSectionAppender =
                new ProviderFavoritesSectionAppender(providerModelMenuItemFactory);

        assertThatThrownBy(() -> subject.create(
                null,
                modelCacheService,
                modelFavoritesService,
                providerSelectableResolver,
                providerMenuAvailabilityApplier,
                providerAvailabilityLabelFormatter,
                providerHeaderMenuItemFactory,
                providerMenuEmptyStateFactory,
                providerModelMenuItemFactory,
                providerFavoritesSectionAppender
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsRepo");

        assertThatThrownBy(() -> subject.create(
                settingsRepo,
                modelCacheService,
                modelFavoritesService,
                null,
                providerMenuAvailabilityApplier,
                providerAvailabilityLabelFormatter,
                providerHeaderMenuItemFactory,
                providerMenuEmptyStateFactory,
                providerModelMenuItemFactory,
                providerFavoritesSectionAppender
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providerSelectableResolver");
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
}
