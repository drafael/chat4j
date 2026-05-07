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
import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import com.github.drafael.chat4j.settings.AssistantRenderModeChangeUiApplyCoordinator;
import com.github.drafael.chat4j.settings.AssistantRenderModeSelectionResolver;
import com.github.drafael.chat4j.settings.FontMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.FontPreviewApplier;
import com.github.drafael.chat4j.settings.FontSelectionNormalizer;
import com.github.drafael.chat4j.settings.GeneralSettingsUiApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionSynchronizer;
import com.github.drafael.chat4j.storage.ConversationLoadResultPlanner;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.PersistedMessageCounter;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.github.drafael.chat4j.util.MenuPopupVisibleRunner;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameDependenciesFactoryTest {

    @Test
    @DisplayName("Create builds non-null dependencies and wiring graphs")
    void create_whenCalled_buildsMainFrameDependencies() {
        var subject = new MainFrameDependenciesFactory();

        MainFrameDependenciesFactory.MainFrameDependencies dependencies = subject.create(context());

        assertThat(dependencies.providerMenuWiring()).isNotNull();
        assertThat(dependencies.settingsWiring()).isNotNull();
        assertThat(dependencies.lifecycleWiring()).isNotNull();
        assertThat(dependencies.conversationWiring()).isNotNull();
    }

    @Test
    @DisplayName("Create validates required dependencies context")
    void create_whenContextMissing_throwsException() {
        var subject = new MainFrameDependenciesFactory();

        assertThatThrownBy(() -> subject.create(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
    }

    @Test
    @DisplayName("Conversation wiring planner uses provided conversation mode resolver")
    void create_whenConversationModeResolverProvided_plannerAppliesProvidedMode() {
        var subject = new MainFrameDependenciesFactory();
        var dependencies = subject.create(context(conversationId -> AssistantRenderMode.MARKDOWN));
        var planner = dependencies.conversationWiring().conversationLoadResultPlanner();
        var conversationId = UUID.randomUUID();

        var plan = planner.planLoaded(
                0,
                conversationId,
                conversationId,
                List.of(new ConversationRepo.MessageRecord(
                        UUID.randomUUID(),
                        Message.user("hello"),
                        LocalDateTime.now()
                )),
                new ConversationRepo.ConversationRecord(
                        conversationId,
                        "demo",
                        "OpenAI",
                        "gpt-4.1",
                        false,
                        "off",
                        false,
                        null,
                        LocalDateTime.now(),
                        LocalDateTime.now()
                )
        );

        assertThat(plan.ignore()).isFalse();
        assertThat(plan.assistantRenderMode()).isEqualTo(AssistantRenderMode.MARKDOWN);
        assertThat(plan.selectedModelKey()).isEqualTo(ModelSelectionCodec.format("OpenAI", "gpt-4.1"));
    }

    @Test
    @DisplayName("Dependencies context validates required components")
    void dependenciesContext_whenComponentMissing_throwsException() {
        assertThatThrownBy(() -> new MainFrameDependenciesFactory.DependenciesContext(
                new ConversationRepo(null),
                null,
                new ProviderModelCacheService(null),
                ModelFavoritesService.createInMemory(),
                new ProviderSelectableResolver(),
                new ProviderMenuAvailabilityApplier(),
                new ProviderAvailabilityLabelFormatter(),
                new ProviderHeaderMenuItemFactory((providerName, item, enabled) -> null),
                new ProviderMenuEmptyStateFactory(),
                new ProviderModelMenuItemFactory(
                        new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), MainFrame.class)
                ),
                new ProviderFavoritesSectionAppender(
                        new ProviderModelMenuItemFactory(
                                new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), MainFrame.class)
                        )
                ),
                new AssistantRenderModeSelectionResolver(),
                new AssistantRenderModeChangeUiApplyCoordinator(),
                new GeneralSettingsUiApplyCoordinator(),
                new FontSelectionNormalizer(),
                new FontPreviewApplier(),
                new FontMenuSelectionSynchronizer(),
                new FontMenuSelectionApplyCoordinator(),
                new ThemeMenuSelectionSynchronizer(),
                new ThemeMenuSelectionApplyCoordinator(),
                new MenuPopupVisibleRunner(),
                new PersistedMessageCounter(),
                conversationId -> null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsRepo");
    }

    private MainFrameDependenciesFactory.DependenciesContext context() {
        return context(conversationId -> AssistantRenderMode.PREVIEW);
    }

    private MainFrameDependenciesFactory.DependenciesContext context(
            ConversationLoadResultPlanner.ConversationModeResolver conversationModeResolver
    ) {
        var providerMenuIconResolver = new ProviderMenuIconResolver(
                new ProviderMenuIconTintResolver(),
                MainFrameDependenciesFactoryTest.class
        );
        var providerModelMenuItemFactory = new ProviderModelMenuItemFactory(providerMenuIconResolver);

        return new MainFrameDependenciesFactory.DependenciesContext(
                new ConversationRepo(null),
                new SettingsRepo(Path.of("target", "test-mainframe-dependencies-settings.properties")),
                new ProviderModelCacheService(null),
                ModelFavoritesService.createInMemory(),
                new ProviderSelectableResolver(),
                new ProviderMenuAvailabilityApplier(),
                new ProviderAvailabilityLabelFormatter(),
                new ProviderHeaderMenuItemFactory(providerMenuIconResolver::resolveHeaderIcon),
                new ProviderMenuEmptyStateFactory(),
                providerModelMenuItemFactory,
                new ProviderFavoritesSectionAppender(providerModelMenuItemFactory),
                new AssistantRenderModeSelectionResolver(),
                new AssistantRenderModeChangeUiApplyCoordinator(),
                new GeneralSettingsUiApplyCoordinator(),
                new FontSelectionNormalizer(),
                new FontPreviewApplier(),
                new FontMenuSelectionSynchronizer(),
                new FontMenuSelectionApplyCoordinator(),
                new ThemeMenuSelectionSynchronizer(),
                new ThemeMenuSelectionApplyCoordinator(),
                new MenuPopupVisibleRunner(),
                new PersistedMessageCounter(),
                conversationModeResolver
        );
    }
}
