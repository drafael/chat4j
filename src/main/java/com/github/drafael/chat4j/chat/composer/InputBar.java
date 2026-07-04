package com.github.drafael.chat4j.chat.composer;

import com.github.drafael.chat4j.chat.ui.WrapLayout;
import com.github.drafael.chat4j.chat.ui.RoundedImageIcon;
import com.github.drafael.chat4j.chat.ui.HoverRoundButton;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.icons.FlatFileViewDirectoryIcon;
import com.formdev.flatlaf.icons.FlatFileViewFileIcon;
import com.formdev.flatlaf.util.SystemFileChooser;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.ComposerState;
import com.github.drafael.chat4j.chat.ui.InputComposerShellPanel;
import com.github.drafael.chat4j.chat.ui.InputIconButton;
import com.github.drafael.chat4j.chat.ui.InputIconToggleButton;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.util.Fonts;
import com.github.drafael.chat4j.util.PopupMenuSupport;
import com.github.drafael.chat4j.web.WebSearchOption;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import static java.util.Collections.emptyList;

public class InputBar extends JPanel {

    private static final int SHELL_ARC = 24;
    private static final int CHIP_ICON_SIZE = 12;
    private static final int ATTACH_ICON_SIZE = 16;
    private static final int COMMAND_CENTER_ICON_SIZE = ATTACH_ICON_SIZE;
    private static final int THINKING_ICON_SIZE = ATTACH_ICON_SIZE;
    private static final int WEB_SEARCH_ICON_SIZE = ATTACH_ICON_SIZE;
    private static final int AGENT_ICON_SIZE = ATTACH_ICON_SIZE;
    private static final int CLEAR_CHAT_ICON_SIZE = ATTACH_ICON_SIZE;
    private static final int STOP_ICON_SIZE = 24;
    private static final int STOP_BUTTON_SIZE = 28;
    private static final int INPUT_ICON_BUTTON_SIZE = 26;
    private static final int INPUT_ICON_BUTTON_ARC = 9;
    private static final String INPUT_ICON_BUTTON_ACTIVE = "chat4j.inputIconButton.active";
    private static final int PROJECT_ROOT_BUTTON_HEIGHT = 24;
    private static final int PROJECT_ROOT_BUTTON_MIN_WIDTH = 72;
    private static final int MENU_ICON_SIZE = 14;
    private static final float CHIP_HOVER_BG_DELTA = 0.09f;
    private static final float CHIP_HOVER_BORDER_DELTA = 0.12f;
    private static final Map<String, Icon> CHIP_ICON_CACHE = new ConcurrentHashMap<>();

    private final JTextArea textArea;
    private final JScrollPane scrollPane;
    private final JPanel chipsPanel;
    private final JPanel composerShell;
    private final JButton attachButton;
    private final JButton commandCenterButton;
    private final JButton thinkingButton;
    private final JToggleButton webSearchButton;
    private final JPopupMenu webSearchMenu = PopupMenuSupport.configureNativeSafePopup(new JPopupMenu());
    private final Map<String, JRadioButtonMenuItem> webSearchOptionItems = new LinkedHashMap<>();
    private final JToggleButton agentModeButton;
    private final JButton clearChatButton;
    private final JPopupMenu reasoningLevelMenu = PopupMenuSupport.configureNativeSafePopup(new JPopupMenu());
    private final Map<ReasoningLevel, JRadioButtonMenuItem> reasoningLevelItems = new LinkedHashMap<>();
    private final JPanel projectRootRowPanel;
    private final JLabel projectRootIconLabel;
    private final JButton projectRootButton;
    private final JLabel agentAccessLabel;
    private final JLabel validationLabel;
    private JButton cancelGenerationButton;
    private final JPopupMenu slashPopupMenu = PopupMenuSupport.configureNativeSafePopup(new JPopupMenu());
    private final SlashPopupPanel slashPopupContentPanel = new SlashPopupPanel(
            this::resolveListBackground,
            this::resolvePopupBorderColor
    );
    private JScrollPane slashPopupScrollPane;
    private AWTEventListener slashPopupOutsideClickListener;
    private boolean slashPopupOutsideClickListenerInstalled;
    private final DefaultListModel<SkillCommand> slashSuggestions = new DefaultListModel<>();
    private final JList<SkillCommand> slashSuggestionsList = new JList<>(slashSuggestions) {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
    };
    private final List<ComposerAttachment> attachments = new ArrayList<>();
    private final List<String> activeSkills = new ArrayList<>();
    private final List<SkillCommand> availableSkills = new ArrayList<>();
    private final AttachmentSelectionPolicy attachmentSelectionPolicy = new AttachmentSelectionPolicy();
    private final List<ActionListener> sendListeners = new ArrayList<>();
    private final List<ActionListener> commandCenterListeners = new ArrayList<>();
    private final List<ActionListener> clearChatListeners = new ArrayList<>();
    private final List<ActionListener> cancelGenerationListeners = new ArrayList<>();
    private final List<Consumer<ReasoningLevel>> reasoningLevelListeners = new ArrayList<>();
    private final List<Consumer<Boolean>> webSearchEnabledListeners = new ArrayList<>();
    private final List<Consumer<String>> webSearchOptionListeners = new ArrayList<>();
    private final List<Consumer<Integer>> webBrowseTopNListeners = new ArrayList<>();
    private final List<Consumer<Boolean>> agentModeListeners = new ArrayList<>();
    private final List<Consumer<Path>> agentProjectRootListeners = new ArrayList<>();
    private boolean sendOnEnter = true;
    private ReasoningLevel reasoningLevel = ReasoningLevel.OFF;
    private boolean thinkingAvailable = false;
    private boolean webSearchAvailable = false;
    private boolean webSearchEnabled = false;
    private boolean webSearchLockedEnabled = false;
    private String webSearchOptionId;
    private int webBrowseTopN = 3;
    private List<WebSearchOption> webSearchOptions = emptyList();
    private boolean agentModeAvailable = false;
    private boolean agentModeEnabled = false;
    private boolean agentModeRequested = false;
    private boolean clearChatAvailable = false;
    private Path agentProjectRoot;
    private int slashTokenStart = -1;
    private ProjectRootChooser projectRootChooser = parent -> showProjectRootChooser(parent, null);

    public InputBar() {
        setLayout(new BorderLayout());

        textArea = new JTextArea(3, 40);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);
        textArea.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        Fonts.apply(textArea, Font.PLAIN, Fonts.SIZE_BODY_LARGE);
        textArea.putClientProperty("JTextField.placeholderText", "Ask, edit, or run an agent task…  / for skills");
        textArea.putClientProperty(FlatClientProperties.STYLE, "border:0,0,0,0;background:null");
        textArea.putClientProperty("JComponent.outline", null);
        textArea.setTransferHandler(new FileDropTransferHandler(this::addAttachments));
        textArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (handleSlashPopupKey(e)) {
                    return;
                }

                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (sendOnEnter && !e.isShiftDown()) {
                        e.consume();
                        fireSend();
                    } else if (!sendOnEnter && (e.isControlDown() || e.isMetaDown())) {
                        e.consume();
                        fireSend();
                    }
                }
            }
        });

        textArea.setComponentPopupMenu(createEditContextMenu(textArea));

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                onInputChanged();
            }

            public void removeUpdate(DocumentEvent e) {
                onInputChanged();
            }

            public void changedUpdate(DocumentEvent e) {
                onInputChanged();
            }
        });

        scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBorder(null);
        scrollPane.putClientProperty(FlatClientProperties.STYLE, "border:0,0,0,0");
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getViewport().setBorder(null);

        chipsPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 2, 0));
        chipsPanel.setOpaque(false);
        chipsPanel.setVisible(false);

        attachButton = new InputIconButton(this::paintInputIconButtonBackground);
        configureInputIconButton(attachButton);
        attachButton.setIcon(attachIcon(UIManager.getColor("Label.foreground")));
        attachButton.setToolTipText("Attach files/images");
        attachButton.addActionListener(e -> openAttachmentPicker());

        commandCenterButton = new InputIconButton(this::paintInputIconButtonBackground);
        configureInputIconButton(commandCenterButton);
        commandCenterButton.setIcon(commandCenterIcon(UIManager.getColor("Label.foreground")));
        commandCenterButton.setToolTipText("Command center  %sP".formatted(SystemInfo.isMacOS ? "⌘" : "Ctrl+"));
        commandCenterButton.addActionListener(e -> fireCommandCenter());

        thinkingButton = new InputIconButton(this::paintInputIconButtonBackground);
        configureInputIconButton(thinkingButton);
        thinkingButton.addActionListener(e -> toggleReasoningSelector());
        initializeReasoningLevelMenu();

        webSearchButton = new InputIconToggleButton(this::paintInputIconButtonBackground);
        configureInputIconButton(webSearchButton);
        webSearchButton.setToolTipText("Web Search");
        webSearchButton.setVisible(false);
        webSearchButton.addActionListener(e -> onWebSearchButtonClicked());
        webSearchButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowWebSearchMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowWebSearchMenu(e);
            }
        });
        rebuildWebSearchMenu();

        agentModeButton = new InputIconToggleButton(this::paintInputIconButtonBackground);
        configureInputIconButton(agentModeButton);
        agentModeButton.setToolTipText("Agent mode");
        agentModeButton.setVisible(false);
        agentModeButton.addActionListener(e -> onAgentModeButtonClicked());

        clearChatButton = new InputIconButton(this::paintInputIconButtonBackground);
        configureInputIconButton(clearChatButton);
        clearChatButton.setToolTipText("Clear chat");
        clearChatButton.setVisible(false);
        clearChatButton.addActionListener(e -> fireClearChat());

        cancelGenerationButton = new JButton();
        cancelGenerationButton.putClientProperty("JButton.buttonType", "toolBarButton");
        cancelGenerationButton.putClientProperty(
                FlatClientProperties.STYLE,
                "focusWidth:0;innerFocusWidth:0;arc:10"
        );
        cancelGenerationButton.setFocusable(false);
        cancelGenerationButton.setToolTipText("Stop generation");
        cancelGenerationButton.setPreferredSize(new Dimension(STOP_BUTTON_SIZE, STOP_BUTTON_SIZE));
        cancelGenerationButton.setMinimumSize(new Dimension(STOP_BUTTON_SIZE, STOP_BUTTON_SIZE));
        cancelGenerationButton.setMaximumSize(new Dimension(STOP_BUTTON_SIZE, STOP_BUTTON_SIZE));
        cancelGenerationButton.setHorizontalAlignment(SwingConstants.CENTER);
        cancelGenerationButton.setVerticalAlignment(SwingConstants.CENTER);
        cancelGenerationButton.setMargin(new Insets(0, 0, 0, 0));
        cancelGenerationButton.setVisible(false);
        cancelGenerationButton.addActionListener(e -> fireCancelGeneration());

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actionsPanel.setOpaque(false);
        actionsPanel.add(attachButton);
        actionsPanel.add(commandCenterButton);
        actionsPanel.add(thinkingButton);
        actionsPanel.add(webSearchButton);
        actionsPanel.add(agentModeButton);
        actionsPanel.add(clearChatButton);

        JPanel cancelPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        cancelPanel.setOpaque(false);
        cancelPanel.add(cancelGenerationButton);

        JPanel actionsRow = new JPanel(new BorderLayout(0, 0));
        actionsRow.setOpaque(false);
        actionsRow.add(actionsPanel, BorderLayout.WEST);
        actionsRow.add(cancelPanel, BorderLayout.EAST);

        projectRootIconLabel = new JLabel(new FlatFileViewDirectoryIcon());
        projectRootIconLabel.setVisible(false);

        projectRootButton = new JButton();
        projectRootButton.putClientProperty("JButton.buttonType", "toolBarButton");
        projectRootButton.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:8");
        projectRootButton.setFocusable(false);
        projectRootButton.setHorizontalAlignment(SwingConstants.LEFT);
        projectRootButton.setMargin(new Insets(0, 6, 0, 6));
        projectRootButton.setPreferredSize(new Dimension(PROJECT_ROOT_BUTTON_MIN_WIDTH, PROJECT_ROOT_BUTTON_HEIGHT));
        projectRootButton.setVisible(false);
        projectRootButton.addActionListener(e -> onProjectRootButtonClicked());

        agentAccessLabel = new JLabel("Full access");
        Fonts.apply(agentAccessLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        agentAccessLabel.setVisible(false);

        projectRootRowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        projectRootRowPanel.setOpaque(false);
        projectRootRowPanel.add(projectRootIconLabel);
        projectRootRowPanel.add(projectRootButton);
        projectRootRowPanel.add(agentAccessLabel);
        projectRootRowPanel.setVisible(false);

        validationLabel = new JLabel();
        Fonts.apply(validationLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        validationLabel.setVisible(false);
        validationLabel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel composerBottomPanel = new JPanel();
        composerBottomPanel.setOpaque(false);
        composerBottomPanel.setLayout(new BoxLayout(composerBottomPanel, BoxLayout.Y_AXIS));
        actionsRow.setAlignmentX(LEFT_ALIGNMENT);
        projectRootRowPanel.setAlignmentX(LEFT_ALIGNMENT);
        composerBottomPanel.add(actionsRow);
        composerBottomPanel.add(Box.createVerticalStrut(4));
        composerBottomPanel.add(projectRootRowPanel);
        composerBottomPanel.add(validationLabel);

        composerShell = new InputComposerShellPanel(SHELL_ARC);
        composerShell.setLayout(new BorderLayout(0, 4));
        composerShell.setBorder(BorderFactory.createEmptyBorder(6, 4, 3, 4));
        composerShell.setTransferHandler(new FileDropTransferHandler(this::addAttachments));
        composerShell.add(chipsPanel, BorderLayout.NORTH);
        composerShell.add(scrollPane, BorderLayout.CENTER);
        composerShell.add(composerBottomPanel, BorderLayout.SOUTH);

        configureSlashPopup();
        refreshSkills();

        applyThemeStyles();
        add(composerShell, BorderLayout.CENTER);

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateProjectRootPresentation();
            }
        });
    }

    private void configureInputIconButton(AbstractButton button) {
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:8");
        button.setFocusable(false);
        button.setRolloverEnabled(true);
        button.setOpaque(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setVerticalAlignment(SwingConstants.CENTER);
        Dimension size = new Dimension(INPUT_ICON_BUTTON_SIZE, INPUT_ICON_BUTTON_SIZE);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        button.setMaximumSize(size);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        refreshDetachedPopupUis();
        applyThemeStyles();
        if (chipsPanel != null) {
            refreshChips();
        }
    }

    public String getText() {
        return textArea.getText().trim();
    }

    public String getRawText() {
        return textArea.getText();
    }

    public void setText(String text) {
        textArea.setText(StringUtils.defaultString(text));
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    public ComposerState getComposerState() {
        return new ComposerState(textArea.getText(), attachments, activeSkills);
    }

    public ComposerState consumeComposerState() {
        ComposerState state = getComposerState();
        clear();
        return state;
    }

    public void setComposerState(ComposerState state) {
        ComposerState safeState = state == null ? ComposerState.empty() : state;
        textArea.setText(safeState.text());
        textArea.setCaretPosition(textArea.getDocument().getLength());
        attachments.clear();
        attachments.addAll(safeState.attachments());
        activeSkills.clear();
        activeSkills.addAll(safeState.activeSkills());
        refreshChips();
        hideSlashPopup();
        clearValidationMessage();
    }

    public void clear() {
        textArea.setText("");
        attachments.clear();
        activeSkills.clear();
        refreshChips();
        hideSlashPopup();
        clearValidationMessage();
    }

    public void showValidationMessage(String message) {
        if (StringUtils.isBlank(message)) {
            clearValidationMessage();
            return;
        }

        validationLabel.setText(message);
        validationLabel.setVisible(true);
        revalidate();
        repaint();
    }

    public void clearValidationMessage() {
        validationLabel.setText("");
        validationLabel.setVisible(false);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        textArea.setEnabled(enabled);
        attachButton.setEnabled(enabled);
        commandCenterButton.setEnabled(enabled);
        thinkingButton.setEnabled(enabled);
        webSearchButton.setEnabled(enabled && webSearchAvailable);
        agentModeButton.setEnabled(enabled && agentModeAvailable);
        clearChatButton.setEnabled(enabled);
        applyClearChatVisibility();
        if (projectRootButton != null) {
            projectRootButton.setEnabled(enabled && agentModeAvailable && agentModeEnabled);
        }
        updateInputButtonIcons();
    }

    public void addSendListener(ActionListener listener) {
        sendListeners.add(listener);
    }

    public void addCommandCenterListener(ActionListener listener) {
        commandCenterListeners.add(listener);
    }

    public void setSendOnEnter(boolean sendOnEnter) {
        this.sendOnEnter = sendOnEnter;
    }

    public void addClearChatListener(ActionListener listener) {
        clearChatListeners.add(listener);
    }

    public void addCancelGenerationListener(ActionListener listener) {
        cancelGenerationListeners.add(listener);
    }

    public void addReasoningLevelListener(Consumer<ReasoningLevel> listener) {
        if (listener != null) {
            reasoningLevelListeners.add(listener);
        }
    }

    public void addWebSearchEnabledListener(Consumer<Boolean> listener) {
        if (listener != null) {
            webSearchEnabledListeners.add(listener);
        }
    }

    public void addWebSearchOptionListener(Consumer<String> listener) {
        if (listener != null) {
            webSearchOptionListeners.add(listener);
        }
    }

    public void addWebBrowseTopNListener(Consumer<Integer> listener) {
        if (listener != null) {
            webBrowseTopNListeners.add(listener);
        }
    }

    public boolean isThinkingEnabled() {
        return getEffectiveReasoningLevel().enabled();
    }

    public void setThinkingEnabled(boolean thinkingEnabled) {
        setReasoningLevel(thinkingEnabled ? ReasoningLevel.MEDIUM : ReasoningLevel.OFF);
    }

    public ReasoningLevel getReasoningLevel() {
        return reasoningLevel;
    }

    public ReasoningLevel getEffectiveReasoningLevel() {
        return thinkingAvailable ? reasoningLevel : ReasoningLevel.OFF;
    }

    public void setReasoningLevel(ReasoningLevel reasoningLevel) {
        ReasoningLevel normalized = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        if (this.reasoningLevel == normalized) {
            return;
        }

        this.reasoningLevel = normalized;
        updateThinkingTogglePresentation();
    }

    public void setThinkingAvailable(boolean thinkingAvailable) {
        this.thinkingAvailable = thinkingAvailable;
        updateThinkingTogglePresentation();
    }

    public boolean isThinkingAvailable() {
        return thinkingAvailable;
    }

    public boolean isWebSearchAvailable() {
        return webSearchAvailable;
    }

    public boolean isWebSearchEnabled() {
        return webSearchAvailable && webSearchEnabled;
    }

    public String getWebSearchOptionId() {
        return webSearchOptionId;
    }

    public void setWebSearchOptionId(String optionId) {
        boolean available = webSearchOptions.stream()
                .anyMatch(option -> option.available() && Strings.CS.equals(option.id(), optionId));
        if (available) {
            selectWebSearchOption(optionId, false);
        }
    }

    public void setWebSearchEnabled(boolean enabled) {
        boolean normalized = enabled && webSearchAvailable;
        if (webSearchEnabled == normalized) {
            updateWebSearchPresentation();
            return;
        }

        webSearchEnabled = normalized;
        updateWebSearchPresentation();
    }

    public void requestWebSearchEnabled(boolean enabled) {
        if (enabled && !webSearchAvailable) {
            showValidationMessage("Web Search is not available for the selected model.");
            updateWebSearchPresentation();
            return;
        }

        boolean previous = webSearchEnabled;
        setWebSearchEnabled(enabled);
        if (previous != webSearchEnabled) {
            notifyWebSearchEnabledChanged(webSearchEnabled);
        }
    }

    public void setWebSearchLockedEnabled(boolean lockedEnabled) {
        if (webSearchLockedEnabled == lockedEnabled) {
            if (webSearchLockedEnabled && webSearchAvailable && !webSearchEnabled) {
                webSearchEnabled = true;
            }
            updateWebSearchPresentation();
            return;
        }

        webSearchLockedEnabled = lockedEnabled;
        if (webSearchLockedEnabled && webSearchAvailable) {
            webSearchEnabled = true;
        }
        updateWebSearchPresentation();
    }

    public void setWebBrowseTopN(int topN) {
        webBrowseTopN = switch (topN) {
            case 1, 2, 3, 5, 10 -> topN;
            default -> 3;
        };
        rebuildWebSearchMenu();
    }

    public int getWebBrowseTopN() {
        return webBrowseTopN;
    }

    public void setWebSearchOptions(List<WebSearchOption> options, String defaultOptionId) {
        webSearchOptions = options == null ? emptyList() : List.copyOf(options);
        webSearchAvailable = webSearchOptions.stream().anyMatch(WebSearchOption::available);

        boolean currentStillAvailable = webSearchOptions.stream()
                .anyMatch(option -> option.available() && Strings.CS.equals(option.id(), webSearchOptionId));
        if (!currentStillAvailable) {
            webSearchOptionId = webSearchOptions.stream()
                    .filter(option -> option.available() && Strings.CS.equals(option.id(), defaultOptionId))
                    .findFirst()
                    .or(() -> webSearchOptions.stream().filter(WebSearchOption::available).findFirst())
                    .map(WebSearchOption::id)
                    .orElse(null);
        }

        if (!webSearchAvailable) {
            webSearchEnabled = false;
        } else if (webSearchLockedEnabled) {
            webSearchEnabled = true;
        }

        rebuildWebSearchMenu();
        updateWebSearchPresentation();
    }

    public boolean isAgentModeAvailable() {
        return agentModeAvailable;
    }

    public void setAgentModeAvailable(boolean agentModeAvailable) {
        if (this.agentModeAvailable == agentModeAvailable) {
            return;
        }

        this.agentModeAvailable = agentModeAvailable;
        updateAgentModeEffectiveState();
    }

    public boolean isAgentModeEnabled() {
        return agentModeEnabled;
    }

    public boolean isAgentModeRequested() {
        return agentModeRequested;
    }

    public void toggleAgentMode() {
        onAgentModeButtonClicked();
    }

    public void requestAgentModeEnabled(boolean enabled) {
        if (enabled && !agentModeAvailable) {
            showValidationMessage("Agent Mode is not available for the selected model.");
            updateAgentModePresentation();
            return;
        }

        if (enabled && !agentModeEnabled) {
            onAgentModeButtonClicked();
            return;
        }

        if (!enabled && agentModeEnabled) {
            setAgentModeEnabled(false);
        }
    }

    public void setAgentModeEnabled(boolean enabled) {
        this.agentModeRequested = enabled;
        updateAgentModeEffectiveState();
    }

    public Path getAgentProjectRoot() {
        return agentProjectRoot;
    }

    public void setAgentProjectRoot(Path projectRoot) {
        Path normalized = projectRoot == null ? null : projectRoot.normalize();
        if (normalized != null && !Files.isDirectory(normalized)) {
            showValidationMessage("Selected project folder no longer exists.");
            normalized = null;
        }

        if ((agentProjectRoot == null && normalized == null)
                || (agentProjectRoot != null && agentProjectRoot.equals(normalized))
        ) {
            updateProjectRootPresentation();
            return;
        }

        agentProjectRoot = normalized;
        updateAgentModeEffectiveState();
        notifyAgentProjectRootChanged(normalized);
    }

    public void addAgentModeListener(Consumer<Boolean> listener) {
        if (listener != null) {
            agentModeListeners.add(listener);
        }
    }

    public void addAgentProjectRootListener(Consumer<Path> listener) {
        if (listener != null) {
            agentProjectRootListeners.add(listener);
        }
    }

    void setProjectRootChooserForTests(ProjectRootChooser chooser) {
        if (chooser != null) {
            this.projectRootChooser = chooser;
        }
    }

    private void initializeReasoningLevelMenu() {
        ButtonGroup buttonGroup = new ButtonGroup();

        reasoningLevelItems.clear();
        for (ReasoningLevel level : List.of(
                ReasoningLevel.OFF,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
                ReasoningLevel.EXTRA_HIGH
        )) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(reasoningLabel(level));
            item.addActionListener(e -> {
                ReasoningLevel previousLevel = this.reasoningLevel;
                setReasoningLevel(level);
                if (previousLevel != this.reasoningLevel) {
                    notifyReasoningLevelChanged(this.reasoningLevel);
                }
            });
            buttonGroup.add(item);
            reasoningLevelItems.put(level, item);
            reasoningLevelMenu.add(item);
        }
    }

    private void toggleReasoningSelector() {
        if (!thinkingAvailable) {
            return;
        }

        if (reasoningLevelMenu.isVisible()) {
            reasoningLevelMenu.setVisible(false);
            return;
        }

        reasoningLevelItems.forEach((level, item) -> item.setSelected(level == reasoningLevel));
        reasoningLevelMenu.show(thinkingButton, 0, -reasoningLevelMenu.getPreferredSize().height - 4);
    }

    private String reasoningLabel(ReasoningLevel level) {
        return switch (level) {
            case OFF -> "Off";
            case LOW -> "Low";
            case MEDIUM -> "Medium";
            case HIGH -> "High";
            case EXTRA_HIGH -> "Extra High";
        };
    }

    private void onWebSearchButtonClicked() {
        if (!webSearchAvailable) {
            setWebSearchEnabled(false);
            return;
        }

        requestWebSearchEnabled(!webSearchEnabled);
        webSearchMenu.setVisible(false);
    }

    private void maybeShowWebSearchMenu(MouseEvent e) {
        if (e == null || !e.isPopupTrigger()) {
            return;
        }

        e.consume();
        showWebSearchMenu();
    }

    private void showWebSearchMenu() {
        updateWebSearchPresentation();
        if (!webSearchAvailable) {
            setWebSearchEnabled(false);
            return;
        }

        rebuildWebSearchMenu();
        if (!webSearchButton.isShowing()) {
            return;
        }
        if (!webSearchMenu.isVisible()) {
            webSearchMenu.show(webSearchButton, 0, -webSearchMenu.getPreferredSize().height - 4);
        }
    }

    private void rebuildWebSearchMenu() {
        if (webSearchMenu == null) {
            return;
        }

        webSearchMenu.removeAll();

        if (!webSearchOptions.isEmpty()) {
            JMenu searchWithMenu = PopupMenuSupport.configureNativeSafeMenu(new JMenu("Search with"));
            ButtonGroup optionGroup = new ButtonGroup();
            webSearchOptionItems.clear();
            webSearchOptions.stream()
                    .filter(WebSearchOption::available)
                    .forEach(option -> {
                        JRadioButtonMenuItem item = new JRadioButtonMenuItem(option.label());
                        item.setSelected(Strings.CS.equals(option.id(), webSearchOptionId));
                        item.addActionListener(e -> selectWebSearchOption(option.id(), true));
                        optionGroup.add(item);
                        webSearchOptionItems.put(option.id(), item);
                        searchWithMenu.add(item);
                    });
            webSearchMenu.add(searchWithMenu);
        }

        JMenu browseTopMenu = PopupMenuSupport.configureNativeSafeMenu(new JMenu("Browse top"));
        ButtonGroup browseGroup = new ButtonGroup();
        for (int topN : List.of(1, 2, 3, 5, 10)) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(String.valueOf(topN));
            item.setSelected(webBrowseTopN == topN);
            item.addActionListener(e -> {
                setWebBrowseTopN(topN);
                notifyWebBrowseTopNChanged(topN);
            });
            browseGroup.add(item);
            browseTopMenu.add(item);
        }
        webSearchMenu.add(browseTopMenu);
    }

    private void selectWebSearchOption(String optionId, boolean notify) {
        if (Strings.CS.equals(webSearchOptionId, optionId)) {
            return;
        }

        webSearchOptionId = optionId;
        webSearchOptionItems.forEach((id, item) -> item.setSelected(Strings.CS.equals(id, optionId)));
        updateWebSearchPresentation();
        if (notify) {
            notifyWebSearchOptionChanged(optionId);
        }
    }

    private void updateWebSearchPresentation() {
        if (webSearchButton == null) {
            return;
        }

        boolean selected = isEnabled() && webSearchAvailable && webSearchEnabled;
        webSearchButton.setVisible(webSearchAvailable);
        webSearchButton.setEnabled(isEnabled() && webSearchAvailable);
        webSearchButton.setSelected(selected);
        applyToolbarToggleSelection(webSearchButton, selected);
        webSearchButton.setIcon(webSearchIcon(resolveInputIconTint(isWebSearchEnabled())));
        String optionLabel = webSearchOptions.stream()
                .filter(option -> Strings.CS.equals(option.id(), webSearchOptionId))
                .findFirst()
                .map(WebSearchOption::label)
                .orElse("Web Search");
        String toggleHint = "click to toggle, right-click for options";
        webSearchButton.setToolTipText(webSearchAvailable
                ? "Web Search: %s (%s)".formatted(optionLabel, toggleHint)
                : null);
        revalidate();
        repaint();
    }

    private void onAgentModeButtonClicked() {
        if (!agentModeAvailable) {
            setAgentModeEnabled(false);
            return;
        }

        if (agentModeEnabled) {
            setAgentModeEnabled(false);
            clearValidationMessage();
            return;
        }

        Path projectRoot = agentProjectRoot;
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            Optional<Path> selectedProjectRoot = chooseProjectRoot();
            if (selectedProjectRoot.isEmpty()) {
                showValidationMessage("Select a project folder to enable Agent Mode.");
                setAgentModeEnabled(false);
                return;
            }

            setAgentProjectRoot(selectedProjectRoot.get());
        }

        clearValidationMessage();
        setAgentModeEnabled(true);
    }

    private void onProjectRootButtonClicked() {
        if (!agentModeAvailable) {
            return;
        }

        Optional<Path> selectedProjectRoot = chooseProjectRoot();
        if (selectedProjectRoot.isEmpty()) {
            return;
        }

        clearValidationMessage();
        setAgentProjectRoot(selectedProjectRoot.get());
    }

    private Optional<Path> chooseProjectRoot() {
        return projectRootChooser.choose(this)
                .map(Path::normalize)
                .filter(Files::isDirectory);
    }

    private Optional<Path> showProjectRootChooser(Component parent, Path initialDirectory) {
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle("Select project folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileHidingEnabled(false);

        File initialDirFile = initialDirectory == null
                ? null
                : initialDirectory.toFile();
        if (initialDirFile != null && initialDirFile.isDirectory()) {
            chooser.setCurrentDirectory(initialDirFile);
            chooser.setSelectedFile(initialDirFile);
        }

        int result = chooser.showOpenDialog(parent);
        if (result != SystemFileChooser.APPROVE_OPTION) {
            return Optional.empty();
        }

        File selectedFile = chooser.getSelectedFile();
        if (selectedFile == null) {
            return Optional.empty();
        }

        return Optional.of(selectedFile.toPath());
    }

    private void updateAgentModeEffectiveState() {
        boolean effectiveEnabled = agentModeRequested && agentModeAvailable && agentProjectRoot != null;
        if (this.agentModeEnabled != effectiveEnabled) {
            this.agentModeEnabled = effectiveEnabled;
            notifyAgentModeChanged(effectiveEnabled);
        }

        updateAgentModePresentation();
    }

    private void updateAgentModePresentation() {
        if (agentModeButton == null) {
            return;
        }

        boolean selected = isEnabled() && agentModeAvailable && agentModeEnabled;
        agentModeButton.setVisible(agentModeAvailable);
        agentModeButton.setEnabled(isEnabled() && agentModeAvailable);
        agentModeButton.setSelected(selected);
        applyToolbarToggleSelection(agentModeButton, selected);
        agentModeButton.setIcon(agentModeIcon(resolveInputIconTint(agentModeAvailable && agentModeEnabled)));
        agentModeButton.setToolTipText(agentModeAvailable ? "Agent mode" : null);
        updateProjectRootPresentation();
        revalidate();
        repaint();
    }

    private void updateProjectRootPresentation() {
        if (projectRootButton == null) {
            return;
        }

        boolean showProjectRoot = agentModeAvailable && agentModeEnabled && agentProjectRoot != null;
        if (!showProjectRoot) {
            if (projectRootRowPanel != null) {
                projectRootRowPanel.setVisible(false);
            }
            if (projectRootIconLabel != null) {
                projectRootIconLabel.setVisible(false);
                projectRootIconLabel.setToolTipText(null);
            }
            projectRootButton.setVisible(false);
            projectRootButton.setText("");
            projectRootButton.setToolTipText(null);
            if (agentAccessLabel != null) {
                agentAccessLabel.setVisible(false);
                agentAccessLabel.setToolTipText(null);
            }
            return;
        }

        String folderName = StringUtils.defaultIfBlank(agentProjectRoot.getFileName().toString(), agentProjectRoot.toString());
        String absolutePath = agentProjectRoot.toAbsolutePath().toString();

        if (projectRootRowPanel != null) {
            projectRootRowPanel.setVisible(true);
        }

        if (projectRootIconLabel != null) {
            projectRootIconLabel.setVisible(true);
            projectRootIconLabel.setToolTipText(absolutePath);
        }

        int maxButtonWidth = resolveProjectRootButtonMaxWidth();
        String displayName = abbreviateToWidth(folderName, projectRootButton, maxButtonWidth);
        int buttonWidth = measureButtonWidth(displayName, projectRootButton, maxButtonWidth);

        projectRootButton.setText(displayName);
        projectRootButton.setToolTipText("Selected folder: %s\nClick to change folder".formatted(absolutePath));
        projectRootButton.setPreferredSize(new Dimension(buttonWidth, PROJECT_ROOT_BUTTON_HEIGHT));
        projectRootButton.setMinimumSize(new Dimension(PROJECT_ROOT_BUTTON_MIN_WIDTH, PROJECT_ROOT_BUTTON_HEIGHT));
        projectRootButton.setMaximumSize(new Dimension(maxButtonWidth, PROJECT_ROOT_BUTTON_HEIGHT));
        projectRootButton.setVisible(true);
        projectRootButton.setEnabled(isEnabled() && agentModeAvailable);

        if (agentAccessLabel != null) {
            agentAccessLabel.setVisible(agentModeEnabled);
            agentAccessLabel.setToolTipText(agentModeEnabled
                    ? "Agent has full access to files and commands in the selected folder."
                    : null);
        }
    }

    private int resolveProjectRootButtonMaxWidth() {
        int referenceWidth = Math.max(getWidth(), composerShell == null ? 0 : composerShell.getWidth());
        if (referenceWidth <= 0) {
            referenceWidth = Math.max(getPreferredSize().width, 360);
        }

        return Math.max(PROJECT_ROOT_BUTTON_MIN_WIDTH, referenceWidth / 2);
    }

    private int measureButtonWidth(String text, JButton button, int maxButtonWidth) {
        FontMetrics metrics = button.getFontMetrics(button.getFont());
        int textWidth = metrics.stringWidth(StringUtils.defaultString(text));
        Insets insets = button.getInsets();
        int horizontalInsets = Math.max(12, insets.left + insets.right + 8);
        int targetWidth = textWidth + horizontalInsets;
        targetWidth = Math.max(PROJECT_ROOT_BUTTON_MIN_WIDTH, targetWidth);
        return Math.min(targetWidth, maxButtonWidth);
    }

    private String abbreviateToWidth(String text, JButton button, int maxButtonWidth) {
        String normalized = StringUtils.defaultString(text);
        FontMetrics metrics = button.getFontMetrics(button.getFont());
        Insets insets = button.getInsets();
        int horizontalInsets = Math.max(12, insets.left + insets.right + 8);
        int maxTextWidth = Math.max(10, maxButtonWidth - horizontalInsets);

        if (metrics.stringWidth(normalized) <= maxTextWidth) {
            return normalized;
        }

        String ellipsis = "…";
        int ellipsisWidth = metrics.stringWidth(ellipsis);
        if (ellipsisWidth >= maxTextWidth) {
            return ellipsis;
        }

        int low = 1;
        int high = normalized.length();
        int best = 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            String candidate = normalized.substring(0, mid) + ellipsis;
            if (metrics.stringWidth(candidate) <= maxTextWidth) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return normalized.substring(0, best) + ellipsis;
    }

    public void requestInputFocus() {
        Runnable request = () -> {
            if (textArea.isShowing()) {
                textArea.requestFocusInWindow();
                return;
            }

            SwingUtilities.invokeLater(textArea::requestFocusInWindow);
        };

        if (SwingUtilities.isEventDispatchThread()) {
            request.run();
        } else {
            SwingUtilities.invokeLater(request);
        }
    }

    public void setCancelGenerationVisible(boolean visible) {
        Runnable update = () -> {
            cancelGenerationButton.setVisible(visible);
            revalidate();
            repaint();
        };

        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    public boolean isCancelGenerationVisible() {
        return cancelGenerationButton.isVisible();
    }

    public void setClearChatVisible(boolean visible) {
        Runnable update = () -> {
            clearChatAvailable = visible;
            applyClearChatVisibility();
        };

        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    public boolean isClearChatVisible() {
        return clearChatButton.isVisible();
    }

    private void applyClearChatVisibility() {
        clearChatButton.setVisible(clearChatAvailable && isEnabled());
        revalidate();
        repaint();
    }

    private void fireSend() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "send");
        for (ActionListener l : sendListeners) {
            l.actionPerformed(event);
        }
    }

    private void fireCommandCenter() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "commandCenter");
        for (ActionListener l : commandCenterListeners) {
            l.actionPerformed(event);
        }
    }

    private void fireClearChat() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "clearChat");
        for (ActionListener l : clearChatListeners) {
            l.actionPerformed(event);
        }
    }

    private void fireCancelGeneration() {
        var event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "cancelGeneration");
        for (ActionListener l : cancelGenerationListeners) {
            l.actionPerformed(event);
        }
    }

    private void notifyReasoningLevelChanged(ReasoningLevel level) {
        for (Consumer<ReasoningLevel> listener : reasoningLevelListeners) {
            listener.accept(level);
        }
    }

    private void notifyWebSearchEnabledChanged(boolean enabled) {
        for (Consumer<Boolean> listener : webSearchEnabledListeners) {
            listener.accept(enabled);
        }
    }

    private void notifyWebSearchOptionChanged(String optionId) {
        for (Consumer<String> listener : webSearchOptionListeners) {
            listener.accept(optionId);
        }
    }

    private void notifyWebBrowseTopNChanged(int topN) {
        for (Consumer<Integer> listener : webBrowseTopNListeners) {
            listener.accept(topN);
        }
    }

    private void notifyAgentModeChanged(boolean enabled) {
        for (Consumer<Boolean> listener : agentModeListeners) {
            listener.accept(enabled);
        }
    }

    private void notifyAgentProjectRootChanged(Path projectRoot) {
        for (Consumer<Path> listener : agentProjectRootListeners) {
            listener.accept(projectRoot);
        }
    }

    public void requestAttachmentPicker() {
        openAttachmentPicker();
    }

    private void onInputChanged() {
        adjustHeight();
        clearValidationMessage();
        updateSlashSuggestions();
    }

    private void adjustHeight() {
        int lineCount = Math.min(textArea.getLineCount(), 5);
        textArea.setRows(Math.max(2, lineCount));
        revalidate();
    }

    private void openAttachmentPicker() {
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle("Attach files");
        chooser.setMultiSelectionEnabled(true);
        chooser.addChoosableFileFilter(new SystemFileChooser.FileNameExtensionFilter(
                "Allowed files",
                attachmentSelectionPolicy.allowedExtensions().toArray(String[]::new)
        ));

        int result = chooser.showOpenDialog(this);
        if (result != SystemFileChooser.APPROVE_OPTION) {
            return;
        }

        File[] selectedFiles = chooser.getSelectedFiles();
        List<Path> selectedPaths = new ArrayList<>();
        if (selectedFiles != null && selectedFiles.length > 0) {
            for (File selectedFile : selectedFiles) {
                selectedPaths.add(selectedFile.toPath());
            }
        } else if (chooser.getSelectedFile() != null) {
            selectedPaths.add(chooser.getSelectedFile().toPath());
        }

        addAttachments(selectedPaths);
    }

    private void addAttachments(List<Path> candidatePaths) {
        clearValidationMessage();

        candidatePaths.stream()
                .map(this::toComposerAttachment)
                .flatMap(Optional::stream)
                .filter(this::isNewAttachment)
                .forEach(attachments::add);

        refreshChips();
    }

    private Optional<ComposerAttachment> toComposerAttachment(Path path) {
        try {
            return Optional.of(attachmentSelectionPolicy.create(path));
        } catch (IllegalArgumentException e) {
            showValidationMessage(e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            showValidationMessage("Failed to read attachment: %s".formatted(path.getFileName()));
            return Optional.empty();
        }
    }

    private boolean isNewAttachment(ComposerAttachment candidate) {
        return attachments.stream().noneMatch(existing -> existing.path().equals(candidate.path()));
    }

    private void refreshChips() {
        chipsPanel.removeAll();

        Icon attachmentIcon = new FlatFileViewFileIcon();

        new ArrayList<>(attachments).forEach(attachment -> {
            Runnable onRemove = () -> {
                attachments.remove(attachment);
                refreshChips();
            };
            if (attachment.image()) {
                chipsPanel.add(createImageAttachmentChip(attachment, onRemove));
            } else {
                chipsPanel.add(createAttachmentChip(
                        ellipsize(attachment.displayName(), 26),
                        attachmentIcon,
                        onRemove
                ));
            }
        });

        new ArrayList<>(activeSkills).forEach(skill -> chipsPanel.add(createSkillChip(
                ellipsize(skill, 20),
                () -> {
                    activeSkills.remove(skill);
                    refreshChips();
                }
        )));

        chipsPanel.setVisible(!attachments.isEmpty() || !activeSkills.isEmpty());
        chipsPanel.revalidate();
        composerShell.revalidate();
        revalidate();
        chipsPanel.repaint();
    }

    private JComponent createAttachmentChip(String text, Icon leadingIcon, Runnable onRemove) {
        InputChipPanel chip = new InputChipPanel(
                this::resolveChipBackground,
                this::resolveChipBorderColor,
                this::adjustBrightness,
                CHIP_HOVER_BG_DELTA,
                CHIP_HOVER_BORDER_DELTA
        );
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 3));

        JLabel label = new JLabel(text, leadingIcon, SwingConstants.LEADING);
        Fonts.apply(label, Font.PLAIN, Fonts.SIZE_COMPACT);
        label.setForeground(UIManager.getColor("Label.foreground"));
        label.setIconTextGap(4);
        chip.add(label);

        JButton remove = createChipRemoveButton(onRemove);
        chip.add(remove);

        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                chip.setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                chip.setHovered(false);
            }
        });

        return chip;
    }

    private JComponent createImageAttachmentChip(ComposerAttachment attachment, Runnable onRemove) {
        InputChipPanel chip = new InputChipPanel(
                this::resolveChipBackground,
                this::resolveChipBorderColor,
                this::adjustBrightness,
                CHIP_HOVER_BG_DELTA,
                CHIP_HOVER_BORDER_DELTA
        );
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chip.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));

        RoundedImageIcon thumbnail = new RoundedImageIcon(attachment.path(), 36);
        JLabel thumbLabel;
        if (thumbnail.hasImage()) {
            thumbLabel = new JLabel(thumbnail);
        } else {
            thumbLabel = new JLabel(new FlatFileViewFileIcon());
        }
        chip.add(thumbLabel);

        JPanel textStack = new JPanel();
        textStack.setOpaque(false);
        textStack.setLayout(new BoxLayout(textStack, BoxLayout.Y_AXIS));

        JLabel nameLabel = new JLabel(ellipsize(attachment.displayName(), 22));
        Fonts.apply(nameLabel, Font.PLAIN, Fonts.SIZE_COMPACT);
        nameLabel.setForeground(UIManager.getColor("Label.foreground"));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textStack.add(nameLabel);

        JLabel sizeLabel = new JLabel(formatSize(attachment.sizeBytes()));
        Fonts.apply(sizeLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        Color disabled = UIManager.getColor("Label.disabledForeground");
        if (disabled == null) {
            disabled = UIManager.getColor("Label.foreground");
        }
        sizeLabel.setForeground(disabled);
        sizeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        textStack.add(sizeLabel);

        chip.add(textStack);

        JButton remove = createChipRemoveButton(onRemove);
        chip.add(remove);

        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                chip.setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                chip.setHovered(false);
            }
        });

        chip.setToolTipText(attachment.displayName());
        return chip;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1_000) {
            return "%d B".formatted(bytes);
        }
        if (bytes < 1_000_000) {
            return "%.1f kB".formatted(bytes / 1_000.0);
        }
        if (bytes < 1_000_000_000) {
            return "%.1f MB".formatted(bytes / 1_000_000.0);
        }
        return "%.1f GB".formatted(bytes / 1_000_000_000.0);
    }

    private JComponent createSkillChip(String text, Runnable onRemove) {
        SkillChipPanel chip = new SkillChipPanel(
                this::resolveSkillChipBackground,
                this::resolveSkillChipBorder
        );
        chip.setLayout(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chip.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 8));

        JLabel label = new JLabel(text);
        Fonts.apply(label, Font.PLAIN, Fonts.SIZE_BODY_LARGE);
        label.setForeground(resolveSkillChipForeground());
        chip.add(label);

        JButton remove = createSkillRemoveButton(onRemove);
        chip.add(remove);

        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                chip.setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                chip.setHovered(false);
            }
        });

        return chip;
    }

    private JButton createChipRemoveButton(Runnable onRemove) {
        JButton remove = new HoverRoundButton(18, 8, this::resolveChipRemoveHoverBackground);
        remove.setIcon(svgIcon("/icons/input/x.svg", CHIP_ICON_SIZE, resolveChipRemoveForeground()));
        remove.setRolloverIcon(svgIcon("/icons/input/x.svg", CHIP_ICON_SIZE, resolveChipRemoveHoverForeground()));
        remove.setToolTipText("Remove");
        remove.addActionListener(e -> onRemove.run());
        return remove;
    }

    private JButton createSkillRemoveButton(Runnable onRemove) {
        JButton remove = new HoverRoundButton(20, 9, this::resolveSkillRemoveHoverBackground);
        Color foreground = resolveSkillChipForeground();
        remove.setIcon(svgIcon("/icons/input/x.svg", 14, foreground));
        remove.setRolloverIcon(svgIcon("/icons/input/x.svg", 14, resolveSkillRemoveHoverForeground()));
        remove.setToolTipText("Remove skill");
        remove.addActionListener(e -> onRemove.run());
        return remove;
    }

    private String ellipsize(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }

        return "%s…".formatted(trimmed.substring(0, Math.max(0, maxLength - 1)));
    }

    private JPopupMenu createEditContextMenu(JTextComponent target) {
        int shortcut = menuShortcutKeyMask();

        JMenuItem cutItem = buildEditMenuItem(
                target,
                "Cut",
                DefaultEditorKit.cutAction,
                KeyStroke.getKeyStroke(KeyEvent.VK_X, shortcut),
                "/icons/input/scissors.svg"
        );
        JMenuItem copyItem = buildEditMenuItem(
                target,
                "Copy",
                DefaultEditorKit.copyAction,
                KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcut),
                "/icons/input/copy.svg"
        );
        JMenuItem pasteItem = buildEditMenuItem(
                target,
                "Paste",
                DefaultEditorKit.pasteAction,
                KeyStroke.getKeyStroke(KeyEvent.VK_V, shortcut),
                "/icons/input/clipboard-paste.svg"
        );

        JPopupMenu popup = PopupMenuSupport.configureNativeSafePopup(new JPopupMenu());
        popup.add(cutItem);
        popup.add(copyItem);
        popup.add(pasteItem);

        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean hasSelection = target.getSelectionStart() != target.getSelectionEnd();
                boolean editable = target.isEditable() && target.isEnabled();
                cutItem.setEnabled(editable && hasSelection);
                copyItem.setEnabled(hasSelection);
                pasteItem.setEnabled(editable);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });

        return popup;
    }

    private int menuShortcutKeyMask() {
        return GraphicsEnvironment.isHeadless()
                ? InputEvent.CTRL_DOWN_MASK
                : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    private JMenuItem buildEditMenuItem(
            JTextComponent target,
            String label,
            String actionName,
            KeyStroke accelerator,
            String iconPath
    ) {
        Action action = target.getActionMap().get(actionName);
        JMenuItem item = new JMenuItem(label);
        Fonts.apply(item, Font.PLAIN, Fonts.SIZE_BODY);
        item.setIcon(svgIcon(iconPath, MENU_ICON_SIZE, UIManager.getColor("Label.foreground")));
        item.setAccelerator(accelerator);
        if (action != null) {
            item.addActionListener(e -> {
                ActionEvent forwarded = new ActionEvent(target, ActionEvent.ACTION_PERFORMED, actionName);
                action.actionPerformed(forwarded);
            });
        }
        return item;
    }

    private void configureSlashPopup() {
        slashSuggestionsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Fonts.apply(slashSuggestionsList, Font.PLAIN, Fonts.SIZE_BODY);
        slashSuggestionsList.setVisibleRowCount(5);
        slashSuggestionsList.setFixedCellHeight(46);
        slashSuggestionsList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JPanel row = new JPanel(new BorderLayout(14, 0));
            row.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 12));

            SkillBadgeLabel badge = new SkillBadgeLabel(
                    this::resolveSkillBadgeBackground,
                    this::resolveSkillBadgeBorder
            );
            badge.setColors(
                    resolveSkillBadgeBackground(),
                    resolveSkillBadgeBorder(),
                    resolveSkillBadgeForeground()
            );
            JPanel badgeContainer = new JPanel(new GridBagLayout());
            badgeContainer.setOpaque(false);
            badgeContainer.add(badge);
            row.add(badgeContainer, BorderLayout.WEST);

            JPanel textStack = new JPanel();
            textStack.setOpaque(false);
            textStack.setLayout(new BoxLayout(textStack, BoxLayout.Y_AXIS));

            JLabel title = new JLabel(value.name());
            Fonts.apply(title, Font.BOLD, Fonts.SIZE_BODY_LARGE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            textStack.add(title);

            JLabel desc = new JLabel(value.description());
            Fonts.apply(desc, Font.PLAIN, Fonts.SIZE_BODY);
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            textStack.add(desc);
            row.add(textStack, BorderLayout.CENTER);

            row.setBackground(isSelected ? resolveSkillPopupSelectionBackground(list.getBackground()) : list.getBackground());
            title.setForeground(resolveSkillPopupTitleForeground(list));
            desc.setForeground(resolveMutedForeground(list));
            row.setOpaque(true);
            return row;
        });

        slashSuggestionsList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2) {
                    applySelectedSlashSkill();
                }
            }
        });

        slashSuggestionsList.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "selectSkill");
        slashSuggestionsList.getActionMap().put("selectSkill", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                applySelectedSlashSkill();
            }
        });

        slashPopupMenu.setBorder(null);
        slashPopupMenu.setOpaque(false);
        slashPopupMenu.setFocusable(false);
        slashPopupScrollPane = new JScrollPane(slashSuggestionsList);
        slashPopupScrollPane.setBorder(null);
        slashPopupScrollPane.setViewportBorder(null);
        slashPopupScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        slashPopupScrollPane.setPreferredSize(new Dimension(320, 220));
        slashPopupContentPanel.setLayout(new BorderLayout());
        slashPopupContentPanel.setOpaque(true);
        slashPopupContentPanel.add(slashPopupScrollPane, BorderLayout.CENTER);
        slashPopupMenu.add(slashPopupContentPanel);
        applySlashPopupBorder();
    }

    private void refreshSkills() {
        Map<String, SkillCommand> byName = new LinkedHashMap<>();
        skillSearchPaths().forEach(path -> loadSkills(path).forEach(skill -> byName.putIfAbsent(skill.name(), skill)));
        availableSkills.clear();
        availableSkills.addAll(byName.values());
    }

    private List<Path> skillSearchPaths() {
        List<Path> paths = new ArrayList<>();
        String userDir = System.getProperty("user.dir");
        if (StringUtils.isNotBlank(userDir)) {
            paths.add(Path.of(userDir, ".agents", "skills"));
        }

        String userHome = System.getProperty("user.home");
        if (StringUtils.isNotBlank(userHome)) {
            paths.add(Path.of(userHome, ".agents", "skills"));
        }

        return paths;
    }

    private List<SkillCommand> loadSkills(Path skillRoot) {
        if (!Files.isDirectory(skillRoot)) {
            return emptyList();
        }

        List<SkillCommand> skills = new ArrayList<>();
        try (var stream = Files.list(skillRoot)) {
            stream.filter(Files::isDirectory)
                    .map(dir -> dir.resolve("SKILL.md"))
                    .filter(Files::isRegularFile)
                    .map(this::parseSkillFile)
                    .flatMap(Optional::stream)
                    .forEach(skills::add);
        } catch (IOException e) {
            return emptyList();
        }

        return skills;
    }

    private Optional<SkillCommand> parseSkillFile(Path file) {
        String fallbackName = file.getParent() == null ? "skill" : file.getParent().getFileName().toString();
        String name = fallbackName;
        String description = "";

        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if ("---".equals(trimmed) && i > 0) {
                    break;
                }

                if (trimmed.startsWith("name:")) {
                    name = normalizeYamlScalar(trimmed.substring("name:".length()));
                } else if (trimmed.startsWith("description:")) {
                    String rawDescription = trimmed.substring("description:".length()).trim();
                    description = rawDescription.startsWith("|") || rawDescription.startsWith(">")
                            ? parseYamlBlockScalar(lines, i + 1, leadingWhitespace(line))
                            : normalizeYamlScalar(rawDescription);
                }

                if (!description.isBlank() && !name.isBlank()) {
                    break;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }

        String finalDescription = description.isBlank() ? "No description" : description;
        return Optional.of(new SkillCommand(name, finalDescription));
    }

    private String parseYamlBlockScalar(List<String> lines, int startIndex, int parentIndent) {
        List<String> blockLines = new ArrayList<>();
        for (int i = startIndex; i < lines.size(); i++) {
            String line = lines.get(i);
            if ("---".equals(line.trim())) {
                break;
            }
            if (StringUtils.isNotBlank(line) && leadingWhitespace(line) <= parentIndent) {
                break;
            }
            blockLines.add(line);
        }

        int contentIndent = blockLines.stream()
                .filter(StringUtils::isNotBlank)
                .mapToInt(this::leadingWhitespace)
                .min()
                .orElse(parentIndent + 1);

        String text = blockLines.stream()
                .map(line -> line.length() >= contentIndent ? line.substring(contentIndent) : line.trim())
                .reduce("", (left, right) -> "%s %s".formatted(left, right));
        return normalizeYamlScalar(text);
    }

    private String normalizeYamlScalar(String value) {
        String normalized = StringUtils.normalizeSpace(value);
        if (normalized.length() >= 2) {
            char first = normalized.charAt(0);
            char last = normalized.charAt(normalized.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return normalized.substring(1, normalized.length() - 1).trim();
            }
        }
        return normalized;
    }

    private int leadingWhitespace(String value) {
        int count = 0;
        while (count < value.length() && Character.isWhitespace(value.charAt(count))) {
            count++;
        }
        return count;
    }

    private void updateSlashSuggestions() {
        SlashToken slashToken = detectSlashToken();
        if (slashToken == null) {
            hideSlashPopup();
            return;
        }

        List<SkillCommand> matches = availableSkills.stream()
                .filter(skill -> slashToken.query().isBlank()
                        || skill.name().toLowerCase().contains(slashToken.query().toLowerCase())
                        || skill.description().toLowerCase().contains(slashToken.query().toLowerCase()))
                .limit(20)
                .toList();

        if (matches.isEmpty()) {
            hideSlashPopup();
            return;
        }

        slashSuggestions.clear();
        matches.forEach(slashSuggestions::addElement);
        slashSuggestionsList.setSelectedIndex(0);
        slashTokenStart = slashToken.startIndex();
        showSlashPopup();
    }

    private SlashToken detectSlashToken() {
        int caret = textArea.getCaretPosition();
        String text = textArea.getText();
        if (caret <= 0 || caret > text.length()) {
            return null;
        }

        int tokenStart = caret - 1;
        while (tokenStart >= 0 && !Character.isWhitespace(text.charAt(tokenStart))) {
            tokenStart--;
        }
        tokenStart += 1;

        String token = text.substring(tokenStart, caret);
        if (!token.startsWith("/")) {
            return null;
        }

        return new SlashToken(tokenStart, token.substring(1));
    }

    private void showSlashPopup() {
        if (slashSuggestions.isEmpty()) {
            return;
        }

        int popupWidth = Math.max(240, (int) (getWidth() * 0.8));
        int visibleRows = Math.max(1, Math.min(slashSuggestionsList.getVisibleRowCount(), slashSuggestions.size()));
        int rowHeight = Math.max(1, slashSuggestionsList.getFixedCellHeight());
        int popupHeight = (visibleRows * rowHeight) + 2;
        if (!isShowing()) {
            return;
        }

        int decorationInset = slashPopupContentPanel.decorationInset();
        slashPopupContentPanel.setPreferredSize(new Dimension(
                popupWidth + (decorationInset * 2),
                popupHeight + (decorationInset * 2)
        ));
        applySlashPopupBorder();

        int popupX = Math.max(6, (getWidth() - popupWidth) / 2) - decorationInset;
        int popupY = -popupHeight - 8 - decorationInset;
        slashPopupMenu.setPopupSize(
                popupWidth + (decorationInset * 2),
                popupHeight + (decorationInset * 2)
        );
        slashPopupMenu.show(this, popupX, popupY);
        installSlashPopupOutsideClickListener();
    }

    private void hideSlashPopup() {
        slashTokenStart = -1;
        slashPopupMenu.setVisible(false);
        uninstallSlashPopupOutsideClickListener();
    }

    private boolean isSlashPopupVisible() {
        return slashPopupMenu.isVisible();
    }

    private void installSlashPopupOutsideClickListener() {
        if (slashPopupOutsideClickListenerInstalled) {
            return;
        }

        if (slashPopupOutsideClickListener == null) {
            slashPopupOutsideClickListener = event -> {
                if (!(event instanceof MouseEvent mouseEvent)
                        || mouseEvent.getID() != MouseEvent.MOUSE_PRESSED
                        || !isSlashPopupVisible()) {
                    return;
                }

                Component source = mouseEvent.getComponent();
                if (source != null && (SwingUtilities.isDescendingFrom(source, slashPopupContentPanel)
                        || SwingUtilities.isDescendingFrom(source, this))) {
                    return;
                }

                hideSlashPopup();
            };
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(slashPopupOutsideClickListener, AWTEvent.MOUSE_EVENT_MASK);
        slashPopupOutsideClickListenerInstalled = true;
    }

    private void uninstallSlashPopupOutsideClickListener() {
        if (!slashPopupOutsideClickListenerInstalled || slashPopupOutsideClickListener == null) {
            return;
        }

        Toolkit.getDefaultToolkit().removeAWTEventListener(slashPopupOutsideClickListener);
        slashPopupOutsideClickListenerInstalled = false;
    }

    private boolean handleSlashPopupKey(KeyEvent e) {
        if (!isSlashPopupVisible()) {
            return false;
        }

        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP -> {
                e.consume();
                int index = Math.max(0, slashSuggestionsList.getSelectedIndex() - 1);
                slashSuggestionsList.setSelectedIndex(index);
                slashSuggestionsList.ensureIndexIsVisible(index);
                return true;
            }
            case KeyEvent.VK_DOWN -> {
                e.consume();
                int index = Math.min(slashSuggestions.size() - 1, slashSuggestionsList.getSelectedIndex() + 1);
                slashSuggestionsList.setSelectedIndex(index);
                slashSuggestionsList.ensureIndexIsVisible(index);
                return true;
            }
            case KeyEvent.VK_ENTER -> {
                e.consume();
                applySelectedSlashSkill();
                return true;
            }
            case KeyEvent.VK_ESCAPE -> {
                e.consume();
                hideSlashPopup();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void applySelectedSlashSkill() {
        SkillCommand selected = slashSuggestionsList.getSelectedValue();
        if (selected == null) {
            hideSlashPopup();
            return;
        }

        removeSlashTokenFromText();
        if (activeSkills.stream().noneMatch(skill -> Strings.CI.equals(skill, selected.name()))) {
            activeSkills.add(selected.name());
            refreshChips();
        }
        hideSlashPopup();
    }

    private void removeSlashTokenFromText() {
        int caret = textArea.getCaretPosition();
        if (slashTokenStart < 0 || slashTokenStart > caret) {
            return;
        }

        String current = textArea.getText();
        String before = current.substring(0, slashTokenStart);
        String after = current.substring(caret);

        boolean needsSeparator = !before.isEmpty()
                && !Character.isWhitespace(before.charAt(before.length() - 1))
                && !after.isEmpty()
                && !Character.isWhitespace(after.charAt(0));
        String separator = needsSeparator ? " " : "";

        String updated = before + separator + after;
        textArea.setText(updated);
        textArea.setCaretPosition(Math.min(before.length() + separator.length(), updated.length()));
    }

    private void refreshDetachedPopupUis() {
        refreshDetachedPopupUi(reasoningLevelMenu);
        refreshDetachedPopupUi(webSearchMenu);
        refreshSlashPopupUi();
        if (textArea != null) {
            refreshDetachedPopupUi(textArea.getComponentPopupMenu());
        }
    }

    private void refreshDetachedPopupUi(JPopupMenu popupMenu) {
        if (popupMenu == null) {
            return;
        }

        SwingUtilities.updateComponentTreeUI(popupMenu);
        popupMenu.invalidate();
        popupMenu.validate();
        popupMenu.repaint();
    }

    private void refreshSlashPopupUi() {
        if (slashPopupContentPanel == null) {
            return;
        }

        SwingUtilities.updateComponentTreeUI(slashPopupMenu);
        SwingUtilities.updateComponentTreeUI(slashPopupContentPanel);
        applySlashPopupBorder();
    }

    private void applySlashPopupBorder() {
        if (slashPopupContentPanel == null) {
            return;
        }

        slashPopupMenu.setBorder(null);
        slashPopupMenu.setOpaque(false);
        slashPopupContentPanel.applyThemeBorder();
        if (slashPopupScrollPane != null) {
            slashPopupScrollPane.setBorder(null);
            slashPopupScrollPane.setViewportBorder(null);
            slashPopupScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        }
    }

    private void applyThemeStyles() {
        setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 16));
        applySlashPopupBorder();

        if (scrollPane != null) {
            scrollPane.setBorder(null);
            scrollPane.setViewportBorder(null);
            scrollPane.putClientProperty(FlatClientProperties.STYLE, "border:0,0,0,0");
            if (scrollPane.getViewport() != null) {
                scrollPane.getViewport().setBorder(null);
                scrollPane.getViewport().setOpaque(false);
            }
        }

        if (textArea != null) {
            Color textColor = UIManager.getColor("TextArea.foreground");
            if (textColor == null) {
                textColor = UIManager.getColor("Label.foreground");
            }
            textArea.setForeground(textColor);
            textArea.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            textArea.setMargin(new Insets(0, 0, 0, 0));
            textArea.setOpaque(false);
            textArea.putClientProperty(FlatClientProperties.STYLE, "border:0,0,0,0;background:null");
            textArea.putClientProperty("JComponent.outline", null);
        }

        Color attachColor = UIManager.getColor("Label.foreground");
        if (attachColor == null) {
            attachColor = new Color(120, 120, 120);
        }

        updateInputButtonIcons();

        Color mutedText = UIManager.getColor("Label.disabledForeground");
        if (mutedText == null) {
            mutedText = attachColor;
        }

        if (projectRootIconLabel != null) {
            projectRootIconLabel.setForeground(mutedText);
        }

        if (projectRootButton != null) {
            projectRootButton.setForeground(mutedText);
        }

        if (agentAccessLabel != null) {
            Color accessColor = UIManager.getColor("Component.warning.focusedBorderColor");
            if (accessColor == null) {
                accessColor = new Color(226, 97, 22);
            }
            agentAccessLabel.setForeground(accessColor);
        }

        if (validationLabel != null) {
            Color errorColor = UIManager.getColor("Component.error.focusedBorderColor");
            if (errorColor == null) {
                errorColor = new Color(190, 58, 58);
            }
            validationLabel.setForeground(errorColor);
        }
    }

    private void updateInputButtonIcons() {
        Color tint = resolveInputIconTint(false);
        if (attachButton != null) {
            attachButton.setForeground(tint);
            attachButton.setIcon(attachIcon(tint));
        }
        if (commandCenterButton != null) {
            commandCenterButton.setForeground(tint);
            commandCenterButton.setIcon(commandCenterIcon(tint));
        }
        if (thinkingButton != null) {
            thinkingButton.setForeground(tint);
            updateThinkingTogglePresentation();
        }
        if (webSearchButton != null) {
            webSearchButton.setForeground(tint);
            updateWebSearchPresentation();
        }
        if (agentModeButton != null) {
            agentModeButton.setForeground(tint);
            updateAgentModePresentation();
        }
        if (clearChatButton != null) {
            clearChatButton.setForeground(tint);
            clearChatButton.setIcon(clearChatIcon(tint));
        }
        if (cancelGenerationButton != null) {
            Color cancelTint = enabledInputIconTint();
            cancelGenerationButton.setForeground(cancelTint);
            cancelGenerationButton.setIcon(stopIcon(cancelTint));
        }
    }

    private Color resolveInputIconTint(boolean active) {
        Color inactive = baseInputIconTint();
        if (!isEnabled() || !active) {
            return inactive;
        }
        return resolveThinkingActiveTint(inactive);
    }

    private Color baseInputIconTint() {
        Color tint = isEnabled() ? UIManager.getColor("Label.foreground") : UIManager.getColor("Label.disabledForeground");
        if (tint == null) {
            tint = enabledInputIconTint();
        }
        return tint;
    }

    private Color enabledInputIconTint() {
        Color tint = UIManager.getColor("Label.foreground");
        return tint == null ? new Color(120, 120, 120) : tint;
    }

    private void paintInputIconButtonBackground(Graphics g, AbstractButton button) {
        Color fill = resolveInputIconButtonBackground(button);
        if (fill == null) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(fill);
        g2.fillRoundRect(1, 1, button.getWidth() - 2, button.getHeight() - 2,
                INPUT_ICON_BUTTON_ARC, INPUT_ICON_BUTTON_ARC);
        g2.dispose();
    }

    private Color resolveInputIconButtonBackground(AbstractButton button) {
        ButtonModel model = button.getModel();
        if (isInputIconButtonActive(button)) {
            Color background = button.getBackground();
            if (background == null) {
                background = resolveThinkingSelectedBackground();
            }
            return background;
        }

        if (model.isPressed()) {
            return resolveToolbarButtonPressedBackground();
        }

        if (model.isRollover()) {
            return resolveToolbarButtonHoverBackground();
        }

        return null;
    }

    private boolean isInputIconButtonActive(AbstractButton button) {
        Object active = button.getClientProperty(INPUT_ICON_BUTTON_ACTIVE);
        return Boolean.TRUE.equals(active) || button.isSelected();
    }

    private Color resolveToolbarButtonHoverBackground() {
        Color background = ObjectUtils.firstNonNull(
                UIManager.getColor("Button.toolbar.hoverBackground"),
                UIManager.getColor("Button.hoverBackground"),
                UIManager.getColor("Component.hoverColor")
        );

        Color base = resolveChipBackground();
        if (background == null || colorDistance(background, base) < 18) {
            background = blendColors(base, enabledInputIconTint(), isDark(base) ? 0.20f : 0.08f);
        }
        return background;
    }

    private Color resolveToolbarButtonPressedBackground() {
        Color background = UIManager.getColor("Button.toolbar.pressedBackground");
        if (background == null) {
            background = UIManager.getColor("Button.pressedBackground");
        }

        Color base = resolveChipBackground();
        if (background == null || colorDistance(background, base) < 22) {
            background = blendColors(base, enabledInputIconTint(), isDark(base) ? 0.28f : 0.13f);
        }
        return background;
    }

    private Color resolveChipRemoveForeground() {
        Color foreground = UIManager.getColor("Label.foreground");
        if (foreground == null) {
            foreground = new Color(120, 120, 120);
        }
        return foreground;
    }

    private Color resolveChipRemoveHoverForeground() {
        Color background = resolveChipBackground();
        return isDark(background) ? Color.WHITE : Color.BLACK;
    }

    private Color resolveChipRemoveHoverBackground() {
        Color chipBackground = resolveChipBackground();
        Color foreground = resolveChipRemoveForeground();
        return blendColors(chipBackground, foreground, isDark(chipBackground) ? 0.24f : 0.10f);
    }

    private Color resolveSkillChipBackground(boolean hovered) {
        Color base = resolveChipBackground();
        Color fill = isDark(base) ? blendColors(base, skillGreen(), hovered ? 0.34f : 0.26f) : new Color(214, 250, 232);
        return hovered ? adjustBrightness(fill, 0.05f) : fill;
    }

    private Color resolveSkillChipBorder(boolean hovered) {
        Color base = resolveChipBackground();
        Color border = isDark(base) ? blendColors(skillGreen(), Color.WHITE, 0.18f) : new Color(125, 239, 183);
        return hovered ? adjustBrightness(border, 0.04f) : border;
    }

    private Color resolveSkillChipForeground() {
        return isDark(resolveChipBackground()) ? new Color(181, 255, 218) : new Color(0, 95, 70);
    }

    private Color resolveSkillRemoveHoverForeground() {
        return isDark(resolveChipBackground()) ? Color.WHITE : new Color(0, 74, 55);
    }

    private Color resolveSkillRemoveHoverBackground() {
        Color chipBackground = resolveSkillChipBackground(false);
        Color foreground = resolveSkillChipForeground();
        return blendColors(chipBackground, foreground, isDark(chipBackground) ? 0.22f : 0.12f);
    }

    private Color resolveSkillBadgeBackground() {
        Color listBackground = resolveListBackground();
        return isDark(listBackground) ? blendColors(listBackground, skillGreen(), 0.30f) : new Color(219, 252, 235);
    }

    private Color resolveSkillBadgeBorder() {
        return isDark(resolveListBackground()) ? blendColors(skillGreen(), Color.WHITE, 0.16f) : new Color(132, 242, 190);
    }

    private Color resolveSkillBadgeForeground() {
        return isDark(resolveListBackground()) ? new Color(181, 255, 218) : new Color(0, 95, 70);
    }

    private Color resolveSkillPopupSelectionBackground(Color listBackground) {
        Color background = listBackground == null ? resolveListBackground() : listBackground;
        Color foreground = resolveContrastingForeground(background);
        return blendColors(background, foreground, isDark(background) ? 0.12f : 0.07f);
    }

    private Color resolveSkillPopupTitleForeground(JList<?> list) {
        Color foreground = list.getForeground();
        if (foreground == null) {
            foreground = UIManager.getColor("Label.foreground");
        }
        return foreground == null ? resolveContrastingForeground(resolveListBackground()) : foreground;
    }

    private Color resolveMutedForeground(JList<?> list) {
        Color foreground = UIManager.getColor("Label.disabledForeground");
        if (foreground == null) {
            foreground = list.getForeground();
        }
        return foreground == null ? new Color(110, 110, 120) : foreground;
    }

    private Color resolveListBackground() {
        Color background = ObjectUtils.firstNonNull(
                UIManager.getColor("List.background"),
                UIManager.getColor("PopupMenu.background"),
                UIManager.getColor("Panel.background")
        );
        return background == null ? Color.WHITE : background;
    }

    private Color resolvePopupBorderColor() {
        Color background = resolveListBackground();
        Color foreground = resolveContrastingForeground(background);
        return blendColors(background, foreground, isDark(background) ? 0.18f : 0.12f);
    }

    private Color resolveContrastingForeground(Color background) {
        return isDark(background) ? Color.WHITE : Color.BLACK;
    }

    private boolean isDark(Color color) {
        return color != null && relativeLuminance(color) < 0.45d;
    }

    private Color skillGreen() {
        return new Color(16, 185, 129);
    }

    private Color blendColors(Color base, Color overlay, float ratio) {
        float clamped = Math.max(0f, Math.min(1f, ratio));
        int red = Math.round(base.getRed() * (1f - clamped) + overlay.getRed() * clamped);
        int green = Math.round(base.getGreen() * (1f - clamped) + overlay.getGreen() * clamped);
        int blue = Math.round(base.getBlue() * (1f - clamped) + overlay.getBlue() * clamped);
        return new Color(red, green, blue);
    }

    private Color resolveChipBackground() {
        Color background = ObjectUtils.firstNonNull(
                UIManager.getColor("TextField.background"),
                UIManager.getColor("Panel.background"),
                getBackground()
        );
        return background == null ? new Color(90, 90, 90) : background;
    }

    private Color resolveChipBorderColor(Color background) {
        Color border = ObjectUtils.firstNonNull(
                UIManager.getColor("TextField.borderColor"),
                UIManager.getColor("Component.borderColor"),
                UIManager.getColor("Separator.foreground"),
                new Color(150, 150, 150)
        );

        if (colorDistance(background, border) < 24) {
            border = adjustBrightness(background, 0.22f);
        }

        return border;
    }

    private int colorDistance(Color c1, Color c2) {
        return Math.abs(c1.getRed() - c2.getRed())
                + Math.abs(c1.getGreen() - c2.getGreen())
                + Math.abs(c1.getBlue() - c2.getBlue());
    }

    private double contrastRatio(Color c1, Color c2) {
        double l1 = relativeLuminance(c1);
        double l2 = relativeLuminance(c2);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    private double relativeLuminance(Color color) {
        double red = linearized(color.getRed() / 255.0d);
        double green = linearized(color.getGreen() / 255.0d);
        double blue = linearized(color.getBlue() / 255.0d);
        return 0.2126d * red + 0.7152d * green + 0.0722d * blue;
    }

    private double linearized(double channel) {
        return channel <= 0.03928d ? channel / 12.92d : Math.pow((channel + 0.055d) / 1.055d, 2.4d);
    }

    private Color adjustBrightness(Color color, float amount) {
        if (color == null) {
            return null;
        }

        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float brightness = Math.max(0f, Math.min(1f, hsb[2] + (hsb[2] < 0.5f ? amount : -amount / 2f)));
        float saturation = Math.max(0f, Math.min(1f, hsb[1] + (hsb[2] < 0.5f ? -amount / 2f : amount / 3f)));
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }

    private void updateThinkingTogglePresentation() {
        if (thinkingButton == null) {
            return;
        }

        boolean reasoningEnabled = reasoningLevel.enabled();

        thinkingButton.setVisible(thinkingAvailable);
        if (!thinkingAvailable) {
            thinkingButton.setSelected(false);
            applyToolbarToggleSelection(thinkingButton, false);
            thinkingButton.setToolTipText(null);
            reasoningLevelMenu.setVisible(false);
            revalidate();
            repaint();
            return;
        }

        Color tint = resolveInputIconTint(reasoningEnabled);
        boolean selected = isEnabled() && reasoningEnabled;
        thinkingButton.setSelected(selected);
        applyToolbarToggleSelection(thinkingButton, selected);
        thinkingButton.setIcon(thinkingIcon(tint));
        thinkingButton.setToolTipText("Reasoning");
        reasoningLevelItems.forEach((level, item) -> item.setSelected(level == reasoningLevel));
        revalidate();
        repaint();
    }

    private void applyToolbarToggleSelection(AbstractButton button, boolean selected) {
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.putClientProperty(INPUT_ICON_BUTTON_ACTIVE, selected);
        button.setBackground(selected ? resolveThinkingSelectedBackground() : null);
    }

    private Color resolveThinkingActiveTint(Color inactiveTint) {
        Color preferred = ObjectUtils.firstNonNull(
                UIManager.getColor("Button.toolbar.selectedForeground"),
                UIManager.getColor("ToggleButton.selectedForeground"),
                UIManager.getColor("Button.selectedForeground"),
                UIManager.getColor("Component.accentColor"),
                new Color(84, 142, 255)
        );

        Color selectedBackground = resolveThinkingSelectedBackground();
        if (selectedBackground == null) {
            return preferred;
        }

        Color candidate = preferred;
        double bestContrast = contrastRatio(candidate, selectedBackground);

        double inactiveContrast = contrastRatio(inactiveTint, selectedBackground);
        if (inactiveContrast > bestContrast) {
            candidate = inactiveTint;
            bestContrast = inactiveContrast;
        }

        double whiteContrast = contrastRatio(Color.WHITE, selectedBackground);
        if (whiteContrast > bestContrast) {
            candidate = Color.WHITE;
            bestContrast = whiteContrast;
        }

        double blackContrast = contrastRatio(Color.BLACK, selectedBackground);
        if (blackContrast > bestContrast) {
            candidate = Color.BLACK;
        }

        return candidate;
    }

    private Color resolveThinkingSelectedBackground() {
        Color background = ObjectUtils.firstNonNull(
                UIManager.getColor("Button.toolbar.selectedBackground"),
                UIManager.getColor("ToggleButton.selectedBackground"),
                UIManager.getColor("Button.selectedBackground"),
                UIManager.getColor("Component.accentColor")
        );
        return background;
    }

    private Icon attachIcon(Color tint) {
        Icon icon = svgIcon("/icons/input/paperclip.svg", ATTACH_ICON_SIZE, tint);
        return icon != null ? icon : new FlatFileViewFileIcon();
    }

    private Icon commandCenterIcon(Color tint) {
        Icon icon = svgIcon("/icons/input/command.svg", COMMAND_CENTER_ICON_SIZE, tint);
        return icon != null ? icon : UIManager.getIcon("OptionPane.informationIcon");
    }

    private Icon thinkingIcon(Color tint) {
        Icon icon = svgIcon("/icons/sidebar/brain.svg", THINKING_ICON_SIZE, tint);
        return icon != null ? icon : UIManager.getIcon("OptionPane.questionIcon");
    }

    private Icon webSearchIcon(Color tint) {
        Icon icon = svgIcon("/icons/input/globe.svg", WEB_SEARCH_ICON_SIZE, tint);
        return icon != null ? icon : UIManager.getIcon("OptionPane.informationIcon");
    }

    private Icon agentModeIcon(Color tint) {
        Icon icon = svgIcon("/icons/input/agent.svg", AGENT_ICON_SIZE, tint);
        return icon != null ? icon : UIManager.getIcon("OptionPane.informationIcon");
    }

    private Icon clearChatIcon(Color tint) {
        Icon icon = svgIcon("/icons/input/eraser.svg", CLEAR_CHAT_ICON_SIZE, tint);
        return icon != null ? icon : UIManager.getIcon("OptionPane.warningIcon");
    }

    private Icon stopIcon(Color tint) {
        Icon icon = svgIcon("/icons/input/stop-circle.svg", STOP_ICON_SIZE, tint);
        if (icon != null) {
            return icon;
        }

        Icon fallback = svgIcon("/icons/input/stop.svg", STOP_ICON_SIZE, tint);
        return fallback != null ? fallback : UIManager.getIcon("OptionPane.errorIcon");
    }

    private Icon svgIcon(String path, int size, Color tint) {
        String tintKey = tint == null ? "default" : String.valueOf(tint.getRGB());
        String key = "%s#%d#%s".formatted(path, size, tintKey);
        return CHIP_ICON_CACHE.computeIfAbsent(key, ignored -> {
            URL iconUrl = InputBar.class.getResource(path);
            if (iconUrl == null) {
                return null;
            }

            FlatSVGIcon svgIcon = new FlatSVGIcon(iconUrl).derive(size, size);
            Color resolvedTint = tint != null ? tint : UIManager.getColor("Label.foreground");
            if (resolvedTint == null) {
                resolvedTint = new Color(90, 90, 90);
            }

            Color finalResolvedTint = resolvedTint;
            svgIcon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> new Color(
                    finalResolvedTint.getRed(),
                    finalResolvedTint.getGreen(),
                    finalResolvedTint.getBlue(),
                    color.getAlpha()
            )));
            return svgIcon.hasFound() ? svgIcon : null;
        });
    }

}
