package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.chat.ChatPanel;
import com.github.drafael.chat4j.chat.ChatSearchPopup;
import com.github.drafael.chat4j.chat.JcefRuntimeManager;
import com.github.drafael.chat4j.chat.MarkdownRenderOptions;
import com.github.drafael.chat4j.util.SingleInstanceWindowTracker;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.LocalServiceHealth;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.icons.FlatAbstractIcon;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.settings.AppearancePanel;
import com.github.drafael.chat4j.settings.SettingsDialog;
import com.github.drafael.chat4j.sidebar.SidebarPanel;
import com.github.drafael.chat4j.storage.ConversationRepo;
import com.github.drafael.chat4j.storage.ConversationRepo.MessageRecord;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.storage.SettingsRepo;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.net.URL;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MainFrame extends JFrame {

    private static final String MODEL_KEY_DELIMITER = " > ";
    private static final String KEY_ASSISTANT_MARKDOWN_DEFAULT = "chat.markdown.default";
    private static final String KEY_ASSISTANT_MARKDOWN_CONVERSATION_PREFIX = "chat.markdown.conv.";
    private static final String KEY_MENU_BAR_ENABLED = "menu.bar.enabled";
    private static final String KEY_THEME = "theme";
    private static final String KEY_LATEX_ENABLED = "markdown.latex.enabled";
    private static final String KEY_LATEX_SINGLE_DOLLAR = "markdown.latex.singleDollar";
    private static final String KEY_LATEX_BRACKET_DELIMITERS = "markdown.latex.bracketDelimiters";
    private static final Set<String> LOCAL_HEALTH_GATED_PROVIDERS = Set.of("LM Studio", "Ollama");
    private static final Map<String, String> PROVIDER_ICON_PATHS = Map.ofEntries(
            Map.entry("Anthropic", "/icons/providers/anthropic.svg"),
            Map.entry("OpenAI", "/icons/providers/openai.svg"),
            Map.entry("OpenAI Codex", "/icons/providers/codex.svg"),
            Map.entry("GitHub Copilot", "/icons/providers/githubcopilot.svg"),
            Map.entry("Google AI", "/icons/providers/google.svg"),
            Map.entry("OpenRouter", "/icons/providers/openrouter.svg"),
            Map.entry("Groq", "/icons/providers/groq.svg"),
            Map.entry("DeepSeek", "/icons/providers/deepseek.svg"),
            Map.entry("Mistral", "/icons/providers/mistral.svg"),
            Map.entry("xAI", "/icons/providers/xai.svg"),
            Map.entry("LM Studio", "/icons/providers/lmstudio.svg"),
            Map.entry("Ollama", "/icons/providers/ollama.svg")
    );
    private static final int PROVIDER_MODEL_ICON_SIZE = 16;
    private static final int PROVIDER_HEADER_ICON_SIZE = 18;
    private static final int PROVIDER_ICON_ALPHA_MIN = 210;
    private static final Color MAC_MENU_ICON_ENABLED = new Color(66, 66, 66);
    private static final Color MAC_MENU_ICON_DISABLED = new Color(150, 150, 150);
    private static final Map<ProviderMenuIconKey, Icon> PROVIDER_ICON_CACHE = new ConcurrentHashMap<>();

    private final ChatPanel chatPanel;
    private final SidebarPanel sidebarPanel;
    private final JSplitPane splitPane;
    private final ConversationRepo conversationRepo;
    private final SettingsRepo settingsRepo;
    private final ProviderModelCacheService modelCacheService;
    private final ModelFavoritesService modelFavoritesService;
    private UUID currentConversationId;
    private boolean sidebarVisible = true;
    private int lastDividerLocation = 250;
    private AssistantRenderMode assistantMarkdownDefaultMode = AssistantRenderMode.PREVIEW;
    private AssistantRenderMode pendingUnsavedConversationRenderMode;
    private final SingleInstanceWindowTracker<SettingsDialog> settingsDialogTracker =
            new SingleInstanceWindowTracker<>();
    private JMenuBar modelMenuBar;
    private JMenu fileMenu;
    private JMenu viewMenu;
    private JMenu modelsMenu;
    private JMenu themesMenu;
    private final Map<String, JRadioButtonMenuItem> modelMenuItemsByKey = new LinkedHashMap<>();
    private final Map<String, JMenuItem> providerHeaderItemsByName = new LinkedHashMap<>();
    private final Map<String, JRadioButtonMenuItem> themeMenuItemsByName = new LinkedHashMap<>();
    private boolean modelsMenuDirty = true;
    private boolean themesMenuBuilt;
    private String lastMenuSelectedModelKey;
    private String lastMenuSelectedTheme;
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
        chatPanel.getInputBar().addSendListener(e -> SwingUtilities.invokeLater(this::saveCurrentConversation));
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

        JButton sidebarToggleBtn = createTitleBarButton(new SidebarToggleIcon(), "Toggle Sidebar");
        sidebarToggleBtn.addActionListener(e -> toggleSidebar());
        leftButtons.add(sidebarToggleBtn);

        JButton searchBtn = createTitleBarButton(new SearchActionIcon(), "Search Chats");
        searchBtn.addActionListener(e -> openChatSearch(searchBtn));
        leftButtons.add(searchBtn);

        JButton newChatBtn = createTitleBarButton(new NewChatIcon(), "New Chat");
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
                JcefRuntimeManager.shutdown();
            }
        });

        // macOS application menu handlers
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            desktop.setPreferencesHandler(e -> openSettings());
            desktop.setQuitHandler((e, response) -> {
                UIManager.removePropertyChangeListener(lookAndFeelListener);
                chatPanel.cancelStreaming();
                saveCurrentConversation();
                saveWindowState();
                JcefRuntimeManager.shutdown();
                response.performQuit();
            });
        }

        // Keyboard shortcuts
        setupKeyboardShortcuts();

        chatPanel.getInputBar().requestInputFocus();
    }

    private void toggleSidebar() {
        if (sidebarVisible) {
            lastDividerLocation = splitPane.getDividerLocation();
            splitPane.setDividerLocation(0);
            splitPane.setDividerSize(0);
            sidebarVisible = false;
        } else {
            splitPane.setDividerSize(2);
            splitPane.setDividerLocation(lastDividerLocation);
            sidebarVisible = true;
        }
    }

    private void newChat() {
        chatPanel.cancelStreaming();
        saveCurrentConversation();
        currentConversationId = null;
        pendingUnsavedConversationRenderMode = null;
        chatPanel.clearChat();
        chatPanel.setAssistantRenderMode(assistantMarkdownDefaultMode, true);
        chatPanel.getInputBar().requestInputFocus();
    }

    private void loadConversation(UUID id) {
        chatPanel.cancelStreaming();
        saveCurrentConversation();
        currentConversationId = id;
        pendingUnsavedConversationRenderMode = null;
        try {
            List<MessageRecord> records = conversationRepo.getMessages(id);
            List<Message> messages = records.stream()
                    .map(r -> new Message(
                        Role.valueOf(r.role()),
                        r.content(),
                        r.createdAt().atZone(ZoneId.systemDefault()).toInstant()
                    )
                    )
                    .toList();
            chatPanel.loadHistory(messages);
            chatPanel.setAssistantRenderMode(resolveConversationRenderMode(id), true);

            // Restore the model selector to the conversation's model
            conversationRepo.findById(id).ifPresent(conversation ->
                    chatPanel.setSelectedModel(toModelKey(conversation.provider(), conversation.model())));

            sidebarPanel.selectConversation(id);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                this,
                "Failed to load conversation: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void saveCurrentConversation() {
        List<Message> history = chatPanel.getHistory();
        if (history.isEmpty()) return;

        try {
            if (currentConversationId == null) {
                String title = history.getFirst().content();
                if (title.length() > 50) title = title.substring(0, 50) + "...";
                ModelSelection modelSelection = parseModelSelection(chatPanel.getSelectedModel())
                        .orElse(new ModelSelection("Unknown", "unknown"));
                currentConversationId = conversationRepo.createConversation(
                    title,
                    modelSelection.provider(),
                    modelSelection.model());

                AssistantRenderMode modeToPersist = pendingUnsavedConversationRenderMode != null
                        ? pendingUnsavedConversationRenderMode
                        : chatPanel.getAssistantRenderMode();
                persistConversationRenderMode(currentConversationId, modeToPersist);
                pendingUnsavedConversationRenderMode = null;
            }

            List<MessageRecord> existing = conversationRepo.getMessages(currentConversationId);
            for (int i = existing.size(); i < history.size(); i++) {
                Message msg = history.get(i);
                conversationRepo.addMessage(currentConversationId, msg.role().name(), msg.content());
            }

            sidebarPanel.refresh();
            if (currentConversationId != null) {
                sidebarPanel.selectConversation(currentConversationId);
            }
        } catch (Exception e) {
            // Silent fail for save
        }
    }

    private void saveWindowState() {
        try {
            Rectangle bounds = getBounds();
            settingsRepo.put("window.x", String.valueOf(bounds.x));
            settingsRepo.put("window.y", String.valueOf(bounds.y));
            settingsRepo.put("window.width", String.valueOf(bounds.width));
            settingsRepo.put("window.height", String.valueOf(bounds.height));
        } catch (Exception e) {
            // ignore
        }
    }

    private void restoreWindowState() {
        try {
            int x = Integer.parseInt(settingsRepo.get("window.x", "0"));
            int y = Integer.parseInt(settingsRepo.get("window.y", "0"));
            int w = Integer.parseInt(settingsRepo.get("window.width", "1000"));
            int h = Integer.parseInt(settingsRepo.get("window.height", "700"));

            Rectangle bounds = new Rectangle(x, y, w, h);
            if (GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration()
                    .getBounds().intersects(bounds)
            ) {
                setBounds(bounds);
            } else {
                setSize(1000, 700);
                setLocationRelativeTo(null);
            }
        } catch (Exception e) {
            setSize(1000, 700);
            setLocationRelativeTo(null);
        }
    }

    private void setupKeyboardShortcuts() {
        JRootPane rootPane = getRootPane();
        String mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() == InputEvent.META_DOWN_MASK
                ? "meta" : "ctrl";

        // Cmd+N / Ctrl+N: New Chat
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(mod + " N"), "newChat");
        rootPane.getActionMap().put(
            "newChat",
            new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                newChat();
            }
        });

        // Cmd+, / Ctrl+,: Settings
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(mod + " COMMA"), "openSettings");
        rootPane.getActionMap().put(
            "openSettings",
            new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSettings();
            }
        });

        // Cmd+B / Ctrl+B: Toggle Sidebar
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(mod + " B"), "toggleSidebar");
        rootPane.getActionMap().put(
            "toggleSidebar",
            new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleSidebar();
            }
        });

        // Cmd+Shift+F / Ctrl+Shift+F: Chat Search
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(mod + " shift F"), "openChatSearch");
        rootPane.getActionMap().put(
            "openChatSearch",
            new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openChatSearch(null);
            }
        });

        // Cmd+/ / Ctrl+/: Toggle Model Dropdown
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(mod + " SLASH"), "toggleModelDropdown");
        rootPane.getActionMap().put(
            "toggleModelDropdown",
            new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                chatPanel.showModelPopupCentered();
            }
        });
    }

    private void openChatSearch(Component relativeTo) {
        ChatSearchPopup popup = new ChatSearchPopup(this, conversationRepo, this::loadConversation);
        popup.show(relativeTo);
    }

    private void openSettings() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::openSettings);
            return;
        }

        SettingsDialog existingDialog = settingsDialogTracker.get();
        if (existingDialog != null) {
            if (existingDialog.isDisplayable() && existingDialog.isVisible()) {
                existingDialog.toFront();
                existingDialog.requestFocus();
                return;
            }
            if (existingDialog.isDisplayable()) {
                existingDialog.setVisible(true);
                return;
            }
            settingsDialogTracker.clear();
        }

        SettingsDialog dialog = new SettingsDialog(this, settingsRepo);
        settingsDialogTracker.set(dialog);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                settingsDialogTracker.clear();
                applyProviderSettings();
                applyGeneralSettings();
            }
        });
        dialog.setVisible(true);
    }

    private void applyProviderSettings() {
        Map<String, ProviderRegistry.ProviderRuntimeConfig> runtimeConfigByProvider = ProviderRegistry
                .allProviders()
                .stream()
                .collect(Collectors.toMap(
                    ProviderRegistry.ProviderDef::name,
                    this::readProviderRuntimeConfig,
                    (existing, replacement) -> replacement,
                    LinkedHashMap::new
                ));

        ProviderRegistry.applyRuntimeConfig(runtimeConfigByProvider);
        chatPanel.refreshProviders();
        markModelsMenuDirty();
    }

    private ProviderRegistry.ProviderRuntimeConfig readProviderRuntimeConfig(ProviderRegistry.ProviderDef providerDef) {
        boolean enabled = true;
        String baseUrl = providerDef.baseUrl();

        try {
            enabled = Boolean.parseBoolean(
                    settingsRepo.get("provider." + providerDef.name() + ".enabled", "true"));
        } catch (Exception e) {
            enabled = true;
        }

        try {
            baseUrl = settingsRepo.get("provider." + providerDef.name() + ".baseUrl", providerDef.baseUrl());
        } catch (Exception e) {
            baseUrl = providerDef.baseUrl();
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = providerDef.baseUrl();
        }

        return new ProviderRegistry.ProviderRuntimeConfig(enabled, baseUrl);
    }

    private void applyGeneralSettings() {
        boolean menuBarEnabled = SystemInfo.isMacOS;
        MarkdownRenderOptions markdownRenderOptions = MarkdownRenderOptions.defaults();

        try {
            String sendKey = settingsRepo.get("send.key", "Enter");
            chatPanel.getInputBar().setSendOnEnter(!"Ctrl+Enter".equalsIgnoreCase(sendKey));

            boolean autoScroll = Boolean.parseBoolean(settingsRepo.get("auto.scroll", "true"));
            chatPanel.setAutoScrollEnabled(autoScroll);

            assistantMarkdownDefaultMode = AssistantRenderMode.fromSettingValue(
                    settingsRepo.get(KEY_ASSISTANT_MARKDOWN_DEFAULT, AssistantRenderMode.PREVIEW.settingValue()));

            menuBarEnabled = Boolean.parseBoolean(
                    settingsRepo.get(KEY_MENU_BAR_ENABLED, String.valueOf(SystemInfo.isMacOS)));

            boolean latexEnabled = Boolean.parseBoolean(settingsRepo.get(KEY_LATEX_ENABLED, "true"));
            boolean singleDollarEnabled = Boolean.parseBoolean(settingsRepo.get(KEY_LATEX_SINGLE_DOLLAR, "true"));
            boolean bracketDelimitersEnabled = Boolean.parseBoolean(
                    settingsRepo.get(KEY_LATEX_BRACKET_DELIMITERS, "true"));
            markdownRenderOptions = new MarkdownRenderOptions(
                    latexEnabled,
                    singleDollarEnabled,
                    bracketDelimitersEnabled
            );
        } catch (Exception e) {
            chatPanel.getInputBar().setSendOnEnter(true);
            chatPanel.setAutoScrollEnabled(true);
            assistantMarkdownDefaultMode = AssistantRenderMode.PREVIEW;
            menuBarEnabled = SystemInfo.isMacOS;
            markdownRenderOptions = MarkdownRenderOptions.defaults();
        }

        chatPanel.setMarkdownRenderOptions(markdownRenderOptions, true);

        AssistantRenderMode modeToApply = currentConversationId != null
                ? resolveConversationRenderMode(currentConversationId)
                : pendingUnsavedConversationRenderMode != null
                ? pendingUnsavedConversationRenderMode
                : assistantMarkdownDefaultMode;
        chatPanel.setAssistantRenderMode(modeToApply, true);
        applyMenuBarSetting(menuBarEnabled);
    }

    private void applyMenuBarSetting(boolean enabled) {
        if (!enabled) {
            setJMenuBar(null);
            revalidate();
            repaint();
            return;
        }

        ensureModelsMenuBar();
        setJMenuBar(modelMenuBar);
        ensureThemesMenuReady();
        ensureModelsMenuReady();
        syncTogglePreviewMenuSelection();
        revalidate();
        repaint();
    }

    private void ensureModelsMenuBar() {
        if (modelMenuBar != null) {
            return;
        }

        modelMenuBar = new JMenuBar();
        int menuShortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        fileMenu = new JMenu("File");
        JMenuItem newChatItem = new JMenuItem("New Chat");
        newChatItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_N, menuShortcut));
        newChatItem.addActionListener(e -> newChat());
        fileMenu.add(newChatItem);

        viewMenu = new JMenu("View");
        viewMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                chatPanel.hideModelPopup();
                syncTogglePreviewMenuSelection();
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });

        JMenuItem toggleSidebarItem = new JMenuItem("Toggle Sidebar");
        toggleSidebarItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_B, menuShortcut));
        toggleSidebarItem.addActionListener(e -> toggleSidebar());
        viewMenu.add(toggleSidebarItem);

        JMenuItem toggleModelDropdownItem = new JMenuItem("Toggle Model Dropdown");
        toggleModelDropdownItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, menuShortcut));
        toggleModelDropdownItem.addActionListener(e -> chatPanel.showModelPopupCentered());
        viewMenu.add(toggleModelDropdownItem);

        JMenuItem chatSearchItem = new JMenuItem("Chat Search");
        chatSearchItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuShortcut | InputEvent.SHIFT_DOWN_MASK));
        chatSearchItem.addActionListener(e -> openChatSearch(null));
        viewMenu.add(chatSearchItem);

        togglePreviewMenuItem = new JCheckBoxMenuItem("Toggle Preview");
        togglePreviewMenuItem.addActionListener(e -> {
            if (syncingPreviewMenuSelection) {
                return;
            }

            AssistantRenderMode mode = togglePreviewMenuItem.isSelected()
                    ? AssistantRenderMode.PREVIEW
                    : AssistantRenderMode.MARKDOWN;
            chatPanel.setAssistantRenderMode(mode, true);
        });
        viewMenu.add(togglePreviewMenuItem);
        syncTogglePreviewMenuSelection();

        themesMenu = new JMenu("Theme");
        themesMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                chatPanel.hideModelPopup();
                ensureThemesMenuReady();
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });

        modelsMenu = new JMenu("Model");
        modelsMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                chatPanel.hideModelPopup();
                ensureModelsMenuReady();
            }

            @Override
            public void menuDeselected(MenuEvent e) {
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });

        modelMenuBar.add(fileMenu);
        modelMenuBar.add(viewMenu);
        modelMenuBar.add(modelsMenu);
        modelMenuBar.add(themesMenu);
        modelsMenuDirty = true;
        themesMenuBuilt = false;
    }

    private void ensureModelsMenuReady() {
        if (modelsMenuDirty) {
            rebuildModelsMenuStructure();
        }

        refreshLocalProviderAvailabilityInMenu();
        syncModelsMenuSelection();
    }

    private void ensureThemesMenuReady() {
        if (!themesMenuBuilt) {
            rebuildThemesMenuStructure();
        }
        syncThemeMenuSelection();
    }

    private void syncTogglePreviewMenuSelection() {
        if (togglePreviewMenuItem == null) {
            return;
        }

        syncingPreviewMenuSelection = true;
        togglePreviewMenuItem.setSelected(chatPanel.getAssistantRenderMode() == AssistantRenderMode.PREVIEW);
        syncingPreviewMenuSelection = false;
    }

    private void markModelsMenuDirty() {
        modelsMenuDirty = true;
    }

    private void onLookAndFeelChanged() {
        PROVIDER_ICON_CACHE.clear();
        markModelsMenuDirty();
        if (modelsMenu != null && modelsMenu.isPopupMenuVisible()) {
            ensureModelsMenuReady();
        }
    }

    private void rebuildModelsMenuStructure() {
        if (modelsMenu == null) {
            return;
        }

        modelsMenu.removeAll();
        modelMenuItemsByKey.clear();
        providerHeaderItemsByName.clear();
        List<ProviderRegistry.ProviderDef> providers = ProviderRegistry.availableProviders();
        if (providers.isEmpty()) {
            JMenuItem empty = new JMenuItem("No providers available");
            empty.setEnabled(false);
            modelsMenu.add(empty);
            modelsMenuDirty = false;
            lastMenuSelectedModelKey = null;
            return;
        }

        Map<String, List<String>> modelsByProvider = providers.stream()
                .collect(Collectors.toMap(
                    ProviderRegistry.ProviderDef::name,
                    provider -> {
                            List<String> models = sanitizeModelIds(provider.name(), modelCacheService.getModels(provider.name()));
                            return models.isEmpty()
                                    ? sanitizeModelIds(provider.name(), provider.seedModels())
                                    : models;
                        },
                    (existing, replacement) -> existing,
                    LinkedHashMap::new
                ));

        ButtonGroup group = new ButtonGroup();

        Map<String, Boolean> providerSelectable = providers.stream()
                .collect(Collectors.toMap(
                        ProviderRegistry.ProviderDef::name,
                        this::isProviderModelSelectionEnabled,
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));

        List<ModelSelection> favorites = providers.stream()
                .flatMap(provider -> modelsByProvider.getOrDefault(provider.name(), List.of()).stream()
                        .filter(model -> modelFavoritesService.isFavorite(provider.name(), model))
                        .map(model -> new ModelSelection(provider.name(), model))
                )
                .toList();

        if (!favorites.isEmpty()) {
            JMenuItem favoritesHeader = new JMenuItem("★ Favorites");
            favoritesHeader.setEnabled(false);
            modelsMenu.add(favoritesHeader);

            favorites.forEach(selection -> {
                String modelKey = toModelKey(selection.provider(), selection.model());
                String label = selection.model() + " (" + selection.provider() + ")";
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(label);
                boolean selectable = providerSelectable.getOrDefault(selection.provider(), true);
                item.setEnabled(selectable);
                item.setIcon(providerIcon(selection.provider(), PROVIDER_MODEL_ICON_SIZE, resolveMenuItemIconTint(item, selectable)));
                item.setIconTextGap(8);
                item.addActionListener(e -> chatPanel.setSelectedModel(modelKey));
                group.add(item);
                modelsMenu.add(item);
                modelMenuItemsByKey.put(modelKey, item);
            });

            modelsMenu.addSeparator();
        }

        boolean firstProvider = true;
        for (ProviderRegistry.ProviderDef provider : providers) {
            if (!firstProvider) {
                modelsMenu.addSeparator();
            }

            boolean providerEnabled = providerSelectable.getOrDefault(provider.name(), true);
            String providerLabel = providerEnabled ? provider.name() : provider.name() + " (offline)";

            JMenuItem providerHeader = createProviderHeader(provider.name(), providerLabel, providerEnabled);
            modelsMenu.add(providerHeader);
            providerHeaderItemsByName.put(provider.name(), providerHeader);

            List<String> models = modelsByProvider.getOrDefault(provider.name(), List.of()).stream()
                    .filter(model -> !modelFavoritesService.isFavorite(provider.name(), model))
                    .toList();

            if (models.isEmpty()) {
                JMenuItem emptyProvider = new JMenuItem("No models available");
                emptyProvider.setEnabled(false);
                modelsMenu.add(emptyProvider);
            } else {
                models.forEach(model -> {
                    String modelKey = toModelKey(provider.name(), model);
                    JRadioButtonMenuItem item = new JRadioButtonMenuItem(model);
                    item.setEnabled(providerEnabled);
                    item.setIcon(providerIcon(provider.name(), PROVIDER_MODEL_ICON_SIZE, resolveMenuItemIconTint(item, providerEnabled)));
                    item.setIconTextGap(8);
                    item.addActionListener(e -> chatPanel.setSelectedModel(modelKey));
                    group.add(item);
                    modelsMenu.add(item);
                    modelMenuItemsByKey.put(modelKey, item);
                });
            }

            firstProvider = false;
        }

        modelsMenuDirty = false;
        lastMenuSelectedModelKey = null;
    }

    private void syncModelsMenuSelection() {
        if (modelsMenuDirty) {
            return;
        }

        String selectedModelKey = chatPanel.getSelectedModel();
        if (Objects.equals(selectedModelKey, lastMenuSelectedModelKey)) {
            return;
        }

        if (lastMenuSelectedModelKey != null) {
            JRadioButtonMenuItem previous = modelMenuItemsByKey.get(lastMenuSelectedModelKey);
            if (previous != null) {
                previous.setSelected(false);
            }
        }

        if (selectedModelKey != null) {
            JRadioButtonMenuItem current = modelMenuItemsByKey.get(selectedModelKey);
            if (current != null) {
                current.setSelected(true);
            }
        }

        lastMenuSelectedModelKey = selectedModelKey;
    }

    private void onSelectedModelChanged(String modelKey) {
        if (modelsMenu == null || modelsMenuDirty) {
            return;
        }

        syncModelsMenuSelection();
    }

    private void onModelFavoritesChanged() {
        markModelsMenuDirty();
        if (modelsMenu != null && modelsMenu.isPopupMenuVisible()) {
            ensureModelsMenuReady();
        }
    }

    private void onModelCatalogChanged() {
        markModelsMenuDirty();
        if (modelsMenu != null && modelsMenu.isPopupMenuVisible()) {
            ensureModelsMenuReady();
        }
    }

    private void refreshLocalProviderAvailabilityInMenu() {
        if (modelMenuItemsByKey.isEmpty()) {
            return;
        }

        Map<String, String> defaultBaseUrlByProvider = ProviderRegistry.allProviders().stream()
                .collect(Collectors.toMap(
                        ProviderRegistry.ProviderDef::name,
                        ProviderRegistry.ProviderDef::baseUrl,
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new
                ));

        Map<String, Boolean> providerEnabledByName = new LinkedHashMap<>();
        LOCAL_HEALTH_GATED_PROVIDERS.forEach(providerName -> {
            String defaultBaseUrl = defaultBaseUrlByProvider.get(providerName);
            String configuredBaseUrl = readConfiguredProviderBaseUrl(providerName, defaultBaseUrl);
            providerEnabledByName.put(providerName, LocalServiceHealth.isReachable(configuredBaseUrl));
        });

        modelMenuItemsByKey.forEach((modelKey, item) -> parseModelSelection(modelKey).ifPresentOrElse(selection -> {
                    Boolean enabled = providerEnabledByName.get(selection.provider());
                    boolean selectable = enabled == null || enabled;
                    item.setEnabled(selectable);
                    item.setIcon(providerIcon(
                            selection.provider(),
                            PROVIDER_MODEL_ICON_SIZE,
                            resolveMenuItemIconTint(item, selectable)));
                },
                () -> item.setEnabled(true)
        ));

        providerHeaderItemsByName.forEach((providerName, headerItem) -> {
            Boolean enabled = providerEnabledByName.get(providerName);
            boolean providerEnabled = enabled == null || enabled;
            String text = providerEnabled ? providerName : providerName + " (offline)";
            updateProviderHeader(headerItem, providerName, text, providerEnabled);
        });
    }

    private JMenuItem createProviderHeader(String providerName, String text, boolean enabled) {
        JMenuItem header = new JMenuItem();
        header.setEnabled(false);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 13f));
        header.setIconTextGap(10);
        updateProviderHeader(header, providerName, text, enabled);
        return header;
    }

    private void updateProviderHeader(JMenuItem header, String providerName, String text, boolean enabled) {
        header.setText(text);
        header.setIcon(providerIcon(
                providerName,
                PROVIDER_HEADER_ICON_SIZE,
                resolveMenuItemIconTint(header, enabled)));
    }

    private String readConfiguredProviderBaseUrl(String providerName, String defaultBaseUrl) {
        try {
            String value = settingsRepo.get("provider." + providerName + ".baseUrl", defaultBaseUrl);
            if (value == null || value.isBlank()) {
                return defaultBaseUrl;
            }
            return value;
        } catch (Exception e) {
            return defaultBaseUrl;
        }
    }

    private boolean isProviderModelSelectionEnabled(ProviderRegistry.ProviderDef provider) {
        if (!LOCAL_HEALTH_GATED_PROVIDERS.contains(provider.name())) {
            return true;
        }

        return LocalServiceHealth.isReachableNonBlocking(provider.baseUrl());
    }

    private void rebuildThemesMenuStructure() {
        if (themesMenu == null) {
            return;
        }

        themesMenu.removeAll();
        themeMenuItemsByName.clear();

        ButtonGroup group = new ButtonGroup();
        boolean firstSection = true;
        for (Map.Entry<String, Map<String, String>> section : AppearancePanel.groupedThemes().entrySet()) {
            if (!firstSection) {
                themesMenu.addSeparator();
            }

            JMenuItem sectionHeader = new JMenuItem(section.getKey());
            sectionHeader.setEnabled(false);
            themesMenu.add(sectionHeader);

            section.getValue().forEach((themeName, className) -> {
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(themeName);
                item.addActionListener(e -> applyThemeFromMenu(themeName, className));
                group.add(item);
                themesMenu.add(item);
                themeMenuItemsByName.put(themeName, item);
            });
            firstSection = false;
        }

        themesMenuBuilt = true;
        lastMenuSelectedTheme = null;
    }

    private void syncThemeMenuSelection() {
        if (!themesMenuBuilt) {
            return;
        }

        String selectedTheme = "GitHub";
        try {
            selectedTheme = settingsRepo.get(KEY_THEME, "GitHub");
        } catch (Exception e) {
            selectedTheme = "GitHub";
        }

        if (Objects.equals(selectedTheme, lastMenuSelectedTheme)) {
            return;
        }

        if (lastMenuSelectedTheme != null) {
            JRadioButtonMenuItem previous = themeMenuItemsByName.get(lastMenuSelectedTheme);
            if (previous != null) {
                previous.setSelected(false);
            }
        }

        JRadioButtonMenuItem current = themeMenuItemsByName.get(selectedTheme);
        if (current != null) {
            current.setSelected(true);
        }

        lastMenuSelectedTheme = selectedTheme;
    }

    private void applyThemeFromMenu(String themeName, String className) {
        try {
            UIManager.setLookAndFeel(className);
            PROVIDER_ICON_CACHE.clear();
            markModelsMenuDirty();
            AppearancePanel.applySavedFonts(settingsRepo);
            refreshAllWindows();
            settingsRepo.put(KEY_THEME, themeName);
            syncThemeMenuSelection();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(
                this,
                "Failed to apply theme: " + e.getMessage(),
                "Theme Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private static void refreshAllWindows() {
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            window.invalidate();
            window.validate();
            window.repaint();
        }
    }

    private static Icon providerIcon(String providerName, int size, Color tintColor) {
        String iconPath = PROVIDER_ICON_PATHS.get(providerName);
        if (iconPath == null || iconPath.isBlank()) {
            return null;
        }

        Color effectiveTint = normalizeOpaque(tintColor, new Color(60, 60, 60));
        ProviderMenuIconKey cacheKey = new ProviderMenuIconKey(iconPath, size, effectiveTint.getRGB());
        return PROVIDER_ICON_CACHE.computeIfAbsent(cacheKey, key -> {
            URL url = MainFrame.class.getResource(key.path());
            if (url == null) {
                return null;
            }

            Color iconColor = new Color(key.fallbackRgb(), true);
            FlatSVGIcon icon = new FlatSVGIcon(url).derive(key.size(), key.size());
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
                int alpha = color.getAlpha() == 0 ? 0 : Math.max(color.getAlpha(), PROVIDER_ICON_ALPHA_MIN);
                return new Color(iconColor.getRed(), iconColor.getGreen(), iconColor.getBlue(), alpha);
            }));
            return icon.hasFound() ? icon : null;
        });
    }

    private static Color resolveMenuItemIconTint(JMenuItem item, boolean enabled) {
        if (SystemInfo.isMacOS && Boolean.parseBoolean(System.getProperty("apple.laf.useScreenMenuBar", "false"))) {
            return enabled ? MAC_MENU_ICON_ENABLED : MAC_MENU_ICON_DISABLED;
        }

        if (enabled) {
            return normalizeOpaque(item.getForeground(), new Color(55, 55, 55));
        }

        Color disabledForeground = UIManager.getColor("MenuItem.disabledForeground");
        return normalizeOpaque(disabledForeground, new Color(140, 140, 140));
    }

    private static Color normalizeOpaque(Color candidate, Color fallback) {
        if (candidate == null) {
            return fallback;
        }
        return new Color(candidate.getRed(), candidate.getGreen(), candidate.getBlue());
    }

    private static List<String> sanitizeModelIds(String providerName, List<String> modelIds) {
        return ModelOrdering.sanitizeAndSortByProvider(providerName, modelIds);
    }

    private void onAssistantRenderModeChanged(AssistantRenderMode mode) {
        if (mode == null) {
            return;
        }

        syncTogglePreviewMenuSelection();

        if (currentConversationId != null) {
            persistConversationRenderMode(currentConversationId, mode);
            return;
        }

        pendingUnsavedConversationRenderMode = mode;
    }

    private AssistantRenderMode resolveConversationRenderMode(UUID conversationId) {
        if (conversationId == null) {
            return assistantMarkdownDefaultMode;
        }

        try {
            String key = KEY_ASSISTANT_MARKDOWN_CONVERSATION_PREFIX + conversationId;
            String stored = settingsRepo.get(key, null);
            if (stored == null || stored.isBlank()) {
                return assistantMarkdownDefaultMode;
            }
            return AssistantRenderMode.fromSettingValue(stored);
        } catch (Exception e) {
            return assistantMarkdownDefaultMode;
        }
    }

    private void persistConversationRenderMode(UUID conversationId, AssistantRenderMode mode) {
        if (conversationId == null || mode == null) {
            return;
        }

        try {
            settingsRepo.put(
                KEY_ASSISTANT_MARKDOWN_CONVERSATION_PREFIX + conversationId,
                mode.settingValue());
        } catch (Exception e) {
            // ignore mode persistence failure
        }
    }

    private static String toModelKey(String providerName, String modelId) {
        return providerName + MODEL_KEY_DELIMITER + modelId;
    }

    private static Optional<ModelSelection> parseModelSelection(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return Optional.empty();
        }

        String[] parts = modelKey.split(MODEL_KEY_DELIMITER, 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new ModelSelection(parts[0], parts[1]));
    }

    private record ModelSelection(String provider, String model) {
    }

    private record ProviderMenuIconKey(String path, int size, int fallbackRgb) {
    }

    private static JButton createTitleBarButton(Icon icon, String tooltip) {
        JButton btn = new JButton(icon);
        btn.putClientProperty("JButton.buttonType", "borderless");
        btn.setToolTipText(tooltip);
        btn.setFocusable(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(24, 24));
        btn.setMinimumSize(new Dimension(24, 24));
        btn.setMaximumSize(new Dimension(24, 24));
        btn.setHorizontalAlignment(SwingConstants.CENTER);
        btn.setVerticalAlignment(SwingConstants.CENTER);
        return btn;
    }

    private abstract static class TitleBarActionIcon extends FlatAbstractIcon {
        protected TitleBarActionIcon() {
            super(16, 16, null);
        }

        protected final void applyStyle(Graphics2D graphics) {
            Color color = UIManager.getColor("Label.foreground");
            graphics.setColor(color != null ? color : Color.GRAY);
            graphics.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }

        protected final boolean isDarkTheme() {
            Color background = UIManager.getColor("Panel.background");
            if (background == null) {
                background = UIManager.getColor("Menu.background");
            }
            if (background == null) {
                return false;
            }

            float brightness = (0.299f * background.getRed()
                    + 0.587f * background.getGreen()
                    + 0.114f * background.getBlue()) / 255f;
            return brightness < 0.5f;
        }

        protected final Color blend(Color base, Color target, float targetWeight, int alpha) {
            float clampedWeight = Math.max(0f, Math.min(1f, targetWeight));
            float baseWeight = 1f - clampedWeight;
            int red = Math.round(base.getRed() * baseWeight + target.getRed() * clampedWeight);
            int green = Math.round(base.getGreen() * baseWeight + target.getGreen() * clampedWeight);
            int blue = Math.round(base.getBlue() * baseWeight + target.getBlue() * clampedWeight);
            return new Color(red, green, blue, Math.max(0, Math.min(255, alpha)));
        }
    }

    private static class SidebarToggleIcon extends TitleBarActionIcon {
        @Override
        protected void paintIcon(Component component, Graphics2D graphics) {
            applyStyle(graphics);

            Color strokeColor = graphics.getColor();
            Color leftPaneFill = isDarkTheme()
                    ? blend(strokeColor, Color.WHITE, 0.58f, 190)
                    : blend(strokeColor, Color.BLACK, 0.45f, 145);
            Shape previousClip = graphics.getClip();
            Shape viewport = new java.awt.geom.RoundRectangle2D.Float(2f, 2f, 12f, 11f, 2f, 2f);

            graphics.setClip(viewport);
            graphics.setColor(leftPaneFill);
            graphics.fillRect(3, 3, 3, 10);
            graphics.setClip(previousClip);

            graphics.setColor(strokeColor);
            graphics.drawRoundRect(2, 2, 12, 11, 2, 2);

            Stroke previousStroke = graphics.getStroke();
            graphics.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            graphics.drawLine(6, 3, 6, 12);
            graphics.setStroke(previousStroke);
        }
    }

    private static class SearchActionIcon extends TitleBarActionIcon {
        @Override
        protected void paintIcon(Component component, Graphics2D graphics) {
            applyStyle(graphics);
            graphics.drawOval(2, 2, 9, 9);
            graphics.drawLine(10, 10, 14, 14);
        }
    }

    private static class NewChatIcon extends TitleBarActionIcon {
        @Override
        protected void paintIcon(Component component, Graphics2D graphics) {
            applyStyle(graphics);

            graphics.drawRoundRect(2, 2, 12, 9, 5, 5);
            graphics.drawLine(5, 11, 4, 14);
            graphics.drawLine(4, 14, 8, 11);
        }
    }
}
