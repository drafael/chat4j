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
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import com.github.drafael.chat4j.settings.AgentModeSettingsCoordinator;
import com.github.drafael.chat4j.settings.AppFontSizeAdjustCoordinator;
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
import com.github.drafael.chat4j.settings.WindowStateRestoreCoordinator;
import com.github.drafael.chat4j.settings.WindowStateSettingsCoordinator;
import com.github.drafael.chat4j.prompts.CommandCenterAction;
import com.github.drafael.chat4j.prompts.PromptCatalogRepo;
import com.github.drafael.chat4j.prompts.PromptCommandCenter;
import com.github.drafael.chat4j.prompts.PromptTemplate;
import com.github.drafael.chat4j.prompts.PromptTemplateRenderer;
import com.github.drafael.chat4j.prompts.PromptVariable;
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
import com.github.drafael.chat4j.storage.SettingsRepo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final MainFrameTitleBarFactory titleBarFactory = new MainFrameTitleBarFactory();
    private final ClearChatConfirmationDialog clearChatConfirmationDialog = new ClearChatConfirmationDialog();
    private final MainFrameShutdownSaveActionFactory shutdownSaveActionFactory = new MainFrameShutdownSaveActionFactory();
    private final JSplitPane splitPane;
    private final ConversationRepo conversationRepo;
    private final SettingsRepo settingsRepo;
    private final PromptCatalogRepo promptCatalogRepo;
    private final PromptTemplateRenderer promptTemplateRenderer = new PromptTemplateRenderer();
    private final PromptVariablesDialog promptVariablesDialog = new PromptVariablesDialog();
    private PromptCommandCenter promptCommandCenter;
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
    private final MainFrameConversationRuntimeSettingsCoordinator conversationRuntimeSettingsCoordinator;
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
    private final AppFontSizeAdjustCoordinator appFontSizeAdjustCoordinator;
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
    private final MainFrameThemeMenuCoordinator themeMenuCoordinator;
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
    private final MainFrameFontMenuCoordinator fontMenuCoordinator;
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
    private final ConversationTitleDeriver conversationTitleDeriver = new ConversationTitleDeriver();
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
    private final Set<UUID> clearedConversationIds = new HashSet<>();
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
    private final MainFrameModelMenuCoordinator modelMenuCoordinator;
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
        this.promptCatalogRepo = new PromptCatalogRepo(settingsRepo);
        this.conversationRuntimeSettingsCoordinator = new MainFrameConversationRuntimeSettingsCoordinator(
                conversationRepo,
                settingsRepo,
                new AgentModeSettingsCoordinator(settingsRepo)
        );
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
        this.appFontSizeAdjustCoordinator = settingsWiring.appFontSizeAdjustCoordinator();
        this.fontMenuCoordinator = new MainFrameFontMenuCoordinator(
                fontMenuReadyDispatchCoordinator,
                fontMenuStructureRebuildCoordinator,
                fontMenuStructureRebuildApplyCoordinator,
                fontMenuSelectionFlowCoordinator,
                fontMenuApplyDispatchCoordinator,
                fontMenuApplyCoordinator,
                fontSettingsResolver,
                appFontSizeAdjustCoordinator
        );
        this.themeMenuApplyCoordinator = settingsWiring.themeMenuApplyCoordinator();
        this.themeMenuSelectionRefreshCoordinator = settingsWiring.themeMenuSelectionRefreshCoordinator();
        this.themeMenuSelectionDispatchCoordinator = settingsWiring.themeMenuSelectionDispatchCoordinator();
        this.themeMenuSelectionFlowCoordinator = settingsWiring.themeMenuSelectionFlowCoordinator();
        this.themeMenuCoordinator = new MainFrameThemeMenuCoordinator(
                themeMenuReadyDispatchCoordinator,
                themeMenuStructureRebuildCoordinator,
                themeMenuStructureRebuildApplyCoordinator,
                themeMenuSelectionFlowCoordinator,
                themeMenuApplyDispatchCoordinator,
                themeMenuApplyCoordinator
        );

        var lifecycleWiring = dependencies.lifecycleWiring();
        this.modelMenuDirtyRefreshCoordinator = lifecycleWiring.modelMenuDirtyRefreshCoordinator();
        this.modelMenuDirtyRefreshTriggerCoordinator = lifecycleWiring.modelMenuDirtyRefreshTriggerCoordinator();
        this.modelMenuCoordinator = new MainFrameModelMenuCoordinator(
                providerMenuReadyDispatchCoordinator,
                modelMenuStructureRebuildCoordinator,
                modelMenuStructureRebuildApplyCoordinator,
                modelMenuSelectionDispatchCoordinator,
                modelMenuSelectionApplyCoordinator,
                modelMenuSelectionChangeCoordinator,
                modelMenuDirtyRefreshTriggerCoordinator,
                providerMenuAvailabilityRefreshDispatchCoordinator,
                providerMenuIconResolver
        );
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

        configureWindowChrome();
        restoreWindowState();

        chatPanel = createConfiguredChatPanel();
        applyProviderSettings();
        applyGeneralSettings();
        UIManager.addPropertyChangeListener(lookAndFeelListener);

        MainFrameTitleBarFactory.TitleBar titleBar = titleBarFactory.create(
                MainFrame.class,
                chatPanel.getModelSelectorButton(),
                this::toggleSidebar,
                this::openChatSearch,
                this::newChat
        );
        sidebarToggleState.setSidebarToggleFilledIcon(titleBar.sidebarToggleFilledIcon());
        sidebarToggleState.setSidebarToggleOutlineIcon(titleBar.sidebarToggleOutlineIcon());
        sidebarToggleState.setSidebarToggleButton(titleBar.sidebarToggleButton());
        add(titleBar.panel(), BorderLayout.NORTH);
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

        installCloseHandlers();
        installDesktopHandlers();
        setupKeyboardShortcuts();

        chatPanel.getInputBar().requestInputFocus();
    }

    private void configureWindowChrome() {
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

        getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
        getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
        getRootPane().putClientProperty("apple.awt.fullscreenable", true);
        setTitle("");
    }

    private ChatPanel createConfiguredChatPanel() {
        ChatPanel panel = new ChatPanel(modelCacheService, modelFavoritesService);
        panel.setOnAssistantRenderModeChanged(this::onAssistantRenderModeChanged);
        panel.setOnSelectedModelChanged(this::onSelectedModelChanged);
        panel.setOnModelFavoritesChanged(this::onModelFavoritesChanged);
        panel.setOnModelCatalogChanged(this::onModelCatalogChanged);
        panel.setOnMessageSubmitted(() -> saveCurrentConversation(true));
        panel.setOnClearChatRequested(this::confirmClearCurrentChat);
        panel.getInputBar().addCommandCenterListener(e -> openCommandCenter());
        panel.getInputBar().addReasoningLevelListener(this::persistReasoningLevel);
        panel.getInputBar().addWebSearchEnabledListener(this::persistWebSearchEnabled);
        panel.getInputBar().addWebSearchOptionListener(this::persistWebSearchOption);
        panel.getInputBar().addWebBrowseTopNListener(this::persistWebBrowseTopN);
        panel.getInputBar().addAgentModeListener(this::persistAgentModeEnabled);
        panel.getInputBar().addAgentProjectRootListener(this::persistAgentProjectRoot);
        panel.setConversationIdSupplier(conversationState::currentConversationId);
        panel.setOnAssistantMessageCompleted(this::onAssistantMessageCompleted);
        panel.setActiveConversationId(conversationState.currentConversationId());
        return panel;
    }

    private void installCloseHandlers() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestWindowClose();
            }
        });
    }

    private void installDesktopHandlers() {
        if (!Desktop.isDesktopSupported()) {
            return;
        }

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
                this::resetConversationRuntimeState,
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

    private void confirmClearCurrentChat() {
        if (!clearChatConfirmationDialog.confirm(this)) {
            return;
        }

        clearCurrentChatMessages();
    }

    private void clearCurrentChatMessages() {
        UUID currentConversationId = conversationState.currentConversationId();
        try {
            if (currentConversationId != null) {
                conversationRepo.deleteMessages(currentConversationId);
                conversationPersistenceCoordinator.markConversationLoaded(currentConversationId, 0);
                clearedConversationIds.add(currentConversationId);
            }

            chatPanel.clearChat();
            sidebarPanel.refresh();
            if (currentConversationId != null) {
                sidebarPanel.selectConversation(currentConversationId);
            }
            chatPanel.getInputBar().requestInputFocus();
        } catch (Exception e) {
            warnWithoutStack("Failed to clear current conversation messages", e);
            JOptionPane.showMessageDialog(
                    this,
                    "Failed to clear chat: %s".formatted(e.getMessage()),
                    "Clear Chat",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void saveCurrentConversation(boolean selectCreatedConversation) {
        UUID currentConversationId = conversationState.currentConversationId();
        List<Message> history = chatPanel.getHistory();
        boolean retitleClearedConversation = shouldRetitleClearedConversation(currentConversationId, history);

        currentConversationSaveDispatchCoordinator.save(
                currentConversationId,
                conversationState.pendingUnsavedConversationRenderMode(),
                history,
                chatPanel.getSelectedModel(),
                chatPanel.getAssistantRenderMode(),
                chatPanel.getInputBar().getReasoningLevel(),
                chatPanel.getInputBar().isAgentModeRequested(),
                chatPanel.getInputBar().getAgentProjectRoot(),
                (conversationId, pendingMode, messages, selectedModel, renderMode, reasoningLevel, agentModeEnabled, agentProjectRoot) ->
                        saveConversationAndRetitleIfNeeded(
                                conversationId,
                                pendingMode,
                                messages,
                                selectedModel,
                                renderMode,
                                reasoningLevel,
                                agentModeEnabled,
                                agentProjectRoot,
                                retitleClearedConversation
                        ),
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

    private CurrentConversationSaveCoordinator.SaveResult saveConversationAndRetitleIfNeeded(
            UUID currentConversationId,
            AssistantRenderMode pendingUnsavedConversationRenderMode,
            List<Message> history,
            String selectedModelKey,
            AssistantRenderMode currentAssistantRenderMode,
            ReasoningLevel reasoningLevel,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            boolean retitleClearedConversation
    ) throws Exception {
        CurrentConversationSaveCoordinator.SaveResult saveResult = currentConversationSaveCoordinator.save(
                currentConversationId,
                pendingUnsavedConversationRenderMode,
                history,
                selectedModelKey,
                currentAssistantRenderMode,
                reasoningLevel,
                agentModeEnabled,
                agentProjectRoot
        );

        if (saveResult.saved() && retitleClearedConversation) {
            conversationRepo.updateTitle(saveResult.conversationId(), conversationTitleDeriver.derive(history.getFirst()));
            clearedConversationIds.remove(saveResult.conversationId());
        }

        return saveResult;
    }

    private boolean shouldRetitleClearedConversation(UUID conversationId, List<Message> history) {
        return conversationId != null && clearedConversationIds.contains(conversationId) && !history.isEmpty();
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
                    this::createShutdownSaveAction,
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

    private ShutdownSaveDispatchCoordinator.SaveAction createShutdownSaveAction() {
        return shutdownSaveActionFactory.create(new MainFrameShutdownSaveActionFactory.ShutdownSaveRequest(
                conversationState::currentConversationId,
                conversationState::pendingUnsavedConversationRenderMode,
                chatPanel::getHistory,
                chatPanel::getSelectedModel,
                chatPanel::getAssistantRenderMode,
                chatPanel.getInputBar()::getReasoningLevel,
                chatPanel.getInputBar()::isAgentModeRequested,
                chatPanel.getInputBar()::getAgentProjectRoot,
                currentConversationSaveCoordinator,
                error -> warnWithoutStack("Failed to capture shutdown save snapshot", error)
        ));
    }

    private void applyLoadedConversation(
            long requestId,
            UUID conversationId,
            List<MessageRecord> records,
            Optional<ConversationRepo.ConversationRecord> conversation
    ) {
        boolean applied = conversationLoadApplyDispatchCoordinator.applyLoaded(
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

        if (applied) {
            conversationRuntimeSettingsCoordinator.applyLoadedConversationSettings(
                    conversation.orElse(null),
                    runtimeSettingsTarget()
            );
        }
    }

    private void resetConversationRuntimeState() {
        conversationRuntimeSettingsCoordinator.resetRuntimeState(runtimeSettingsTarget());
    }

    private MainFrameConversationRuntimeSettingsCoordinator.RuntimeSettingsTarget runtimeSettingsTarget() {
        return new MainFrameConversationRuntimeSettingsCoordinator.RuntimeSettingsTarget(
                chatPanel.getInputBar()::setReasoningLevel,
                chatPanel.getInputBar()::setWebSearchEnabled,
                chatPanel.getInputBar()::setWebSearchOptionId,
                chatPanel.getInputBar()::setAgentProjectRoot,
                chatPanel.getInputBar()::setAgentModeEnabled
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
                        this::openCommandCenter,
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

    private void openCommandCenter() {
        if (!chatPanel.getInputBar().isEnabled()) {
            return;
        }
        if (promptCommandCenter == null) {
            promptCommandCenter = new PromptCommandCenter(
                    this,
                    promptCatalogRepo,
                    this::commandCenterActions,
                    this::invokePromptTemplate
            );
        }
        promptCommandCenter.openNear(chatPanel.getInputBar());
    }

    private List<CommandCenterAction> commandCenterActions() {
        return List.of(
                new CommandCenterAction("New chat", this::newChat, this::canUseCommandCenterAction),
                new CommandCenterAction("Search chats", () -> openChatSearch(null), this::canUseCommandCenterAction),
                new CommandCenterAction(
                        "Copy recent response",
                        chatPanel::copyRecentResponseToClipboard,
                        () -> canUseCommandCenterAction() && chatPanel.canCopyRecentResponse()
                ),
                new CommandCenterAction(
                        "Regenerate",
                        chatPanel::regenerateRecentResponse,
                        () -> canUseCommandCenterAction() && chatPanel.canRegenerateRecentResponse()
                ),
                new CommandCenterAction(
                        "Clear chat",
                        chatPanel::requestClearChat,
                        () -> canUseCommandCenterAction() && chatPanel.canClearChat()
                ),
                new CommandCenterAction("Toggle sidebar", this::toggleSidebar, this::canUseCommandCenterAction),
                new CommandCenterAction(
                        "Toggle agent mode",
                        chatPanel.getInputBar()::toggleAgentMode,
                        () -> canUseCommandCenterAction() && chatPanel.getInputBar().isAgentModeAvailable()
                ),
                new CommandCenterAction(
                        "Switch selected model",
                        () -> chatPanel.showModelPopupCentered(),
                        this::canUseCommandCenterAction
                ),
                new CommandCenterAction("Open settings", this::openSettings, this::canUseCommandCenterAction)
        );
    }

    private boolean canUseCommandCenterAction() {
        return chatPanel.getInputBar().isEnabled();
    }

    private void invokePromptTemplate(PromptTemplate promptTemplate) {
        Map<String, String> values = new LinkedHashMap<>();
        String currentInput = chatPanel.getInputBar().getRawText();
        promptTemplate.variables().forEach(variable -> {
            if (StringUtils.isNotBlank(variable.defaultValue())) {
                values.put(variable.name(), variable.defaultValue());
            } else if ("text".equals(variable.name()) && StringUtils.isNotBlank(currentInput)) {
                values.put(variable.name(), currentInput);
            }
        });

        List<PromptVariable> missingVariables = promptTemplate.variables().stream()
                .filter(variable -> StringUtils.isBlank(values.get(variable.name())))
                .toList();
        if (!missingVariables.isEmpty()) {
            Optional<Map<String, String>> collectedValues = promptVariablesDialog.show(this, promptTemplate, missingVariables);
            if (collectedValues.isEmpty()) {
                chatPanel.getInputBar().requestInputFocus();
                return;
            }
            values.putAll(collectedValues.orElseThrow());
        }

        try {
            chatPanel.getInputBar().setText(promptTemplateRenderer.render(promptTemplate, values));
            chatPanel.getInputBar().requestInputFocus();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Prompt Error", JOptionPane.ERROR_MESSAGE);
        }
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
            Thread.startVirtualThread(this::logProviderAndModelSummary);
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

        applyAgentModeSettings();
        applyWebSearchSettings();
    }

    private void persistReasoningLevel(ReasoningLevel reasoningLevel) {
        persistCurrentConversationReasoningLevel(reasoningLevel);
    }

    private void applyAgentModeSettings() {
        conversationRuntimeSettingsCoordinator.applyAgentModeSettings(chatPanel::setAgentSystemPromptAppend);
    }

    private void applyWebSearchSettings() {
        conversationRuntimeSettingsCoordinator.applyWebSearchSettings(chatPanel.getInputBar()::setWebBrowseTopN);
    }

    private void persistCurrentConversationReasoningLevel(ReasoningLevel reasoningLevel) {
        conversationRuntimeSettingsCoordinator.persistReasoningLevel(
                conversationState.currentConversationId(),
                reasoningLevel
        );
    }

    private void persistWebSearchEnabled(boolean enabled) {
        persistCurrentConversationWebSearchSettings();
    }

    private void persistWebSearchOption(String optionId) {
        persistCurrentConversationWebSearchSettings();
    }

    private void persistWebBrowseTopN(int topN) {
        conversationRuntimeSettingsCoordinator.persistWebBrowseTopN(topN);
    }

    private void persistCurrentConversationWebSearchSettings() {
        conversationRuntimeSettingsCoordinator.persistWebSearchSettings(
                conversationState.currentConversationId(),
                chatPanel.getInputBar().isWebSearchEnabled(),
                chatPanel.getInputBar().getWebSearchOptionId()
        );
    }

    private void persistAgentModeEnabled(boolean enabled) {
        persistCurrentConversationAgentSettings();
    }

    private void persistAgentProjectRoot(Path projectRoot) {
        persistCurrentConversationAgentSettings();
    }

    private void persistCurrentConversationAgentSettings() {
        conversationRuntimeSettingsCoordinator.persistAgentSettings(
                conversationState.currentConversationId(),
                chatPanel.getInputBar().isAgentModeRequested(),
                chatPanel.getInputBar().getAgentProjectRoot()
        );
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
        modelMenuCoordinator.ensureReady(modelMenuContext());
    }

    private void ensureThemesMenuReady() {
        themeMenuCoordinator.ensureReady(themeMenuContext());
    }

    private void ensureFontMenuReady() {
        fontMenuCoordinator.ensureReady(fontMenuContext());
    }

    private void syncTogglePreviewMenuSelection() {
        assistantRenderModeToggleSelectionSyncCoordinator.sync(
                previewMenuState.togglePreviewMenuItem(),
                chatPanel.getAssistantRenderMode(),
                previewMenuState::setSyncingPreviewMenuSelection
        );
    }

    private void markModelsMenuDirty() {
        modelMenuCoordinator.markDirty(modelMenuContext());
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

    private void onSelectedModelChanged(String modelKey) {
        modelMenuCoordinator.onSelectedModelChanged(modelMenuContext());
    }

    private void onModelFavoritesChanged() {
        modelMenuCoordinator.onModelFavoritesChanged(modelMenuContext());
    }

    private void onModelCatalogChanged() {
        modelMenuCoordinator.onModelCatalogChanged(modelMenuContext());
    }

    private MainFrameModelMenuCoordinator.ModelMenuContext modelMenuContext() {
        return new MainFrameModelMenuCoordinator.ModelMenuContext(
                boundMenusState,
                menuItemsState,
                modelMenuState,
                chatPanel::getSelectedModel,
                chatPanel::setSelectedModel
        );
    }

    private void rebuildThemesMenuStructure() {
        themeMenuCoordinator.rebuildStructure(themeMenuContext());
    }

    private void syncThemeMenuSelection() {
        themeMenuCoordinator.syncSelection(themeMenuContext());
    }

    private void rebuildFontMenuStructure() {
        fontMenuCoordinator.rebuildStructure(fontMenuContext());
    }

    private void syncFontMenuSelection() {
        fontMenuCoordinator.syncSelection(fontMenuContext());
    }

    private void applyAppFontFamilyFromMenu(String fontFamily) {
        fontMenuCoordinator.applyAppFontFamily(fontMenuContext(), fontFamily);
    }

    private void applyAppFontSizeFromMenu(int appFontSize) {
        fontMenuCoordinator.applyAppFontSize(fontMenuContext(), appFontSize);
    }

    private void applyCodeFontFromMenu(String fontFamily) {
        fontMenuCoordinator.applyCodeFont(fontMenuContext(), fontFamily);
    }

    private void restoreAppFontFromMenu() {
        fontMenuCoordinator.restoreAppFont(fontMenuContext());
    }

    private void adjustAppFontSizeFromMenu(boolean increase) {
        fontMenuCoordinator.adjustAppFontSize(fontMenuContext(), increase);
    }

    private MainFrameFontMenuCoordinator.FontMenuContext fontMenuContext() {
        return new MainFrameFontMenuCoordinator.FontMenuContext(
                boundMenusState,
                menuItemsState,
                fontMenuState,
                message -> JOptionPane.showMessageDialog(this, message, "Font Error", JOptionPane.ERROR_MESSAGE)
        );
    }

    private void applyThemeFromMenu(String themeName, String className) {
        themeMenuCoordinator.applyTheme(themeName, className, themeMenuContext());
    }

    private MainFrameThemeMenuCoordinator.ThemeMenuContext themeMenuContext() {
        return new MainFrameThemeMenuCoordinator.ThemeMenuContext(
                boundMenusState,
                menuItemsState,
                themeMenuState,
                this::markModelsMenuDirty,
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
