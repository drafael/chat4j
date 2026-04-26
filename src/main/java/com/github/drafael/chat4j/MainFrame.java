package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.chat.ChatPanel;
import com.github.drafael.chat4j.chat.ChatSearchPopup;
import com.github.drafael.chat4j.chat.ChatSearchPopupCoordinator;
import com.github.drafael.chat4j.chat.NewChatCoordinator;
import com.github.drafael.chat4j.menu.BoundMenuFactory;
import com.github.drafael.chat4j.menu.FileMenuFactory;
import com.github.drafael.chat4j.menu.HelpMenuFactory;
import com.github.drafael.chat4j.menu.HelpMenuVisibilityResolver;
import com.github.drafael.chat4j.menu.MainMenuBarApplyStateCoordinator;
import com.github.drafael.chat4j.menu.MainMenuBarBuilder;
import com.github.drafael.chat4j.menu.MainMenuBarCreateDispatchCoordinator;
import com.github.drafael.chat4j.menu.MainMenuBarCreatedApplyCoordinator;
import com.github.drafael.chat4j.menu.MainMenuBarEnsureCoordinator;
import com.github.drafael.chat4j.menu.MainMenuBarEnsureApplyFlowCoordinator;
import com.github.drafael.chat4j.menu.MainMenuBarEnsureDispatchCoordinator;
import com.github.drafael.chat4j.menu.MainMenuBarEnsureResultApplyCoordinator;
import com.github.drafael.chat4j.menu.MainMenuBarEnsureStateResolver;
import com.github.drafael.chat4j.menu.MenuBarAssemblyFactory;
import com.github.drafael.chat4j.menu.MenuSectionHeaderFactory;
import com.github.drafael.chat4j.menu.MenuSelectionListenerBinder;
import com.github.drafael.chat4j.menu.ViewMenuFactory;
import com.github.drafael.chat4j.util.LookAndFeelMenuRefreshCoordinator;
import com.github.drafael.chat4j.util.MenuPopupVisibleRunner;
import com.github.drafael.chat4j.util.TitleBarUiSupport;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshTriggerCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderAvailabilityLabelFormatter;
import com.github.drafael.chat4j.provider.support.ProviderAvailabilityResolver;
import com.github.drafael.chat4j.provider.support.ProviderCatalogSectionAppender;
import com.github.drafael.chat4j.provider.support.ProviderFavoritesResolver;
import com.github.drafael.chat4j.provider.support.ProviderFavoritesSectionAppender;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityApplier;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityRefreshCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityRefreshDispatchCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuDataResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuEmptyStateFactory;
import com.github.drafael.chat4j.provider.support.ProviderMenuReadyCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuReadyDispatchCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderHeaderMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderModelsResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconRenderer;
import com.github.drafael.chat4j.provider.support.ProviderSelectableResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconResolver;
import com.github.drafael.chat4j.provider.support.ProviderModelMenuItemFactory;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconTintResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuStructureRebuilder;
import com.formdev.flatlaf.util.SystemInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import com.github.drafael.chat4j.settings.AppFontSizeAdjustCoordinator;
import com.github.drafael.chat4j.settings.AppFontSizeStepResolver;
import com.github.drafael.chat4j.settings.AppearancePanel;
import com.github.drafael.chat4j.settings.AssistantRenderModeChangeCoordinator;
import com.github.drafael.chat4j.settings.AssistantRenderModeChangeDispatchCoordinator;
import com.github.drafael.chat4j.settings.AssistantRenderModeChangePlanner;
import com.github.drafael.chat4j.settings.AssistantRenderModeChangeUiApplyCoordinator;
import com.github.drafael.chat4j.settings.AssistantRenderModeSelectionResolver;
import com.github.drafael.chat4j.settings.AssistantRenderModeToggleCoordinator;
import com.github.drafael.chat4j.settings.AssistantRenderModeToggleSelectionSyncCoordinator;
import com.github.drafael.chat4j.settings.AssistantRenderModeSettingsCoordinator;
import com.github.drafael.chat4j.settings.FontMenuApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuApplyDispatchCoordinator;
import com.github.drafael.chat4j.settings.FontMenuReadyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuReadyDispatchCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionDispatchCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionFlowCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionRefreshCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.FontMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.settings.FontMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.settings.FontMenuStructureRebuilder;
import com.github.drafael.chat4j.settings.FontPreviewApplier;
import com.github.drafael.chat4j.settings.FontSelectionNormalizer;
import com.github.drafael.chat4j.settings.FontSettingsPersister;
import com.github.drafael.chat4j.settings.FontSettingsResolver;
import com.github.drafael.chat4j.settings.GeneralSettingsApplyCoordinator;
import com.github.drafael.chat4j.settings.GeneralSettingsApplyDispatchCoordinator;
import com.github.drafael.chat4j.settings.GeneralSettingsDefaultModeApplyCoordinator;
import com.github.drafael.chat4j.settings.GeneralSettingsResolver;
import com.github.drafael.chat4j.settings.GeneralSettingsUiApplyCoordinator;
import com.github.drafael.chat4j.settings.MenuBarSettingCoordinator;
import com.github.drafael.chat4j.settings.MenuBarSettingDispatchCoordinator;
import com.github.drafael.chat4j.settings.ProviderRuntimeSettingsResolver;
import com.github.drafael.chat4j.settings.ProviderSettingsApplyCoordinator;
import com.github.drafael.chat4j.settings.SettingsDialog;
import com.github.drafael.chat4j.settings.SettingsDialogCoordinator;
import com.github.drafael.chat4j.settings.SettingsOpenDispatchCoordinator;
import com.github.drafael.chat4j.settings.SettingsOpenFlowCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuApplyDispatchCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuReadyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuReadyDispatchCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionDispatchCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionFlowCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionRefreshCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.ThemeMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuStructureRebuilder;
import com.github.drafael.chat4j.settings.ThemeSettingsResolver;
import com.github.drafael.chat4j.settings.WindowStateRestoreCoordinator;
import com.github.drafael.chat4j.settings.WindowStateSettingsCoordinator;
import com.github.drafael.chat4j.sidebar.SidebarPanel;
import com.github.drafael.chat4j.sidebar.SidebarToggleCoordinator;
import com.github.drafael.chat4j.sidebar.SidebarToggleStateApplyCoordinator;
import com.github.drafael.chat4j.storage.AssistantMessageCompletionCoordinator;
import com.github.drafael.chat4j.storage.AssistantMessageCompletionDispatchCoordinator;
import com.github.drafael.chat4j.storage.AssistantMessageCompletionEventDispatchCoordinator;
import com.github.drafael.chat4j.storage.AssistantMessageCompletionFlowCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadApplyCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadApplyDispatchCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadDispatchCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadFailureCoordinator;
import com.github.drafael.chat4j.storage.ConversationLoadResultPlanner;
import com.github.drafael.chat4j.storage.ConversationLoadStartCoordinator;
import com.github.drafael.chat4j.storage.ConversationPersistenceCoordinator;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.ConversationTitleDeriver;
import com.github.drafael.chat4j.storage.CurrentConversationSaveCoordinator;
import com.github.drafael.chat4j.storage.CurrentConversationSaveDispatchCoordinator;
import com.github.drafael.chat4j.storage.CurrentConversationSaveUiApplyCoordinator;
import com.github.drafael.chat4j.storage.ShutdownFlowCoordinator;
import com.github.drafael.chat4j.storage.ShutdownSaveDispatchCoordinator;
import com.github.drafael.chat4j.storage.ConversationRepo.MessageRecord;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.PersistedMessageCounter;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toMap;

@Slf4j
public class MainFrame extends JFrame {
    private static final long SHUTDOWN_SAVE_TIMEOUT_MILLIS = 2000;

    private final ChatPanel chatPanel;
    private final SidebarPanel sidebarPanel;
    private final NewChatCoordinator newChatCoordinator = new NewChatCoordinator();
    private final HelpMenuVisibilityResolver helpMenuVisibilityResolver = new HelpMenuVisibilityResolver();
    private final HelpMenuFactory helpMenuFactory = new HelpMenuFactory();
    private final MenuBarAssemblyFactory menuBarAssemblyFactory =
            new MenuBarAssemblyFactory(helpMenuVisibilityResolver, helpMenuFactory);
    private final FileMenuFactory fileMenuFactory = new FileMenuFactory();
    private final MenuSectionHeaderFactory menuSectionHeaderFactory = new MenuSectionHeaderFactory();
    private final MenuSelectionListenerBinder menuSelectionListenerBinder = new MenuSelectionListenerBinder();
    private final BoundMenuFactory boundMenuFactory = new BoundMenuFactory(menuSelectionListenerBinder);
    private final ViewMenuFactory viewMenuFactory = new ViewMenuFactory(menuSelectionListenerBinder);
    private final MainMenuBarBuilder mainMenuBarBuilder =
            new MainMenuBarBuilder(fileMenuFactory, viewMenuFactory, boundMenuFactory, menuBarAssemblyFactory);
    private final MainMenuBarCreateDispatchCoordinator mainMenuBarCreateDispatchCoordinator =
            new MainMenuBarCreateDispatchCoordinator(mainMenuBarBuilder);
    private final MainMenuBarEnsureCoordinator mainMenuBarEnsureCoordinator = new MainMenuBarEnsureCoordinator();
    private final MainMenuBarCreatedApplyCoordinator mainMenuBarCreatedApplyCoordinator =
            new MainMenuBarCreatedApplyCoordinator();
    private final MainMenuBarEnsureDispatchCoordinator mainMenuBarEnsureDispatchCoordinator =
            new MainMenuBarEnsureDispatchCoordinator(mainMenuBarEnsureCoordinator, mainMenuBarCreatedApplyCoordinator);
    private final MainMenuBarEnsureResultApplyCoordinator mainMenuBarEnsureResultApplyCoordinator =
            new MainMenuBarEnsureResultApplyCoordinator();
    private final MainMenuBarEnsureStateResolver mainMenuBarEnsureStateResolver =
            new MainMenuBarEnsureStateResolver(
                    mainMenuBarEnsureDispatchCoordinator,
                    mainMenuBarEnsureResultApplyCoordinator
            );
    private final MainMenuBarApplyStateCoordinator mainMenuBarApplyStateCoordinator =
            new MainMenuBarApplyStateCoordinator();
    private final MainMenuBarEnsureApplyFlowCoordinator mainMenuBarEnsureApplyFlowCoordinator =
            new MainMenuBarEnsureApplyFlowCoordinator(
                    mainMenuBarEnsureStateResolver,
                    mainMenuBarApplyStateCoordinator
            );
    private final SidebarToggleCoordinator sidebarToggleCoordinator = new SidebarToggleCoordinator();
    private final SidebarToggleStateApplyCoordinator sidebarToggleStateApplyCoordinator =
            new SidebarToggleStateApplyCoordinator();
    private final MainFrameDependenciesFactory mainFrameDependenciesFactory = new MainFrameDependenciesFactory();
    private final JSplitPane splitPane;
    private final ConversationRepo conversationRepo;
    private final SettingsRepo settingsRepo;
    private final ProviderModelCacheService modelCacheService;
    private final ModelFavoritesService modelFavoritesService;
    private final ProviderSettingsApplyCoordinator providerSettingsApplyCoordinator;
    private final ProviderModelsResolver providerModelsResolver;
    private final ProviderFavoritesResolver providerFavoritesResolver;
    private final ProviderAvailabilityResolver providerAvailabilityResolver;
    private final ProviderSelectableResolver providerSelectableResolver = new ProviderSelectableResolver();
    private final ProviderMenuDataResolver providerMenuDataResolver;
    private final ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter =
            new ProviderAvailabilityLabelFormatter();
    private final ProviderMenuAvailabilityApplier providerMenuAvailabilityApplier = new ProviderMenuAvailabilityApplier();
    private final ProviderMenuAvailabilityRefreshCoordinator providerMenuAvailabilityRefreshCoordinator;
    private final ProviderMenuAvailabilityRefreshDispatchCoordinator providerMenuAvailabilityRefreshDispatchCoordinator;
    private final ProviderMenuReadyCoordinator providerMenuReadyCoordinator = new ProviderMenuReadyCoordinator();
    private final ProviderMenuReadyDispatchCoordinator providerMenuReadyDispatchCoordinator =
            new ProviderMenuReadyDispatchCoordinator(providerMenuReadyCoordinator);
    private final ProviderMenuEmptyStateFactory providerMenuEmptyStateFactory = new ProviderMenuEmptyStateFactory();
    private final ProviderMenuIconTintResolver providerMenuIconTintResolver = new ProviderMenuIconTintResolver();
    private final ProviderMenuIconResolver providerMenuIconResolver =
            new ProviderMenuIconResolver(providerMenuIconTintResolver, MainFrame.class);
    private final ProviderModelMenuItemFactory providerModelMenuItemFactory =
            new ProviderModelMenuItemFactory(providerMenuIconResolver);
    private final ProviderHeaderMenuItemFactory providerHeaderMenuItemFactory =
            new ProviderHeaderMenuItemFactory(providerMenuIconResolver::resolveHeaderIcon);
    private final ProviderFavoritesSectionAppender providerFavoritesSectionAppender =
            new ProviderFavoritesSectionAppender(providerModelMenuItemFactory);
    private final ProviderCatalogSectionAppender providerCatalogSectionAppender;
    private final ProviderMenuStructureRebuilder providerMenuStructureRebuilder;
    private final ModelMenuStructureRebuildCoordinator modelMenuStructureRebuildCoordinator;
    private final ModelMenuStructureRebuildApplyCoordinator modelMenuStructureRebuildApplyCoordinator =
            new ModelMenuStructureRebuildApplyCoordinator();
    private final AssistantRenderModeSettingsCoordinator assistantRenderModeSettingsCoordinator;
    private final AssistantRenderModeChangeCoordinator assistantRenderModeChangeCoordinator;
    private final AssistantRenderModeChangeUiApplyCoordinator assistantRenderModeChangeUiApplyCoordinator =
            new AssistantRenderModeChangeUiApplyCoordinator();
    private final AssistantRenderModeChangeDispatchCoordinator assistantRenderModeChangeDispatchCoordinator;
    private final AssistantRenderModeToggleCoordinator assistantRenderModeToggleCoordinator =
            new AssistantRenderModeToggleCoordinator();
    private final AssistantRenderModeToggleSelectionSyncCoordinator assistantRenderModeToggleSelectionSyncCoordinator =
            new AssistantRenderModeToggleSelectionSyncCoordinator();
    private final AssistantRenderModeSelectionResolver assistantRenderModeSelectionResolver =
            new AssistantRenderModeSelectionResolver();
    private final GeneralSettingsResolver generalSettingsResolver;
    private final GeneralSettingsApplyCoordinator generalSettingsApplyCoordinator;
    private final GeneralSettingsUiApplyCoordinator generalSettingsUiApplyCoordinator =
            new GeneralSettingsUiApplyCoordinator();
    private final GeneralSettingsApplyDispatchCoordinator generalSettingsApplyDispatchCoordinator;
    private final GeneralSettingsDefaultModeApplyCoordinator generalSettingsDefaultModeApplyCoordinator =
            new GeneralSettingsDefaultModeApplyCoordinator();
    private final MenuBarSettingCoordinator menuBarSettingCoordinator = new MenuBarSettingCoordinator();
    private final MenuBarSettingDispatchCoordinator menuBarSettingDispatchCoordinator =
            new MenuBarSettingDispatchCoordinator(menuBarSettingCoordinator);
    private final FontSettingsResolver fontSettingsResolver;
    private final FontSelectionNormalizer fontSelectionNormalizer = new FontSelectionNormalizer();
    private final FontPreviewApplier fontPreviewApplier = new FontPreviewApplier();
    private final FontSettingsPersister fontSettingsPersister;
    private final FontMenuApplyCoordinator fontMenuApplyCoordinator;
    private final FontMenuApplyDispatchCoordinator fontMenuApplyDispatchCoordinator =
            new FontMenuApplyDispatchCoordinator();
    private final AppFontSizeStepResolver appFontSizeStepResolver;
    private final AppFontSizeAdjustCoordinator appFontSizeAdjustCoordinator;
    private final ThemeSettingsResolver themeSettingsResolver;
    private final ThemeMenuApplyCoordinator themeMenuApplyCoordinator;
    private final ThemeMenuApplyDispatchCoordinator themeMenuApplyDispatchCoordinator =
            new ThemeMenuApplyDispatchCoordinator();
    private final ThemeMenuStructureRebuilder themeMenuStructureRebuilder =
            new ThemeMenuStructureRebuilder(menuSectionHeaderFactory);
    private final ThemeMenuStructureRebuildCoordinator themeMenuStructureRebuildCoordinator =
            new ThemeMenuStructureRebuildCoordinator(themeMenuStructureRebuilder);
    private final ThemeMenuStructureRebuildApplyCoordinator themeMenuStructureRebuildApplyCoordinator =
            new ThemeMenuStructureRebuildApplyCoordinator();
    private final ThemeMenuSelectionSynchronizer themeMenuSelectionSynchronizer = new ThemeMenuSelectionSynchronizer();
    private final ThemeMenuReadyCoordinator themeMenuReadyCoordinator = new ThemeMenuReadyCoordinator();
    private final ThemeMenuReadyDispatchCoordinator themeMenuReadyDispatchCoordinator =
            new ThemeMenuReadyDispatchCoordinator(themeMenuReadyCoordinator);
    private final ThemeMenuSelectionRefreshCoordinator themeMenuSelectionRefreshCoordinator;
    private final ThemeMenuSelectionDispatchCoordinator themeMenuSelectionDispatchCoordinator;
    private final ThemeMenuSelectionApplyCoordinator themeMenuSelectionApplyCoordinator =
            new ThemeMenuSelectionApplyCoordinator();
    private final ThemeMenuSelectionFlowCoordinator themeMenuSelectionFlowCoordinator;
    private final FontMenuStructureRebuilder fontMenuStructureRebuilder =
            new FontMenuStructureRebuilder(menuSectionHeaderFactory);
    private final FontMenuStructureRebuildCoordinator fontMenuStructureRebuildCoordinator =
            new FontMenuStructureRebuildCoordinator(fontMenuStructureRebuilder);
    private final FontMenuStructureRebuildApplyCoordinator fontMenuStructureRebuildApplyCoordinator =
            new FontMenuStructureRebuildApplyCoordinator();
    private final FontMenuSelectionSynchronizer fontMenuSelectionSynchronizer = new FontMenuSelectionSynchronizer();
    private final FontMenuReadyCoordinator fontMenuReadyCoordinator = new FontMenuReadyCoordinator();
    private final FontMenuReadyDispatchCoordinator fontMenuReadyDispatchCoordinator =
            new FontMenuReadyDispatchCoordinator(fontMenuReadyCoordinator);
    private final FontMenuSelectionRefreshCoordinator fontMenuSelectionRefreshCoordinator;
    private final FontMenuSelectionDispatchCoordinator fontMenuSelectionDispatchCoordinator;
    private final FontMenuSelectionApplyCoordinator fontMenuSelectionApplyCoordinator =
            new FontMenuSelectionApplyCoordinator();
    private final FontMenuSelectionFlowCoordinator fontMenuSelectionFlowCoordinator;
    private final WindowStateSettingsCoordinator windowStateSettingsCoordinator;
    private final WindowStateRestoreCoordinator windowStateRestoreCoordinator;
    private final ConversationLoadCoordinator conversationLoadCoordinator;
    private final ConversationLoadDispatchCoordinator conversationLoadDispatchCoordinator =
            new ConversationLoadDispatchCoordinator();
    private final ConversationLoadStartCoordinator conversationLoadStartCoordinator =
            new ConversationLoadStartCoordinator(conversationLoadDispatchCoordinator);
    private final ConversationLoadFailureCoordinator conversationLoadFailureCoordinator =
            new ConversationLoadFailureCoordinator();
    private final ConversationLoadResultPlanner conversationLoadResultPlanner;
    private final ConversationLoadApplyDispatchCoordinator conversationLoadApplyDispatchCoordinator;
    private final ConversationPersistenceCoordinator conversationPersistenceCoordinator;
    private final AssistantMessageCompletionDispatchCoordinator assistantMessageCompletionDispatchCoordinator =
            new AssistantMessageCompletionDispatchCoordinator();
    private final AssistantMessageCompletionEventDispatchCoordinator assistantMessageCompletionEventDispatchCoordinator =
            new AssistantMessageCompletionEventDispatchCoordinator(assistantMessageCompletionDispatchCoordinator);
    private final AssistantMessageCompletionFlowCoordinator assistantMessageCompletionFlowCoordinator;
    private final CurrentConversationSaveCoordinator currentConversationSaveCoordinator;
    private final CurrentConversationSaveDispatchCoordinator currentConversationSaveDispatchCoordinator =
            new CurrentConversationSaveDispatchCoordinator();
    private final CurrentConversationSaveUiApplyCoordinator currentConversationSaveUiApplyCoordinator =
            new CurrentConversationSaveUiApplyCoordinator();
    private final ShutdownSaveDispatchCoordinator shutdownSaveDispatchCoordinator = new ShutdownSaveDispatchCoordinator();
    private final ShutdownFlowCoordinator shutdownFlowCoordinator =
            new ShutdownFlowCoordinator(shutdownSaveDispatchCoordinator);
    private final MainFrameConversationState conversationState = new MainFrameConversationState();
    private final MainFrameShutdownState shutdownState = new MainFrameShutdownState();
    private final MainFrameSidebarState sidebarState = new MainFrameSidebarState();
    private final MainFrameSidebarToggleState sidebarToggleState = new MainFrameSidebarToggleState();
    private final ChatSearchPopupCoordinator chatSearchPopupCoordinator = new ChatSearchPopupCoordinator();
    private final MainFrameAssistantRenderModeState assistantRenderModeState = new MainFrameAssistantRenderModeState();
    private final SettingsDialogCoordinator settingsDialogCoordinator = new SettingsDialogCoordinator();
    private final SettingsOpenDispatchCoordinator settingsOpenDispatchCoordinator = new SettingsOpenDispatchCoordinator();
    private final SettingsOpenFlowCoordinator settingsOpenFlowCoordinator =
            new SettingsOpenFlowCoordinator(settingsOpenDispatchCoordinator, settingsDialogCoordinator);
    private final MainFrameTopMenusState topMenusState = new MainFrameTopMenusState();
    private final MainFrameBoundMenusState boundMenusState = new MainFrameBoundMenusState();
    private final MainFrameMenuItemsState menuItemsState = new MainFrameMenuItemsState();
    private final ModelMenuSelectionSynchronizer modelMenuSelectionSynchronizer = new ModelMenuSelectionSynchronizer();
    private final ModelMenuSelectionDispatchCoordinator modelMenuSelectionDispatchCoordinator =
            new ModelMenuSelectionDispatchCoordinator(modelMenuSelectionSynchronizer);
    private final ModelMenuSelectionApplyCoordinator modelMenuSelectionApplyCoordinator =
            new ModelMenuSelectionApplyCoordinator();
    private final ModelMenuSelectionChangeCoordinator modelMenuSelectionChangeCoordinator =
            new ModelMenuSelectionChangeCoordinator();
    private final MenuPopupVisibleRunner menuPopupVisibleRunner = new MenuPopupVisibleRunner();
    private final ModelMenuDirtyRefreshCoordinator modelMenuDirtyRefreshCoordinator;
    private final ModelMenuDirtyRefreshTriggerCoordinator modelMenuDirtyRefreshTriggerCoordinator;
    private final LookAndFeelMenuRefreshCoordinator lookAndFeelMenuRefreshCoordinator;
    private final PersistedMessageCounter persistedMessageCounter = new PersistedMessageCounter();
    private final MainFrameModelMenuState modelMenuState = new MainFrameModelMenuState();
    private final MainFrameThemeMenuState themeMenuState = new MainFrameThemeMenuState();
    private final MainFrameFontMenuState fontMenuState = new MainFrameFontMenuState();
    private final MainFramePreviewMenuState previewMenuState = new MainFramePreviewMenuState();
    private final PropertyChangeListener lookAndFeelListener = event -> {
        if (!"lookAndFeel".equals(event.getPropertyName())) {
            return;
        }

        SwingUtilities.invokeLater(this::onLookAndFeelChanged);
    };

    public MainFrame(
        ConversationRepo conversationRepo,
        SettingsRepo settingsRepo,
        ProviderModelCacheService modelCacheService,
        ModelFavoritesService modelFavoritesService
    ) {
        super("Chat4J");
        this.conversationRepo = conversationRepo;
        this.settingsRepo = settingsRepo;
        this.modelCacheService = modelCacheService;
        this.modelFavoritesService = modelFavoritesService;
        var dependencies = mainFrameDependenciesFactory.create(new MainFrameDependenciesFactory.DependenciesContext(
                conversationRepo,
                settingsRepo,
                modelCacheService,
                modelFavoritesService,
                providerSelectableResolver,
                providerMenuAvailabilityApplier,
                providerAvailabilityLabelFormatter,
                providerHeaderMenuItemFactory,
                providerMenuEmptyStateFactory,
                providerModelMenuItemFactory,
                providerFavoritesSectionAppender,
                assistantRenderModeSelectionResolver,
                assistantRenderModeChangeUiApplyCoordinator,
                generalSettingsUiApplyCoordinator,
                fontSelectionNormalizer,
                fontPreviewApplier,
                fontMenuSelectionSynchronizer,
                fontMenuSelectionApplyCoordinator,
                themeMenuSelectionSynchronizer,
                themeMenuSelectionApplyCoordinator,
                menuPopupVisibleRunner,
                persistedMessageCounter,
                this::resolveConversationRenderMode
        ));

        var providerMenuWiring = dependencies.providerMenuWiring();
        this.providerSettingsApplyCoordinator = providerMenuWiring.providerSettingsApplyCoordinator();
        this.providerModelsResolver = providerMenuWiring.providerModelsResolver();
        this.providerFavoritesResolver = providerMenuWiring.providerFavoritesResolver();
        this.providerAvailabilityResolver = providerMenuWiring.providerAvailabilityResolver();
        this.providerMenuDataResolver = providerMenuWiring.providerMenuDataResolver();
        this.providerMenuAvailabilityRefreshCoordinator =
                providerMenuWiring.providerMenuAvailabilityRefreshCoordinator();
        this.providerMenuAvailabilityRefreshDispatchCoordinator =
                providerMenuWiring.providerMenuAvailabilityRefreshDispatchCoordinator();
        this.providerCatalogSectionAppender = providerMenuWiring.providerCatalogSectionAppender();
        this.providerMenuStructureRebuilder = providerMenuWiring.providerMenuStructureRebuilder();
        this.modelMenuStructureRebuildCoordinator = providerMenuWiring.modelMenuStructureRebuildCoordinator();

        var settingsWiring = dependencies.settingsWiring();
        this.assistantRenderModeSettingsCoordinator = settingsWiring.assistantRenderModeSettingsCoordinator();
        this.assistantRenderModeChangeCoordinator = settingsWiring.assistantRenderModeChangeCoordinator();
        this.assistantRenderModeChangeDispatchCoordinator =
                settingsWiring.assistantRenderModeChangeDispatchCoordinator();
        this.generalSettingsResolver = settingsWiring.generalSettingsResolver();
        this.generalSettingsApplyCoordinator = settingsWiring.generalSettingsApplyCoordinator();
        this.generalSettingsApplyDispatchCoordinator = settingsWiring.generalSettingsApplyDispatchCoordinator();
        this.fontSettingsResolver = settingsWiring.fontSettingsResolver();
        this.fontSettingsPersister = settingsWiring.fontSettingsPersister();
        this.fontMenuApplyCoordinator = settingsWiring.fontMenuApplyCoordinator();
        this.fontMenuSelectionRefreshCoordinator = settingsWiring.fontMenuSelectionRefreshCoordinator();
        this.fontMenuSelectionDispatchCoordinator = settingsWiring.fontMenuSelectionDispatchCoordinator();
        this.fontMenuSelectionFlowCoordinator = settingsWiring.fontMenuSelectionFlowCoordinator();
        this.appFontSizeStepResolver = settingsWiring.appFontSizeStepResolver();
        this.appFontSizeAdjustCoordinator = settingsWiring.appFontSizeAdjustCoordinator();
        this.themeSettingsResolver = settingsWiring.themeSettingsResolver();
        this.themeMenuApplyCoordinator = settingsWiring.themeMenuApplyCoordinator();
        this.themeMenuSelectionRefreshCoordinator = settingsWiring.themeMenuSelectionRefreshCoordinator();
        this.themeMenuSelectionDispatchCoordinator = settingsWiring.themeMenuSelectionDispatchCoordinator();
        this.themeMenuSelectionFlowCoordinator = settingsWiring.themeMenuSelectionFlowCoordinator();

        var lifecycleWiring = dependencies.lifecycleWiring();
        this.modelMenuDirtyRefreshCoordinator = lifecycleWiring.modelMenuDirtyRefreshCoordinator();
        this.modelMenuDirtyRefreshTriggerCoordinator = lifecycleWiring.modelMenuDirtyRefreshTriggerCoordinator();
        this.lookAndFeelMenuRefreshCoordinator = lifecycleWiring.lookAndFeelMenuRefreshCoordinator();
        this.windowStateSettingsCoordinator = lifecycleWiring.windowStateSettingsCoordinator();
        this.windowStateRestoreCoordinator = lifecycleWiring.windowStateRestoreCoordinator();

        var conversationWiring = dependencies.conversationWiring();
        this.conversationLoadCoordinator = conversationWiring.conversationLoadCoordinator();
        this.conversationLoadResultPlanner = conversationWiring.conversationLoadResultPlanner();
        this.conversationLoadApplyDispatchCoordinator = conversationWiring.conversationLoadApplyDispatchCoordinator();
        this.conversationPersistenceCoordinator = conversationWiring.conversationPersistenceCoordinator();
        this.assistantMessageCompletionFlowCoordinator = conversationWiring.assistantMessageCompletionFlowCoordinator();
        this.currentConversationSaveCoordinator = conversationWiring.currentConversationSaveCoordinator();

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(600, 400));
        var iconImage = new ImageIcon(getClass().getResource("/icons/icon.png")).getImage();
        setIconImage(iconImage);
        if (Taskbar.isTaskbarSupported()) {
            var taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.setIconImage(iconImage);
            }
        }

        // macOS: transparent title bar with content underneath
        getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
        getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
        getRootPane().putClientProperty("apple.awt.fullscreenable", true);
        setTitle(""); // Hide title text since buttons go in the title bar

        // Restore window state
        restoreWindowState();

        // Build UI
        chatPanel = new ChatPanel(modelCacheService, modelFavoritesService);
        chatPanel.setOnAssistantRenderModeChanged(this::onAssistantRenderModeChanged);
        chatPanel.setOnSelectedModelChanged(this::onSelectedModelChanged);
        chatPanel.setOnModelFavoritesChanged(this::onModelFavoritesChanged);
        chatPanel.setOnModelCatalogChanged(this::onModelCatalogChanged);
        chatPanel.setOnMessageSubmitted(() -> saveCurrentConversation(true));
        chatPanel.getInputBar().addReasoningLevelListener(this::persistReasoningLevel);
        chatPanel.setConversationIdSupplier(conversationState::currentConversationId);
        chatPanel.setOnAssistantMessageCompleted(this::onAssistantMessageCompleted);
        chatPanel.setActiveConversationId(conversationState.currentConversationId());
        applyProviderSettings();
        applyGeneralSettings();
        UIManager.addPropertyChangeListener(lookAndFeelListener);

        // Title bar — embedded in macOS title bar area
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setOpaque(false);
        titleBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        // Left: sidebar toggle + new chat (after traffic lights)
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        leftButtons.setOpaque(false);
        leftButtons.setBorder(BorderFactory.createEmptyBorder(0, 78, 0, 0));

        sidebarToggleState.setSidebarToggleFilledIcon(
                TitleBarUiSupport.loadIcon(MainFrame.class, "/icons/titlebar/panel-left-filled.svg")
        );
        sidebarToggleState.setSidebarToggleOutlineIcon(
                TitleBarUiSupport.loadIcon(MainFrame.class, "/icons/titlebar/panel-left.svg")
        );
        sidebarToggleState.setSidebarToggleButton(
                TitleBarUiSupport.createButton(sidebarToggleState.sidebarToggleFilledIcon(), "Toggle Sidebar")
        );
        sidebarToggleState.sidebarToggleButton().addActionListener(e -> toggleSidebar());
        leftButtons.add(sidebarToggleState.sidebarToggleButton());

        JButton searchBtn = TitleBarUiSupport.createButton(
                TitleBarUiSupport.loadIcon(MainFrame.class, "/icons/titlebar/search.svg"),
                "Search Chats"
        );
        searchBtn.addActionListener(e -> openChatSearch(searchBtn));
        leftButtons.add(searchBtn);

        JButton newChatBtn = TitleBarUiSupport.createButton(
                TitleBarUiSupport.loadIcon(MainFrame.class, "/icons/titlebar/square-pen.svg"),
                "New Chat"
        );
        newChatBtn.addActionListener(e -> newChat());
        leftButtons.add(newChatBtn);

        titleBar.add(leftButtons, BorderLayout.WEST);

        // Center: model selector
        JPanel centerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
        centerPanel.setOpaque(false);
        centerPanel.add(chatPanel.getModelSelectorButton());
        titleBar.add(centerPanel, BorderLayout.CENTER);

        // Right: empty panel to balance left for centering
        JPanel rightPanel = new JPanel();
        rightPanel.setOpaque(false);
        rightPanel.setPreferredSize(leftButtons.getPreferredSize());
        titleBar.add(rightPanel, BorderLayout.EAST);

        add(titleBar, BorderLayout.NORTH);
        sidebarPanel = new SidebarPanel(conversationRepo);
        chatPanel.setOnConversationStreamingChanged(event ->
                sidebarPanel.setConversationStreaming(event.conversationId(), event.streaming()));

        sidebarPanel.setOnConversationSelected(this::loadConversation);
        sidebarPanel.setOnNewChat(this::newChat);

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebarPanel, chatPanel);
        splitPane.setDividerLocation(250);
        splitPane.setDividerSize(2);
        splitPane.setContinuousLayout(true);

        add(splitPane, BorderLayout.CENTER);

        // Save state on close
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestWindowClose();
            }
        });

        // macOS application menu handlers
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            desktop.setPreferencesHandler(e -> openSettings());
            if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
                desktop.setAboutHandler(e -> AboutDialog.show(this));
            }
            desktop.setQuitHandler((e, response) -> {
                response.cancelQuit();
                requestWindowClose();
            });
        }

        // Keyboard shortcuts
        setupKeyboardShortcuts();

        chatPanel.getInputBar().requestInputFocus();
    }

    private void toggleSidebar() {
        SidebarToggleCoordinator.ToggleState toggleState = sidebarToggleCoordinator.toggle(
                sidebarState.sidebarVisible(),
                sidebarState.lastDividerLocation(),
                splitPane.getDividerLocation(),
                splitPane,
                sidebarToggleState.sidebarToggleButton(),
                sidebarToggleState.sidebarToggleFilledIcon(),
                sidebarToggleState.sidebarToggleOutlineIcon()
        );

        sidebarToggleStateApplyCoordinator.apply(
                toggleState,
                sidebarState::setSidebarVisible,
                sidebarState::setLastDividerLocation
        );
    }

    private void newChat() {
        newChatCoordinator.start(
                () -> saveCurrentConversation(false),
                conversationState::clearCurrentConversationId,
                conversationState::clearPendingUnsavedConversationRenderMode,
                () -> chatPanel.setActiveConversationId(null),
                sidebarPanel::clearSelection,
                chatPanel::clearChatView,
                assistantRenderModeState.defaultAssistantRenderMode(),
                chatPanel::setAssistantRenderMode,
                () -> chatPanel.getInputBar().requestInputFocus()
        );
    }

    private void loadConversation(UUID id) {
        conversationLoadStartCoordinator.start(
                id,
                () -> saveCurrentConversation(false),
                conversationState::setCurrentConversationId,
                conversationState::clearPendingUnsavedConversationRenderMode,
                chatPanel::setActiveConversationId,
                conversationLoadCoordinator::loadAsync,
                this::applyLoadedConversation,
                this::handleConversationLoadFailure
        );
    }

    private void saveCurrentConversation(boolean selectCreatedConversation) {
        currentConversationSaveDispatchCoordinator.save(
                conversationState.currentConversationId(),
                conversationState.pendingUnsavedConversationRenderMode(),
                chatPanel.getHistory(),
                chatPanel.getSelectedModel(),
                chatPanel.getAssistantRenderMode(),
                currentConversationSaveCoordinator::save,
                saveResult -> currentConversationSaveUiApplyCoordinator.apply(
                        saveResult,
                        conversationState::setCurrentConversationId,
                        conversationState::setPendingUnsavedConversationRenderMode,
                        chatPanel::setActiveConversationId,
                        sidebarPanel::refresh,
                        sidebarPanel::selectConversation,
                        selectCreatedConversation
                ),
                error -> warnWithoutStack("Failed to persist current conversation", error)
        );
    }

    private void requestWindowClose() {
        runShutdownFlow(() -> {
            dispose();
            System.exit(0);
        });
    }

    private void runShutdownFlow(Runnable finishAction) {
        try {
            shutdownFlowCoordinator.request(
                    shutdownState::shutdownInProgress,
                    () -> shutdownState.setShutdownInProgress(true),
                    SHUTDOWN_SAVE_TIMEOUT_MILLIS,
                    () -> {
                        try {
                            UIManager.removePropertyChangeListener(lookAndFeelListener);
                            chatPanel.cancelStreaming();
                            saveWindowState();
                        } catch (Exception e) {
                            warnWithoutStack("Failed during pre-shutdown actions", e);
                        }
                    },
                    () -> {
                        try {
                            ShutdownSaveSnapshot saveSnapshot = captureShutdownSaveSnapshot();
                            return () -> currentConversationSaveCoordinator.save(
                                    saveSnapshot.currentConversationId(),
                                    saveSnapshot.pendingUnsavedConversationRenderMode(),
                                    saveSnapshot.history(),
                                    saveSnapshot.selectedModelKey(),
                                    saveSnapshot.currentAssistantRenderMode()
                            );
                        } catch (Exception e) {
                            warnWithoutStack("Failed to capture shutdown save snapshot", e);
                            return () -> {
                            };
                        }
                    },
                    finishAction,
                    () -> log.warn("Timed out persisting current conversation during shutdown"),
                    error -> warnWithoutStack("Failed to persist current conversation during shutdown", error)
            );
        } catch (Exception e) {
            shutdownState.setShutdownInProgress(false);
            warnWithoutStack("Shutdown flow setup failed, exiting without persistence", e);
            finishAction.run();
        }
    }

    private ShutdownSaveSnapshot captureShutdownSaveSnapshot() {
        return new ShutdownSaveSnapshot(
                conversationState.currentConversationId(),
                conversationState.pendingUnsavedConversationRenderMode(),
                List.copyOf(chatPanel.getHistory()),
                chatPanel.getSelectedModel(),
                chatPanel.getAssistantRenderMode()
        );
    }

    private record ShutdownSaveSnapshot(
            UUID currentConversationId,
            AssistantRenderMode pendingUnsavedConversationRenderMode,
            List<Message> history,
            String selectedModelKey,
            AssistantRenderMode currentAssistantRenderMode
    ) {
    }

    private void applyLoadedConversation(
            long requestId,
            UUID conversationId,
            List<MessageRecord> records,
            Optional<ConversationRepo.ConversationRecord> conversation
    ) {
        conversationLoadApplyDispatchCoordinator.applyLoaded(
                requestId,
                conversationState.currentConversationId(),
                conversationId,
                records,
                conversation,
                chatPanel::loadHistory,
                conversationPersistenceCoordinator::markConversationLoaded,
                chatPanel::setAssistantRenderMode,
                chatPanel::setSelectedModel,
                sidebarPanel::selectConversation
        );
    }

    private void handleConversationLoadFailure(long requestId, UUID conversationId, Exception e) {
        conversationLoadFailureCoordinator.handle(
                requestId,
                conversationState.currentConversationId(),
                conversationId,
                e,
                conversationLoadResultPlanner::shouldHandleFailure,
                message -> JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)
        );
    }

    private boolean onAssistantMessageCompleted(ChatPanel.AssistantMessageEvent event) {
        return assistantMessageCompletionEventDispatchCoordinator.handle(
                event,
                conversationState.currentConversationId(),
                (conversationId, message, activeConversationId) -> assistantMessageCompletionFlowCoordinator.handle(
                        conversationId,
                        message,
                        activeConversationId,
                        sidebarPanel::refresh,
                        sidebarPanel::selectConversation
                ),
                (conversationId, error) -> {
                    String message = "Failed to persist assistant message for conversation %s"
                            .formatted(conversationId);
                    warnWithoutStack(message, error);
                }
        );
    }

    private void saveWindowState() {
        windowStateSettingsCoordinator.save(getBounds());
    }

    private void restoreWindowState() {
        windowStateRestoreCoordinator.restore(
                this::setBounds,
                () -> {
                    setSize(1000, 700);
                    setLocationRelativeTo(null);
                }
        );
    }

    private void setupKeyboardShortcuts() {
        MainFrameShortcutBinder.bindDefault(
                getRootPane(),
                new MainFrameShortcutBinder.ShortcutActions(
                        this::newChat,
                        this::openSettings,
                        this::toggleSidebar,
                        () -> openChatSearch(null),
                        () -> chatPanel.showModelPopupCentered()
                )
        );
    }

    private void openChatSearch(Component relativeTo) {
        chatSearchPopupCoordinator.toggle(
                relativeTo,
                () -> ChatSearchPopupCoordinator.PopupHandle.forPopup(
                        new ChatSearchPopup(this, conversationRepo, this::loadConversation)
                )
        );
    }

    private void openSettings() {
        settingsOpenFlowCoordinator.open(
                SwingUtilities.isEventDispatchThread(),
                () -> SwingUtilities.invokeLater(this::openSettings),
                () -> SettingsDialogCoordinator.DialogHandle.forWindow(new SettingsDialog(this, settingsRepo)),
                () -> {
                    applyProviderSettings();
                    applyGeneralSettings();
                }
        );
    }

    private void applyProviderSettings() {
        List<ProviderRegistry.ProviderDef> allProviders = ProviderRegistry.allProviders();

        try {
            providerSettingsApplyCoordinator.apply(
                    allProviders,
                    chatPanel::refreshProviders,
                    this::markModelsMenuDirty
            );
            logProviderAndModelSummary();
        } catch (Exception e) {
            warnWithoutStack("Failed to apply provider settings", e);
            throw e;
        }
    }

    private void logProviderAndModelSummary() {
        try {
            List<ProviderRegistry.ProviderStatus> statuses = ProviderRegistry.providerStatuses().stream()
                    .sorted((left, right) -> left.name().compareToIgnoreCase(right.name()))
                    .toList();
            List<ProviderRegistry.ProviderDef> availableProviders = ProviderRegistry.availableProviders();
            Map<String, Boolean> localAvailabilityByProvider =
                    providerAvailabilityResolver.resolveMenuAvailability(ProviderRegistry.allProviders());
            List<ProviderRegistry.ProviderStatus> effectiveStatuses = statuses.stream()
                    .map(status -> new ProviderRegistry.ProviderStatus(
                            status.name(),
                            status.enabled(),
                            status.credentialReady(),
                            localAvailabilityByProvider.getOrDefault(status.name(), status.available())
                    ))
                    .toList();
            List<String> availableProviderNames = effectiveStatuses.stream()
                    .filter(ProviderRegistry.ProviderStatus::available)
                    .map(ProviderRegistry.ProviderStatus::name)
                    .toList();

            Map<String, List<String>> modelsByProvider = providerModelsResolver.resolve(availableProviders);
            Map<String, Integer> modelCountByProvider = effectiveStatuses.stream()
                    .collect(toMap(
                            ProviderRegistry.ProviderStatus::name,
                            status -> modelsByProvider.getOrDefault(status.name(), emptyList()).size(),
                            (existing, replacement) -> existing,
                            LinkedHashMap::new
                    ));

            long authenticatedCount = effectiveStatuses.stream()
                    .filter(ProviderRegistry.ProviderStatus::credentialReady)
                    .count();
            long availableCount = effectiveStatuses.stream()
                    .filter(ProviderRegistry.ProviderStatus::available)
                    .count();
            int totalModels = modelsByProvider.values().stream()
                    .mapToInt(List::size)
                    .sum();

            log.info(
                    "Provider/model snapshot: resolved={} authenticated={} available={} totalModels={}",
                    statuses.size(),
                    authenticatedCount,
                    availableCount,
                    totalModels
            );
            log.info("\n{}", buildProviderStatusTable(effectiveStatuses, modelCountByProvider));
            log.info("\n{}", buildModelCountTable(modelsByProvider));

            List<String> availableWithoutModels = availableProviderNames.stream()
                    .filter(providerName -> modelsByProvider.getOrDefault(providerName, emptyList()).isEmpty())
                    .toList();
            if (!availableWithoutModels.isEmpty()) {
                log.warn("Available providers without models: {}", joinNames(availableWithoutModels));
            }
        } catch (Exception e) {
            warnWithoutStack("Failed to build provider/model summary", e);
        }
    }

    private static String buildProviderStatusTable(
            List<ProviderRegistry.ProviderStatus> statuses,
            Map<String, Integer> modelCountByProvider
    ) {
        int providerWidth = Math.max(
                "Provider".length(),
                statuses.stream()
                        .map(ProviderRegistry.ProviderStatus::name)
                        .mapToInt(String::length)
                        .max()
                        .orElse("Provider".length())
        );
        String rowPattern = "| %-" + providerWidth + "s | %-7s | %-13s | %-9s | %6s |";
        String separator = "+-" + "-".repeat(providerWidth) + "-+---------+---------------+-----------+--------+";

        String rows = statuses.stream()
                .map(status -> rowPattern.formatted(
                        status.name(),
                        yesNo(status.enabled()),
                        yesNo(status.credentialReady()),
                        yesNo(status.available()),
                        modelCountByProvider.getOrDefault(status.name(), 0)
                ))
                .reduce((left, right) -> "%s\n%s".formatted(left, right))
                .orElse("");

        String header = rowPattern.formatted("Provider", "Enabled", "Authenticated", "Available", "Models");
        return rows.isBlank()
                ? "%s\n%s\n%s".formatted(separator, header, separator)
                : "%s\n%s\n%s\n%s\n%s".formatted(separator, header, separator, rows, separator);
    }

    private static String buildModelCountTable(Map<String, List<String>> modelsByProvider) {
        List<Map.Entry<String, List<String>>> entries = modelsByProvider.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .toList();

        int providerWidth = Math.max(
                "Provider".length(),
                entries.stream()
                        .map(Map.Entry::getKey)
                        .mapToInt(String::length)
                        .max()
                        .orElse("Provider".length())
        );
        int modelWidth = Math.max(
                "Models".length(),
                entries.stream()
                        .map(entry -> String.valueOf(entry.getValue().size()))
                        .mapToInt(String::length)
                        .max()
                        .orElse("Models".length())
        );

        String rowPattern = "| %-" + providerWidth + "s | %" + modelWidth + "s |";
        String separator = "+-" + "-".repeat(providerWidth) + "-+-" + "-".repeat(modelWidth) + "-+";
        String header = rowPattern.formatted("Provider", "Models");

        String rows = entries.stream()
                .map(entry -> rowPattern.formatted(entry.getKey(), entry.getValue().size()))
                .reduce((left, right) -> "%s\n%s".formatted(left, right))
                .orElse(rowPattern.formatted("none", 0));

        return "%s\n%s\n%s\n%s\n%s".formatted(separator, header, separator, rows, separator);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private void warnWithoutStack(String message, Exception e) {
        log.warn("{}: {}", message, ExceptionUtils.getMessage(e));
    }

    private static String joinNames(List<String> names) {
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private void applyGeneralSettings() {
        AssistantRenderMode defaultAssistantRenderMode = generalSettingsApplyDispatchCoordinator.apply(
                SystemInfo.isMacOS,
                conversationState.currentConversationId(),
                conversationState.currentConversationId() != null
                        ? resolveConversationRenderMode(conversationState.currentConversationId())
                        : null,
                conversationState.pendingUnsavedConversationRenderMode(),
                sendOnEnter -> chatPanel.getInputBar().setSendOnEnter(sendOnEnter),
                chatPanel::setAutoScrollEnabled,
                chatPanel::setAssistantRenderMode,
                this::applyMenuBarSetting
        );

        generalSettingsDefaultModeApplyCoordinator.apply(
                defaultAssistantRenderMode,
                assistantRenderModeState::setDefaultAssistantRenderMode
        );

        applyReasoningLevelSetting();
    }

    private void applyReasoningLevelSetting() {
        try {
            String settingValue = settingsRepo.get(
                    SettingsKeys.CHAT_REASONING_LEVEL,
                    ReasoningLevel.OFF.toSettingValue()
            );
            ReasoningLevel reasoningLevel = ReasoningLevel.fromSettingValue(settingValue, ReasoningLevel.OFF);
            chatPanel.getInputBar().setReasoningLevel(reasoningLevel);
        } catch (Exception e) {
            log.debug("Failed to resolve reasoning level setting", e);
        }
    }

    private void persistReasoningLevel(ReasoningLevel reasoningLevel) {
        try {
            settingsRepo.put(SettingsKeys.CHAT_REASONING_LEVEL, reasoningLevel.toSettingValue());
        } catch (Exception e) {
            log.debug("Failed to persist reasoning level setting", e);
        }
    }

    private void applyMenuBarSetting(boolean enabled) {
        menuBarSettingDispatchCoordinator.apply(
                enabled,
                this::setJMenuBar,
                this::ensureModelsMenuBar,
                topMenusState::modelMenuBar,
                this::ensureThemesMenuReady,
                this::ensureModelsMenuReady,
                this::ensureFontMenuReady,
                this::syncTogglePreviewMenuSelection,
                () -> {
                    revalidate();
                    repaint();
                }
        );
    }

    private void ensureModelsMenuBar() {
        mainMenuBarEnsureApplyFlowCoordinator.ensureAndApply(
                topMenusState.modelMenuBar(),
                () -> mainMenuBarCreateDispatchCoordinator.create(
                        getTitle(),
                        this::newChat,
                        chatPanel::hideModelPopup,
                        this::syncTogglePreviewMenuSelection,
                        this::toggleSidebar,
                        () -> chatPanel.showModelPopupCentered(),
                        () -> openChatSearch(null),
                        selected -> assistantRenderModeToggleCoordinator.apply(
                                selected,
                                previewMenuState.syncingPreviewMenuSelection(),
                                chatPanel::setAssistantRenderMode
                        ),
                        this::ensureThemesMenuReady,
                        this::ensureFontMenuReady,
                        this::ensureModelsMenuReady,
                        () -> AboutDialog.show(this)
                ),
                this::syncTogglePreviewMenuSelection,
                new MainMenuBarEnsureStateResolver.CurrentState(
                        topMenusState.fileMenu(),
                        topMenusState.viewMenu(),
                        boundMenusState.modelsMenu(),
                        boundMenusState.fontMenu(),
                        boundMenusState.themesMenu(),
                        previewMenuState.togglePreviewMenuItem(),
                        modelMenuState.modelsMenuDirty(),
                        themeMenuState.themesMenuBuilt(),
                        fontMenuState.fontMenuBuilt()
                ),
                topMenusState::setModelMenuBar,
                topMenusState::setFileMenu,
                topMenusState::setViewMenu,
                boundMenusState::setModelsMenu,
                boundMenusState::setFontMenu,
                boundMenusState::setThemesMenu,
                previewMenuState::setTogglePreviewMenuItem,
                modelMenuState::setModelsMenuDirty,
                themeMenuState::setThemesMenuBuilt,
                fontMenuState::setFontMenuBuilt
        );
    }

    private void ensureModelsMenuReady() {
        providerMenuReadyDispatchCoordinator.ensureReady(
                modelMenuState.modelsMenuDirty(),
                this::rebuildModelsMenuStructure,
                this::refreshLocalProviderAvailabilityInMenu,
                this::syncModelsMenuSelection
        );
    }

    private void ensureThemesMenuReady() {
        themeMenuReadyDispatchCoordinator.ensureReady(
                themeMenuState.themesMenuBuilt(),
                this::rebuildThemesMenuStructure,
                this::syncThemeMenuSelection
        );
    }

    private void ensureFontMenuReady() {
        fontMenuReadyDispatchCoordinator.ensureReady(
                fontMenuState.fontMenuBuilt(),
                this::rebuildFontMenuStructure,
                this::syncFontMenuSelection
        );
    }

    private void syncTogglePreviewMenuSelection() {
        assistantRenderModeToggleSelectionSyncCoordinator.sync(
                previewMenuState.togglePreviewMenuItem(),
                chatPanel.getAssistantRenderMode(),
                previewMenuState::setSyncingPreviewMenuSelection
        );
    }

    private void markModelsMenuDirty() {
        modelMenuState.markModelsMenuDirty();
    }

    private void onLookAndFeelChanged() {
        lookAndFeelMenuRefreshCoordinator.refresh(
                ProviderMenuIconRenderer::clearCache,
                this::markModelsMenuDirty,
                boundMenusState.modelsMenu(),
                this::ensureModelsMenuReady,
                boundMenusState.fontMenu(),
                this::ensureFontMenuReady
        );
    }

    private void rebuildModelsMenuStructure() {
        ModelMenuStructureRebuildCoordinator.RebuildState rebuildState = modelMenuStructureRebuildCoordinator.rebuild(
                boundMenusState.modelsMenu(),
                menuItemsState.modelMenuItemsByKey(),
                menuItemsState.providerHeaderItemsByName(),
                ProviderRegistry.availableProviders(),
                chatPanel::setSelectedModel,
                modelMenuState.modelsMenuDirty(),
                modelMenuState.lastMenuSelectedModelKey()
        );

        modelMenuStructureRebuildApplyCoordinator.apply(
                rebuildState,
                modelMenuState::setModelsMenuDirty,
                modelMenuState::setLastMenuSelectedModelKey
        );
    }

    private void syncModelsMenuSelection() {
        String syncedSelection = modelMenuSelectionDispatchCoordinator.sync(
                menuItemsState.modelMenuItemsByKey(),
                chatPanel.getSelectedModel(),
                modelMenuState.lastMenuSelectedModelKey(),
                modelMenuState.modelsMenuDirty()
        );

        modelMenuSelectionApplyCoordinator.apply(
                syncedSelection,
                modelMenuState::setLastMenuSelectedModelKey
        );
    }

    private void onSelectedModelChanged(String modelKey) {
        modelMenuSelectionChangeCoordinator.onSelectedModelChanged(
                boundMenusState.modelsMenu(),
                modelMenuState.modelsMenuDirty(),
                this::syncModelsMenuSelection
        );
    }

    private void onModelFavoritesChanged() {
        modelMenuDirtyRefreshTriggerCoordinator.trigger(
                boundMenusState.modelsMenu(),
                this::markModelsMenuDirty,
                this::ensureModelsMenuReady
        );
    }

    private void onModelCatalogChanged() {
        modelMenuDirtyRefreshTriggerCoordinator.trigger(
                boundMenusState.modelsMenu(),
                this::markModelsMenuDirty,
                this::ensureModelsMenuReady
        );
    }

    private void refreshLocalProviderAvailabilityInMenu() {
        providerMenuAvailabilityRefreshDispatchCoordinator.refresh(
                menuItemsState.modelMenuItemsByKey(),
                menuItemsState.providerHeaderItemsByName(),
                providerMenuIconResolver
        );
    }

    private void rebuildThemesMenuStructure() {
        ThemeMenuStructureRebuildCoordinator.RebuildState rebuildState =
                themeMenuStructureRebuildCoordinator.rebuild(
                        boundMenusState.themesMenu(),
                        menuItemsState.themeMenuItemsByName(),
                        this::applyThemeFromMenu,
                        themeMenuState.themesMenuBuilt(),
                        themeMenuState.lastMenuSelectedTheme()
                );

        themeMenuStructureRebuildApplyCoordinator.apply(
                rebuildState,
                themeMenuState::setThemesMenuBuilt,
                themeMenuState::setLastMenuSelectedTheme
        );
    }

    private void syncThemeMenuSelection() {
        themeMenuSelectionFlowCoordinator.refreshAndApply(
                menuItemsState.themeMenuItemsByName(),
                themeMenuState.lastMenuSelectedTheme(),
                themeMenuState.themesMenuBuilt(),
                ThemeSettingsResolver.DEFAULT_THEME,
                themeMenuState::setLastMenuSelectedTheme
        );
    }

    private void rebuildFontMenuStructure() {
        FontMenuStructureRebuildCoordinator.RebuildState rebuildState =
                fontMenuStructureRebuildCoordinator.rebuild(
                        boundMenusState.fontMenu(),
                        menuItemsState.appFontMenuItemsByFamily(),
                        menuItemsState.appFontSizeMenuItemsBySize(),
                        menuItemsState.codeFontMenuItemsByFamily(),
                        this::restoreAppFontFromMenu,
                        () -> adjustAppFontSizeFromMenu(true),
                        () -> adjustAppFontSizeFromMenu(false),
                        this::applyAppFontFamilyFromMenu,
                        this::applyCodeFontFromMenu,
                        this::applyAppFontSizeFromMenu,
                        fontMenuState.fontMenuBuilt(),
                        fontMenuState.lastMenuSelectedAppFontFamily(),
                        fontMenuState.lastMenuSelectedAppFontSize(),
                        fontMenuState.lastMenuSelectedCodeFontFamily()
                );

        fontMenuStructureRebuildApplyCoordinator.apply(
                rebuildState,
                fontMenuState::setFontMenuBuilt,
                fontMenuState::setLastMenuSelectedAppFontFamily,
                fontMenuState::setLastMenuSelectedAppFontSize,
                fontMenuState::setLastMenuSelectedCodeFontFamily
        );
    }

    private void syncFontMenuSelection() {
        fontMenuSelectionFlowCoordinator.refreshAndApply(
                menuItemsState.appFontMenuItemsByFamily(),
                menuItemsState.appFontSizeMenuItemsBySize(),
                menuItemsState.codeFontMenuItemsByFamily(),
                fontMenuState.lastMenuSelectedAppFontFamily(),
                fontMenuState.lastMenuSelectedAppFontSize(),
                fontMenuState.lastMenuSelectedCodeFontFamily(),
                fontMenuState.fontMenuBuilt(),
                fontMenuState::setLastMenuSelectedAppFontFamily,
                fontMenuState::setLastMenuSelectedAppFontSize,
                fontMenuState::setLastMenuSelectedCodeFontFamily
        );
    }

    private void applyAppFontFamilyFromMenu(String fontFamily) {
        applyAppFontSelectionFromMenu(fontFamily, fontSettingsResolver.resolveAppFontSizeSetting());
    }

    private void applyAppFontSizeFromMenu(int appFontSize) {
        String appFontFamily = fontSettingsResolver.resolveAppFontFamilySetting();
        applyAppFontSelectionFromMenu(appFontFamily, appFontSize);
    }

    private void applyAppFontSelectionFromMenu(String appFontFamily, int appFontSize) {
        fontMenuApplyDispatchCoordinator.apply(
                () -> fontMenuApplyCoordinator.applyAppFontSelection(
                        appFontFamily,
                        appFontSize,
                        menuItemsState.appFontMenuItemsByFamily().keySet(),
                        this::syncFontMenuSelection
                ),
                "Failed to apply UI font: ",
                message -> JOptionPane.showMessageDialog(this, message, "Font Error", JOptionPane.ERROR_MESSAGE)
        );
    }

    private void applyCodeFontFromMenu(String fontFamily) {
        fontMenuApplyDispatchCoordinator.apply(
                () -> fontMenuApplyCoordinator.applyCodeFontSelection(
                        fontFamily,
                        menuItemsState.codeFontMenuItemsByFamily().keySet(),
                        this::syncFontMenuSelection
                ),
                "Failed to apply code font: ",
                message -> JOptionPane.showMessageDialog(this, message, "Font Error", JOptionPane.ERROR_MESSAGE)
        );
    }

    private void restoreAppFontFromMenu() {
        applyAppFontSelectionFromMenu(
                AppearancePanel.DEFAULT_APP_FONT,
                AppearancePanel.defaultAppFontSize());
    }

    private void adjustAppFontSizeFromMenu(boolean increase) {
        appFontSizeAdjustCoordinator.adjust(
                increase,
                AppearancePanel::appFontSizeOptions,
                fontSettingsResolver::resolveAppFontSizeSetting,
                this::applyAppFontSizeFromMenu
        );
    }

    private void applyThemeFromMenu(String themeName, String className) {
        themeMenuApplyDispatchCoordinator.apply(
                themeName,
                className,
                themeMenuApplyCoordinator::apply,
                this::markModelsMenuDirty,
                this::syncThemeMenuSelection,
                this::syncFontMenuSelection,
                message -> JOptionPane.showMessageDialog(this, message, "Theme Error", JOptionPane.ERROR_MESSAGE)
        );
    }

    private void onAssistantRenderModeChanged(AssistantRenderMode mode) {
        assistantRenderModeChangeDispatchCoordinator.apply(
                conversationState.currentConversationId(),
                mode,
                conversationState.pendingUnsavedConversationRenderMode(),
                this::syncTogglePreviewMenuSelection,
                conversationState::setPendingUnsavedConversationRenderMode
        );
    }

    private AssistantRenderMode resolveConversationRenderMode(UUID conversationId) {
        return assistantRenderModeSettingsCoordinator.resolveConversationMode(
                conversationId,
                assistantRenderModeState.defaultAssistantRenderMode()
        );
    }

}
