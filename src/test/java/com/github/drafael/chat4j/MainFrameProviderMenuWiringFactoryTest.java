package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCache;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.support.ProviderAvailabilityLabelFormatter;
import com.github.drafael.chat4j.provider.support.ProviderFavoritesSectionAppender;
import com.github.drafael.chat4j.provider.support.ProviderHeaderMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityApplier;
import com.github.drafael.chat4j.provider.support.ProviderMenuEmptyStateFactory;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconTintResolver;
import com.github.drafael.chat4j.provider.support.ProviderModelMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderSelectableResolver;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameProviderMenuWiringFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Create builds non-null provider-menu wiring graph")
    void create_whenCalled_buildsProviderMenuWiring() throws Exception {
        var subject = new MainFrameProviderMenuWiringFactory();
        var settingsRepo = settingsRepo("mainframe-provider-menu-wiring");
        var modelCacheService = new ProviderModelCacheService(new ProviderModelCache(StoragePaths.defaultPaths()));
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
        var modelCacheService = new ProviderModelCacheService(new ProviderModelCache(StoragePaths.defaultPaths()));
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

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }
}
