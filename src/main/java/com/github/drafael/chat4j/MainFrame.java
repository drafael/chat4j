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
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshTriggerCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildCoordinator;
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
import com.github.drafael.chat4j.settings.FontMenuSelectionRefreshCoordinator;
import com.github.drafael.chat4j.settings.FontMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.FontMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.settings.FontMenuStructureRebuilder;
import com.github.drafael.chat4j.settings.FontPreviewApplier;
import com.github.drafael.chat4j.settings.FontSelectionNormalizer;
import com.github.drafael.chat4j.settings.FontSettingsPersister;
import com.github.drafael.chat4j.settings.FontSettingsResolver;
import com.github.drafael.chat4j.settings.GeneralSettingsApplyCoordinator;
import com.github.drafael.chat4j.settings.GeneralSettingsApplyDispatchCoordinator;
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
import com.github.drafael.chat4j.settings.ThemeMenuSelectionRefreshCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuSelectionSynchronizer;
import com.github.drafael.chat4j.settings.ThemeMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.settings.ThemeMenuStructureRebuilder;
import com.github.drafael.chat4j.settings.ThemeSettingsResolver;
import com.github.drafael.chat4j.settings.WindowStateRestoreCoordinator;
import com.github.drafael.chat4j.settings.WindowStateSettingsCoordinator;
import com.github.drafael.chat4j.sidebar.SidebarPanel;
import com.github.drafael.chat4j.sidebar.SidebarToggleCoordinator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MainFrame extends JFrame {

    private static final Logger LOG = Logger.getLogger(MainFrame.class.getName());

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
            new ProviderFavoritesSectionAppender(menuSectionHeaderFactory, providerModelMenuItemFactory);
    private final ProviderCatalogSectionAppender providerCatalogSectionAppender;
    private final ProviderMenuStructureRebuilder providerMenuStructureRebuilder;
    private final ModelMenuStructureRebuildCoordinator modelMenuStructureRebuildCoordinator;
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
    private final ThemeMenuSelectionSynchronizer themeMenuSelectionSynchronizer = new ThemeMenuSelectionSynchronizer();
    private final ThemeMenuReadyCoordinator themeMenuReadyCoordinator = new ThemeMenuReadyCoordinator();
    private final ThemeMenuReadyDispatchCoordinator themeMenuReadyDispatchCoordinator =
            new ThemeMenuReadyDispatchCoordinator(themeMenuReadyCoordinator);
    private final ThemeMenuSelectionRefreshCoordinator themeMenuSelectionRefreshCoordinator;
    private final ThemeMenuSelectionDispatchCoordinator themeMenuSelectionDispatchCoordinator;
    private final ThemeMenuSelectionApplyCoordinator themeMenuSelectionApplyCoordinator =
            new ThemeMenuSelectionApplyCoordinator();
    private final FontMenuStructureRebuilder fontMenuStructureRebuilder =
            new FontMenuStructureRebuilder(menuSectionHeaderFactory);
    private final FontMenuStructureRebuildCoordinator fontMenuStructureRebuildCoordinator =
            new FontMenuStructureRebuildCoordinator(fontMenuStructureRebuilder);
    private final FontMenuSelectionSynchronizer fontMenuSelectionSynchronizer = new FontMenuSelectionSynchronizer();
    private final FontMenuReadyCoordinator fontMenuReadyCoordinator = new FontMenuReadyCoordinator();
    private final FontMenuReadyDispatchCoordinator fontMenuReadyDispatchCoordinator =
            new FontMenuReadyDispatchCoordinator(fontMenuReadyCoordinator);
    private final FontMenuSelectionRefreshCoordinator fontMenuSelectionRefreshCoordinator;
    private final FontMenuSelectionDispatchCoordinator fontMenuSelectionDispatchCoordinator;
    private final FontMenuSelectionApplyCoordinator fontMenuSelectionApplyCoordinator =
            new FontMenuSelectionApplyCoordinator();
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
    private final AssistantMessageCompletionCoordinator assistantMessageCompletionCoordinator;
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
    private UUID currentConversationId;
    private boolean sidebarVisible = true;
    private int lastDividerLocation = 250;
    private JButton sidebarToggleBtn;
    private Icon sidebarToggleIconFilled;
    private Icon sidebarToggleIconOutline;
    private final ChatSearchPopupCoordinator chatSearchPopupCoordinator = new ChatSearchPopupCoordinator();
    private AssistantRenderMode assistantMarkdownDefaultMode = AssistantRenderMode.PREVIEW;
    private AssistantRenderMode pendingUnsavedConversationRenderMode;
    private final SettingsDialogCoordinator settingsDialogCoordinator = new SettingsDialogCoordinator();
    private final SettingsOpenDispatchCoordinator settingsOpenDispatchCoordinator = new SettingsOpenDispatchCoordinator();
    private final SettingsOpenFlowCoordinator settingsOpenFlowCoordinator =
            new SettingsOpenFlowCoordinator(settingsOpenDispatchCoordinator, settingsDialogCoordinator);
    private JMenuBar modelMenuBar;
    private JMenu fileMenu;
    private JMenu viewMenu;
    private JMenu modelsMenu;
    private JMenu themesMenu;
    private JMenu fontMenu;
    private final Map<String, JRadioButtonMenuItem> modelMenuItemsByKey = new LinkedHashMap<>();
    private final Map<String, JMenuItem> providerHeaderItemsByName = new LinkedHashMap<>();
    private final ModelMenuSelectionSynchronizer modelMenuSelectionSynchronizer = new ModelMenuSelectionSynchronizer();
    private final ModelMenuSelectionDispatchCoordinator modelMenuSelectionDispatchCoordinator =
            new ModelMenuSelectionDispatchCoordinator(modelMenuSelectionSynchronizer);
    private final ModelMenuSelectionChangeCoordinator modelMenuSelectionChangeCoordinator =
            new ModelMenuSelectionChangeCoordinator();
    private final MenuPopupVisibleRunner menuPopupVisibleRunner = new MenuPopupVisibleRunner();
    private final ModelMenuDirtyRefreshCoordinator modelMenuDirtyRefreshCoordinator;
    private final ModelMenuDirtyRefreshTriggerCoordinator modelMenuDirtyRefreshTriggerCoordinator;
    private final LookAndFeelMenuRefreshCoordinator lookAndFeelMenuRefreshCoordinator;
    private final Map<String, JRadioButtonMenuItem> themeMenuItemsByName = new LinkedHashMap<>();
    private final Map<String, JRadioButtonMenuItem> appFontMenuItemsByFamily = new LinkedHashMap<>();
    private final Map<Integer, JRadioButtonMenuItem> appFontSizeMenuItemsBySize = new LinkedHashMap<>();
    private final Map<String, JRadioButtonMenuItem> codeFontMenuItemsByFamily = new LinkedHashMap<>();
    private final PersistedMessageCounter persistedMessageCounter = new PersistedMessageCounter();
    private boolean modelsMenuDirty = true;
    private boolean themesMenuBuilt;
    private boolean fontMenuBuilt;
    private String lastMenuSelectedModelKey;
    private String lastMenuSelectedTheme;
    private String lastMenuSelectedAppFontFamily;
    private Integer lastMenuSelectedAppFontSize;
    private String lastMenuSelectedCodeFontFamily;
    private JCheckBoxMenuItem togglePreviewMenuItem;
    private boolean syncingPreviewMenuSelection;
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
        this.providerSettingsApplyCoordinator =
                new ProviderSettingsApplyCoordinator(new ProviderRuntimeSettingsResolver(settingsRepo));
        this.providerModelsResolver = new ProviderModelsResolver(modelCacheService);
        this.providerFavoritesResolver = new ProviderFavoritesResolver(modelFavoritesService);
        this.providerAvailabilityResolver = new ProviderAvailabilityResolver(settingsRepo);
        this.providerMenuDataResolver = new ProviderMenuDataResolver(
                providerModelsResolver,
                providerSelectableResolver,
                providerFavoritesResolver,
                providerAvailabilityResolver
        );
        this.providerMenuAvailabilityRefreshCoordinator = new ProviderMenuAvailabilityRefreshCoordinator(
                providerAvailabilityResolver::resolveMenuAvailability,
                providerMenuAvailabilityApplier
        );
        this.providerMenuAvailabilityRefreshDispatchCoordinator =
                new ProviderMenuAvailabilityRefreshDispatchCoordinator(providerMenuAvailabilityRefreshCoordinator);
        this.providerCatalogSectionAppender = new ProviderCatalogSectionAppender(
                providerAvailabilityLabelFormatter,
                providerHeaderMenuItemFactory,
                providerFavoritesResolver,
                providerMenuEmptyStateFactory,
                providerModelMenuItemFactory
        );
        this.providerMenuStructureRebuilder = new ProviderMenuStructureRebuilder(
                providerMenuDataResolver,
                providerFavoritesSectionAppender,
                providerCatalogSectionAppender,
                providerMenuEmptyStateFactory
        );
        this.modelMenuStructureRebuildCoordinator =
                new ModelMenuStructureRebuildCoordinator(providerMenuStructureRebuilder);
        this.assistantRenderModeSettingsCoordinator = new AssistantRenderModeSettingsCoordinator(settingsRepo);
        this.assistantRenderModeChangeCoordinator = new AssistantRenderModeChangeCoordinator(
                new AssistantRenderModeChangePlanner(),
                assistantRenderModeSettingsCoordinator
        );
        this.assistantRenderModeChangeDispatchCoordinator = new AssistantRenderModeChangeDispatchCoordinator(
                assistantRenderModeChangeCoordinator,
                assistantRenderModeChangeUiApplyCoordinator
        );
        this.generalSettingsResolver = new GeneralSettingsResolver(settingsRepo, assistantRenderModeSettingsCoordinator);
        this.generalSettingsApplyCoordinator = new GeneralSettingsApplyCoordinator(
                generalSettingsResolver,
                assistantRenderModeSelectionResolver
        );
        this.generalSettingsApplyDispatchCoordinator = new GeneralSettingsApplyDispatchCoordinator(
                generalSettingsApplyCoordinator,
                generalSettingsUiApplyCoordinator
        );
        this.fontSettingsResolver = new FontSettingsResolver(settingsRepo);
        this.fontSettingsPersister = new FontSettingsPersister(settingsRepo);
        this.fontMenuApplyCoordinator = new FontMenuApplyCoordinator(
                fontSelectionNormalizer,
                fontPreviewApplier,
                fontSettingsPersister
        );
        this.fontMenuSelectionRefreshCoordinator = new FontMenuSelectionRefreshCoordinator(
                fontSettingsResolver,
                fontMenuSelectionSynchronizer
        );
        this.fontMenuSelectionDispatchCoordinator =
                new FontMenuSelectionDispatchCoordinator(fontMenuSelectionRefreshCoordinator);
        this.appFontSizeStepResolver = new AppFontSizeStepResolver();
        this.appFontSizeAdjustCoordinator = new AppFontSizeAdjustCoordinator(appFontSizeStepResolver);
        this.themeSettingsResolver = new ThemeSettingsResolver(settingsRepo);
        this.themeMenuApplyCoordinator = new ThemeMenuApplyCoordinator(themeSettingsResolver, settingsRepo);
        this.themeMenuSelectionRefreshCoordinator = new ThemeMenuSelectionRefreshCoordinator(
                themeSettingsResolver,
                themeMenuSelectionSynchronizer
        );
        this.themeMenuSelectionDispatchCoordinator =
                new ThemeMenuSelectionDispatchCoordinator(themeMenuSelectionRefreshCoordinator);
        this.modelMenuDirtyRefreshCoordinator = new ModelMenuDirtyRefreshCoordinator(menuPopupVisibleRunner);
        this.modelMenuDirtyRefreshTriggerCoordinator =
                new ModelMenuDirtyRefreshTriggerCoordinator(modelMenuDirtyRefreshCoordinator);
        this.lookAndFeelMenuRefreshCoordinator = new LookAndFeelMenuRefreshCoordinator(menuPopupVisibleRunner);
        this.windowStateSettingsCoordinator = new WindowStateSettingsCoordinator(settingsRepo);
        this.windowStateRestoreCoordinator = new WindowStateRestoreCoordinator(windowStateSettingsCoordinator);
        this.conversationLoadCoordinator = new ConversationLoadCoordinator(conversationRepo);
        this.conversationLoadResultPlanner = new ConversationLoadResultPlanner(
                conversationLoadCoordinator::isCurrentRequest,
                this::resolveConversationRenderMode
        );
        this.conversationLoadApplyDispatchCoordinator = new ConversationLoadApplyDispatchCoordinator(
                conversationLoadResultPlanner,
                new ConversationLoadApplyCoordinator()
        );
        this.conversationPersistenceCoordinator = new ConversationPersistenceCoordinator(
                conversationRepo,
                persistedMessageCounter
        );
        this.assistantMessageCompletionCoordinator =
                new AssistantMessageCompletionCoordinator(conversationPersistenceCoordinator);
        this.assistantMessageCompletionFlowCoordinator =
                new AssistantMessageCompletionFlowCoordinator(assistantMessageCompletionCoordinator);
        this.currentConversationSaveCoordinator = new CurrentConversationSaveCoordinator(
                new ConversationTitleDeriver(),
                conversationPersistenceCoordinator,
                assistantRenderModeSettingsCoordinator
        );

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
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
        chatPanel.setOnMessageSubmitted(this::saveCurrentConversation);
        chatPanel.setConversationIdSupplier(() -> currentConversationId);
        chatPanel.setOnAssistantMessageCompleted(this::onAssistantMessageCompleted);
        chatPanel.setActiveConversationId(currentConversationId);
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

        sidebarToggleIconFilled = TitleBarUiSupport.loadIcon(MainFrame.class, "/icons/titlebar/panel-left-filled.svg");
        sidebarToggleIconOutline = TitleBarUiSupport.loadIcon(MainFrame.class, "/icons/titlebar/panel-left.svg");
        sidebarToggleBtn = TitleBarUiSupport.createButton(sidebarToggleIconFilled, "Toggle Sidebar");
        sidebarToggleBtn.addActionListener(e -> toggleSidebar());
        leftButtons.add(sidebarToggleBtn);

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
                UIManager.removePropertyChangeListener(lookAndFeelListener);
                chatPanel.cancelStreaming();
                saveCurrentConversation();
                saveWindowState();
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
                UIManager.removePropertyChangeListener(lookAndFeelListener);
                chatPanel.cancelStreaming();
                saveCurrentConversation();
                saveWindowState();
                response.performQuit();
            });
        }

        // Keyboard shortcuts
        setupKeyboardShortcuts();

        chatPanel.getInputBar().requestInputFocus();
    }

    private void toggleSidebar() {
        SidebarToggleCoordinator.ToggleState toggleState = sidebarToggleCoordinator.toggle(
                sidebarVisible,
                lastDividerLocation,
                splitPane.getDividerLocation(),
                splitPane,
                sidebarToggleBtn,
                sidebarToggleIconFilled,
                sidebarToggleIconOutline
        );

        sidebarVisible = toggleState.sidebarVisible();
        lastDividerLocation = toggleState.lastDividerLocation();
    }

    private void newChat() {
        newChatCoordinator.start(
                this::saveCurrentConversation,
                () -> currentConversationId = null,
                () -> pendingUnsavedConversationRenderMode = null,
                () -> chatPanel.setActiveConversationId(null),
                chatPanel::clearChatView,
                assistantMarkdownDefaultMode,
                chatPanel::setAssistantRenderMode,
                () -> chatPanel.getInputBar().requestInputFocus()
        );
    }

    private void loadConversation(UUID id) {
        conversationLoadStartCoordinator.start(
                id,
                this::saveCurrentConversation,
                value -> currentConversationId = value,
                () -> pendingUnsavedConversationRenderMode = null,
                chatPanel::setActiveConversationId,
                conversationLoadCoordinator::loadAsync,
                this::applyLoadedConversation,
                this::handleConversationLoadFailure
        );
    }

    private void saveCurrentConversation() {
        currentConversationSaveDispatchCoordinator.save(
                currentConversationId,
                pendingUnsavedConversationRenderMode,
                chatPanel.getHistory(),
                chatPanel.getSelectedModel(),
                chatPanel.getAssistantRenderMode(),
                currentConversationSaveCoordinator::save,
                saveResult -> currentConversationSaveUiApplyCoordinator.apply(
                        saveResult,
                        value -> currentConversationId = value,
                        value -> pendingUnsavedConversationRenderMode = value,
                        chatPanel::setActiveConversationId,
                        sidebarPanel::refresh,
                        sidebarPanel::selectConversation
                ),
                error -> LOG.log(Level.WARNING, "Failed to persist current conversation", error)
        );
    }

    private void applyLoadedConversation(
            long requestId,
            UUID conversationId,
            List<MessageRecord> records,
            Optional<ConversationRepo.ConversationRecord> conversation
    ) {
        conversationLoadApplyDispatchCoordinator.applyLoaded(
                requestId,
                currentConversationId,
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
                currentConversationId,
                conversationId,
                e,
                conversationLoadResultPlanner::shouldHandleFailure,
                message -> JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)
        );
    }

    private boolean onAssistantMessageCompleted(ChatPanel.AssistantMessageEvent event) {
        return assistantMessageCompletionEventDispatchCoordinator.handle(
                event,
                currentConversationId,
                (conversationId, message, activeConversationId) -> assistantMessageCompletionFlowCoordinator.handle(
                        conversationId,
                        message,
                        activeConversationId,
                        sidebarPanel::refresh,
                        sidebarPanel::selectConversation
                ),
                (conversationId, error) -> LOG.log(
                        Level.WARNING,
                        "Failed to persist assistant message for conversation %s".formatted(conversationId),
                        error
                )
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
        providerSettingsApplyCoordinator.apply(
                ProviderRegistry.allProviders(),
                chatPanel::refreshProviders,
                this::markModelsMenuDirty
        );
    }

    private void applyGeneralSettings() {
        assistantMarkdownDefaultMode = generalSettingsApplyDispatchCoordinator.apply(
                SystemInfo.isMacOS,
                currentConversationId,
                currentConversationId != null ? resolveConversationRenderMode(currentConversationId) : null,
                pendingUnsavedConversationRenderMode,
                sendOnEnter -> chatPanel.getInputBar().setSendOnEnter(sendOnEnter),
                chatPanel::setAutoScrollEnabled,
                chatPanel::setAssistantRenderMode,
                this::applyMenuBarSetting
        );
    }

    private void applyMenuBarSetting(boolean enabled) {
        menuBarSettingDispatchCoordinator.apply(
                enabled,
                this::setJMenuBar,
                this::ensureModelsMenuBar,
                () -> modelMenuBar,
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
                modelMenuBar,
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
                                syncingPreviewMenuSelection,
                                chatPanel::setAssistantRenderMode
                        ),
                        this::ensureThemesMenuReady,
                        this::ensureFontMenuReady,
                        this::ensureModelsMenuReady,
                        () -> AboutDialog.show(this)
                ),
                this::syncTogglePreviewMenuSelection,
                new MainMenuBarEnsureStateResolver.CurrentState(
                        fileMenu,
                        viewMenu,
                        modelsMenu,
                        fontMenu,
                        themesMenu,
                        togglePreviewMenuItem,
                        modelsMenuDirty,
                        themesMenuBuilt,
                        fontMenuBuilt
                ),
                value -> modelMenuBar = value,
                value -> fileMenu = value,
                value -> viewMenu = value,
                value -> modelsMenu = value,
                value -> fontMenu = value,
                value -> themesMenu = value,
                value -> togglePreviewMenuItem = value,
                value -> modelsMenuDirty = value,
                value -> themesMenuBuilt = value,
                value -> fontMenuBuilt = value
        );
    }

    private void ensureModelsMenuReady() {
        providerMenuReadyDispatchCoordinator.ensureReady(
                modelsMenuDirty,
                this::rebuildModelsMenuStructure,
                this::refreshLocalProviderAvailabilityInMenu,
                this::syncModelsMenuSelection
        );
    }

    private void ensureThemesMenuReady() {
        themeMenuReadyDispatchCoordinator.ensureReady(
                themesMenuBuilt,
                this::rebuildThemesMenuStructure,
                this::syncThemeMenuSelection
        );
    }

    private void ensureFontMenuReady() {
        fontMenuReadyDispatchCoordinator.ensureReady(
                fontMenuBuilt,
                this::rebuildFontMenuStructure,
                this::syncFontMenuSelection
        );
    }

    private void syncTogglePreviewMenuSelection() {
        assistantRenderModeToggleSelectionSyncCoordinator.sync(
                togglePreviewMenuItem,
                chatPanel.getAssistantRenderMode(),
                value -> syncingPreviewMenuSelection = value
        );
    }

    private void markModelsMenuDirty() {
        modelsMenuDirty = true;
    }

    private void onLookAndFeelChanged() {
        lookAndFeelMenuRefreshCoordinator.refresh(
                ProviderMenuIconRenderer::clearCache,
                this::markModelsMenuDirty,
                modelsMenu,
                this::ensureModelsMenuReady,
                fontMenu,
                this::ensureFontMenuReady
        );
    }

    private void rebuildModelsMenuStructure() {
        ModelMenuStructureRebuildCoordinator.RebuildState rebuildState = modelMenuStructureRebuildCoordinator.rebuild(
                modelsMenu,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                ProviderRegistry.availableProviders(),
                chatPanel::setSelectedModel,
                modelsMenuDirty,
                lastMenuSelectedModelKey
        );

        modelsMenuDirty = rebuildState.modelsMenuDirty();
        lastMenuSelectedModelKey = rebuildState.lastMenuSelectedModelKey();
    }

    private void syncModelsMenuSelection() {
        lastMenuSelectedModelKey = modelMenuSelectionDispatchCoordinator.sync(
                modelMenuItemsByKey,
                chatPanel.getSelectedModel(),
                lastMenuSelectedModelKey,
                modelsMenuDirty
        );
    }

    private void onSelectedModelChanged(String modelKey) {
        modelMenuSelectionChangeCoordinator.onSelectedModelChanged(
                modelsMenu,
                modelsMenuDirty,
                this::syncModelsMenuSelection
        );
    }

    private void onModelFavoritesChanged() {
        modelMenuDirtyRefreshTriggerCoordinator.trigger(
                modelsMenu,
                this::markModelsMenuDirty,
                this::ensureModelsMenuReady
        );
    }

    private void onModelCatalogChanged() {
        modelMenuDirtyRefreshTriggerCoordinator.trigger(
                modelsMenu,
                this::markModelsMenuDirty,
                this::ensureModelsMenuReady
        );
    }

    private void refreshLocalProviderAvailabilityInMenu() {
        providerMenuAvailabilityRefreshDispatchCoordinator.refresh(
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providerMenuIconResolver
        );
    }

    private void rebuildThemesMenuStructure() {
        ThemeMenuStructureRebuildCoordinator.RebuildState rebuildState =
                themeMenuStructureRebuildCoordinator.rebuild(
                        themesMenu,
                        themeMenuItemsByName,
                        this::applyThemeFromMenu,
                        themesMenuBuilt,
                        lastMenuSelectedTheme
                );

        themesMenuBuilt = rebuildState.themesMenuBuilt();
        lastMenuSelectedTheme = rebuildState.lastMenuSelectedTheme();
    }

    private void syncThemeMenuSelection() {
        String selectedTheme = themeMenuSelectionDispatchCoordinator.refresh(
                themeMenuItemsByName,
                lastMenuSelectedTheme,
                themesMenuBuilt,
                "GitHub"
        );

        themeMenuSelectionApplyCoordinator.apply(
                selectedTheme,
                value -> lastMenuSelectedTheme = value
        );
    }

    private void rebuildFontMenuStructure() {
        FontMenuStructureRebuildCoordinator.RebuildState rebuildState =
                fontMenuStructureRebuildCoordinator.rebuild(
                        fontMenu,
                        appFontMenuItemsByFamily,
                        appFontSizeMenuItemsBySize,
                        codeFontMenuItemsByFamily,
                        this::restoreAppFontFromMenu,
                        () -> adjustAppFontSizeFromMenu(true),
                        () -> adjustAppFontSizeFromMenu(false),
                        this::applyAppFontFamilyFromMenu,
                        this::applyCodeFontFromMenu,
                        this::applyAppFontSizeFromMenu,
                        fontMenuBuilt,
                        lastMenuSelectedAppFontFamily,
                        lastMenuSelectedAppFontSize,
                        lastMenuSelectedCodeFontFamily
                );

        fontMenuBuilt = rebuildState.fontMenuBuilt();
        lastMenuSelectedAppFontFamily = rebuildState.lastMenuSelectedAppFontFamily();
        lastMenuSelectedAppFontSize = rebuildState.lastMenuSelectedAppFontSize();
        lastMenuSelectedCodeFontFamily = rebuildState.lastMenuSelectedCodeFontFamily();
    }

    private void syncFontMenuSelection() {
        FontMenuSelectionSynchronizer.FontMenuSelectionState syncedSelection =
                fontMenuSelectionDispatchCoordinator.refresh(
                        appFontMenuItemsByFamily,
                        appFontSizeMenuItemsBySize,
                        codeFontMenuItemsByFamily,
                        lastMenuSelectedAppFontFamily,
                        lastMenuSelectedAppFontSize,
                        lastMenuSelectedCodeFontFamily,
                        fontMenuBuilt
                );

        fontMenuSelectionApplyCoordinator.apply(
                syncedSelection,
                value -> lastMenuSelectedAppFontFamily = value,
                value -> lastMenuSelectedAppFontSize = value,
                value -> lastMenuSelectedCodeFontFamily = value
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
                        appFontMenuItemsByFamily.keySet(),
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
                        codeFontMenuItemsByFamily.keySet(),
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
                currentConversationId,
                mode,
                pendingUnsavedConversationRenderMode,
                this::syncTogglePreviewMenuSelection,
                value -> pendingUnsavedConversationRenderMode = value
        );
    }

    private AssistantRenderMode resolveConversationRenderMode(UUID conversationId) {
        return assistantRenderModeSettingsCoordinator.resolveConversationMode(
                conversationId,
                assistantMarkdownDefaultMode
        );
    }

}
