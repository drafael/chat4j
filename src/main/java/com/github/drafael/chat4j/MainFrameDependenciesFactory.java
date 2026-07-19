package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.conversation.ConversationLoadResultPlanner;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.persistence.conversation.PersistedMessageCounter;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ProviderAvailabilityLabelFormatter;
import com.github.drafael.chat4j.provider.support.ProviderFavoritesSectionAppender;
import com.github.drafael.chat4j.provider.support.ProviderHeaderMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityApplier;
import com.github.drafael.chat4j.provider.support.ProviderMenuEmptyStateFactory;
import com.github.drafael.chat4j.provider.support.ProviderModelMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderSelectableResolver;
import com.github.drafael.chat4j.settings.FontMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.FontPreviewApplier;
import com.github.drafael.chat4j.settings.FontSelectionNormalizer;
import com.github.drafael.chat4j.settings.GeneralSettingsUiApplyCoordinator;
import com.github.drafael.chat4j.settings.RenderModeChangeUiApplyCoordinator;
import com.github.drafael.chat4j.settings.RenderModeSelectionResolver;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionSynchronizer;
import com.github.drafael.chat4j.util.MenuPopupVisibleRunner;
import lombok.NonNull;

public class MainFrameDependenciesFactory {

    private final MainFrameProviderMenuWiringFactory providerMenuWiringFactory;
    private final MainFrameSettingsWiringFactory settingsWiringFactory;
    private final MainFrameLifecycleWiringFactory lifecycleWiringFactory;
    private final MainFrameConversationWiringFactory conversationWiringFactory;

    public MainFrameDependenciesFactory() {
        this(
                new MainFrameProviderMenuWiringFactory(),
                new MainFrameSettingsWiringFactory(),
                new MainFrameLifecycleWiringFactory(),
                new MainFrameConversationWiringFactory()
        );
    }

    MainFrameDependenciesFactory(
            @NonNull MainFrameProviderMenuWiringFactory providerMenuWiringFactory,
            @NonNull MainFrameSettingsWiringFactory settingsWiringFactory,
            @NonNull MainFrameLifecycleWiringFactory lifecycleWiringFactory,
            @NonNull MainFrameConversationWiringFactory conversationWiringFactory
    ) {
        this.providerMenuWiringFactory = providerMenuWiringFactory;
        this.settingsWiringFactory = settingsWiringFactory;
        this.lifecycleWiringFactory = lifecycleWiringFactory;
        this.conversationWiringFactory = conversationWiringFactory;
    }

    public MainFrameDependencies create(@NonNull DependenciesContext context) {
        var providerMenuWiring = providerMenuWiringFactory.create(
                context.settingsRepo(),
                context.providerRegistry(),
                context.modelCacheService(),
                context.modelFavoritesService(),
                context.providerSelectableResolver(),
                context.providerMenuAvailabilityApplier(),
                context.providerAvailabilityLabelFormatter(),
                context.providerHeaderMenuItemFactory(),
                context.providerMenuEmptyStateFactory(),
                context.providerModelMenuItemFactory(),
                context.providerFavoritesSectionAppender()
        );
        var settingsWiring = settingsWiringFactory.create(
                context.settingsRepo(),
                context.renderModeSelectionResolver(),
                context.renderModeChangeUiApplyCoordinator(),
                context.generalSettingsUiApplyCoordinator(),
                context.fontSelectionNormalizer(),
                context.fontPreviewApplier(),
                context.fontMenuSelectionSynchronizer(),
                context.fontMenuSelectionApplyCoordinator(),
                context.themeMenuSelectionSynchronizer(),
                context.themeMenuSelectionApplyCoordinator()
        );
        var lifecycleWiring = lifecycleWiringFactory.create(
                context.settingsRepo(),
                context.menuPopupVisibleRunner()
        );
        var conversationWiring = conversationWiringFactory.create(
                context.conversationRepo(),
                context.persistedMessageCounter()
        );

        return new MainFrameDependencies(
                providerMenuWiring,
                settingsWiring,
                lifecycleWiring,
                conversationWiring
        );
    }

    public record DependenciesContext(
            @NonNull ConversationRepository conversationRepo,
            @NonNull SettingsRepository settingsRepo,
            @NonNull ProviderRegistry providerRegistry,
            @NonNull ProviderModelCacheService modelCacheService,
            @NonNull ModelFavoritesService modelFavoritesService,
            @NonNull ProviderSelectableResolver providerSelectableResolver,
            @NonNull ProviderMenuAvailabilityApplier providerMenuAvailabilityApplier,
            @NonNull ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter,
            @NonNull ProviderHeaderMenuItemFactory providerHeaderMenuItemFactory,
            @NonNull ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory,
            @NonNull ProviderModelMenuItemFactory providerModelMenuItemFactory,
            @NonNull ProviderFavoritesSectionAppender providerFavoritesSectionAppender,
            @NonNull RenderModeSelectionResolver renderModeSelectionResolver,
            @NonNull RenderModeChangeUiApplyCoordinator renderModeChangeUiApplyCoordinator,
            @NonNull GeneralSettingsUiApplyCoordinator generalSettingsUiApplyCoordinator,
            @NonNull FontSelectionNormalizer fontSelectionNormalizer,
            @NonNull FontPreviewApplier fontPreviewApplier,
            @NonNull FontMenuSelectionSynchronizer fontMenuSelectionSynchronizer,
            @NonNull FontMenuSelectionApplyCoordinator fontMenuSelectionApplyCoordinator,
            @NonNull ThemeMenuSelectionSynchronizer themeMenuSelectionSynchronizer,
            @NonNull ThemeMenuSelectionApplyCoordinator themeMenuSelectionApplyCoordinator,
            @NonNull MenuPopupVisibleRunner menuPopupVisibleRunner,
            @NonNull PersistedMessageCounter persistedMessageCounter
    ) {
    }

    public record MainFrameDependencies(
            MainFrameProviderMenuWiringFactory.ProviderMenuWiring providerMenuWiring,
            MainFrameSettingsWiringFactory.SettingsWiring settingsWiring,
            MainFrameLifecycleWiringFactory.LifecycleWiring lifecycleWiring,
            MainFrameConversationWiringFactory.ConversationWiring conversationWiring
    ) {
    }
}
