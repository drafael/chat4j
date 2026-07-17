package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.FlatClientProperties;
import com.github.drafael.chat4j.chat.agent.AgentOrchestrator;
import com.github.drafael.chat4j.chat.agent.AgentRunCallbacks;
import com.github.drafael.chat4j.chat.agent.AgentRunRequest;
import com.github.drafael.chat4j.chat.agent.AgentToolActivity;
import com.github.drafael.chat4j.chat.composer.AttachmentStager;
import com.github.drafael.chat4j.chat.composer.ComposerAttachment;
import com.github.drafael.chat4j.chat.composer.ComposerPanel;
import com.github.drafael.chat4j.chat.composer.EditComposerPanel;
import com.github.drafael.chat4j.chat.composer.FileAttachmentChip;
import com.github.drafael.chat4j.chat.composer.ImageAttachmentPreview;
import com.github.drafael.chat4j.chat.composer.InputBar;
import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.conversation.webview.jcef.JcefBrowserView;
import com.github.drafael.chat4j.chat.conversation.webview.system.SystemWebView;
import com.github.drafael.chat4j.chat.diagram.DiagramHtmlExporter;
import com.github.drafael.chat4j.chat.message.ChatMessageView;
import com.github.drafael.chat4j.chat.message.ChatMessageViewFactory;
import com.github.drafael.chat4j.chat.model.ModelSelectorButton;
import com.github.drafael.chat4j.chat.model.ModelSelectorPopup;
import com.github.drafael.chat4j.chat.model.ProviderModelSelection;
import com.github.drafael.chat4j.chat.model.ProviderSelectionSnapshot;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.chat.render.ThinkTagSplit;
import com.github.drafael.chat4j.chat.render.WebSearchActivityNormalizer;
import com.github.drafael.chat4j.chat.ui.ActivityBubble;
import com.github.drafael.chat4j.chat.ui.ChatEmptyStatePanel;
import com.github.drafael.chat4j.chat.ui.ChatFadeOverlay;
import com.github.drafael.chat4j.chat.ui.EmptyStateActions;
import com.github.drafael.chat4j.chat.ui.JumpToLatestButton;
import com.github.drafael.chat4j.chat.ui.ScrollablePanel;
import com.github.drafael.chat4j.chat.ui.ThemeAwareSvgIcon;
import com.github.drafael.chat4j.chat.webview.WebViewEngine;
import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AgentToolActivityMeta;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.github.drafael.chat4j.stt.SpeechToTextService;
import com.github.drafael.chat4j.tts.TextToSpeechService;
import com.github.drafael.chat4j.util.Fonts;
import com.github.drafael.chat4j.util.PopupMenuSupport;
import com.github.drafael.chat4j.web.BrowsedPage;
import com.github.drafael.chat4j.web.ModelWebQueryPlanner;
import com.github.drafael.chat4j.web.PerplexityWebSearchProvider;
import com.github.drafael.chat4j.web.WebSearchAvailability;
import com.github.drafael.chat4j.web.WebSearchAvailabilityResolver;
import com.github.drafael.chat4j.web.WebSearchContext;
import com.github.drafael.chat4j.web.WebSearchCoordinator;
import com.github.drafael.chat4j.web.WebSearchResponse;
import com.github.drafael.chat4j.web.WebSearchResult;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;

@Slf4j
public class ChatPanel extends JPanel {
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_CHAT = "chat";
    private static final int CHAT_MENU_ICON_SIZE = 14;
    private static final int RENDER_MODE_ICON_SIZE = 16;
    private static final int BUBBLE_ACTION_BUTTON_SIZE = 20;
    private static final int BUBBLE_ACTION_BAR_HEIGHT = 22;
    private static final int JUMP_OVERLAY_BOTTOM_GAP = 8;
    private static final int COMPOSER_FADE_HEIGHT = 48;
    private static final int CHAT_TOP_FADE_HEIGHT = 34;
    private static final int READ_ALOUD_STATUS_CLEAR_DELAY_MILLIS = 2800;
    private static final int CHAT_COLUMN_SIDE_MARGIN = 16;
    private static final int ASSISTANT_MESSAGE_SIDE_MARGIN = 0;
    private static final String MESSAGE_ROLE_PROPERTY = "chat4j.messageRole";
    private static final String MESSAGE_INDEX_PROPERTY = "chat4j.messageIndex";
    private static final String MESSAGE_META_PROPERTY = "chat4j.messageMeta";
    private static final String MESSAGE_VIEW_PROPERTY = "chat4j.messageView";
    private static final String MESSAGE_ACTION_BAR_PROPERTY = "chat4j.messageActionBar";
    private static final Integer COMPOSER_FADE_LAYER = 50;
    private static final boolean THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING = true;
    private static final boolean THINKING_COLLAPSED_BY_DEFAULT_WHEN_LOADING_HISTORY = true;
    private static final boolean WEB_SEARCH_COLLAPSED_BY_DEFAULT = true;
    private static final boolean AGENT_TOOLS_COLLAPSED_BY_DEFAULT = true;
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Pattern NON_PRINTABLE_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern UNICODE_FORMAT_PATTERN = Pattern.compile("\\p{Cf}");
    private static final Pattern SOURCE_URL_PATTERN = Pattern.compile("(?:\\[[^]]+])?\\(<(https?://[^>\\s]+)>\\)|<(https?://[^>\\s]+)>|(?:\\[[^]]+])?\\((https?://(?:[^\\s()<>]|\\([^\\s()<>]*\\))+)\\)|(https?://(?:[^\\s()<>]|\\([^\\s()<>]*\\))+)");
    private static final Pattern SOURCE_REFERENCE_LINE_PATTERN = Pattern.compile("(?m)^\\s*(?:[-*]\\s*)?\\[\\d+]\\s*(?:\\([^)]*https?://[^)]*\\)|.*https?://\\S+)");
    private static final Map<String, Icon> CHAT_MENU_ICON_CACHE = new ConcurrentHashMap<>();

    private final JPanel messagesPanel;
    private final JScrollPane scrollPane;
    private final CardLayout messagesCardLayout = new CardLayout();
    private final JPanel messagesContainer;
    private JPanel emptyStatePanel;
    private final InputBar inputBar;
    private final ComposerPanel composerPanel;
    private final JLayeredPane bodyLayered;
    private final JPanel bodyContent;
    private final JumpToLatestButton jumpToLatestOverlay;
    private final JLabel readAloudStatusLabel = new JLabel();
    private final Timer readAloudStatusTimer;
    private final ChatFadeOverlay topFadeOverlay;
    private final ChatFadeOverlay composerFadeOverlay;
    private boolean atBottom = true;
    private final ModelSelectorButton modelSelectorBtn;
    private final ProviderModelCacheService modelCacheService;
    private final ModelFavoritesService modelFavoritesService;
    private final AttachmentStager attachmentStager = new AttachmentStager();
    private final WebSearchAvailabilityResolver webSearchAvailabilityResolver = new WebSearchAvailabilityResolver();
    private final WebSearchCoordinator webSearchCoordinator = new WebSearchCoordinator(List.of(new PerplexityWebSearchProvider()));
    private final ChatMessageViewFactory messageViewFactory;
    private final WebViewEngine webViewEngine;
    private final SystemWebView systemWebView;
    private final JcefBrowserView jcefBrowserView;
    private final TextToSpeechService textToSpeechService;
    private final SpeechToTextService speechToTextService;
    private final CodexAuthResolver codexAuthResolver = new CodexAuthResolver();
    private final CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver();
    private volatile AgentOrchestrator agentOrchestrator;
    private volatile String agentSystemPromptAppend = "";
    private final List<ChatMessageView> assistantBubbles = new ArrayList<>();
    private final List<ActivityBubble> thinkingBubbles = new ArrayList<>();
    private final JToggleButton previewToggle = new JToggleButton();
    private final JToggleButton markdownToggle = new JToggleButton();
    private final JPanel renderTogglePanel;
    private RenderMode renderMode = RenderMode.PREVIEW;
    private Consumer<RenderMode> renderModeChangedListener;
    private Runnable selectedModelChangedListener;
    private Runnable modelFavoritesChangedListener;
    private Runnable modelCatalogChangedListener;
    private Runnable messageSubmittedListener;
    private Runnable clearChatRequestedListener;
    private UserMessagePersistenceListener userMessageSubmittedListener;
    private AssistantMessagePersistenceListener assistantMessageCompletedListener;
    private Consumer<HistoryTruncatedEvent> historyTruncatedListener;
    private Consumer<ConversationStreamingEvent> conversationStreamingListener;
    private Consumer<Boolean> visibleStreamingChangedListener;
    private Supplier<UUID> conversationIdSupplier;
    private ModelSelectorPopup modelPopup;
    private EditingUserMessage editingUserMessage;
    private ComposerState speechToTextComposerSnapshot = ComposerState.empty();

    private final List<Message> history = new ArrayList<>();
    private Map<String, ProviderRegistry.ProviderDef> providerMap = emptyMap();
    private String selectedProviderName;
    private String selectedModelId;
    private ProviderService currentProvider;
    private String currentProviderApiKey;
    private volatile boolean currentProviderResolving;
    private boolean conversationLoading;
    private boolean batchMessageRefresh;
    private ActivityBubble currentAssistantWebSearchBubble;
    private ActivityBubble currentAssistantActivityBubble;
    private final Map<String, ActivityBubble> currentAssistantAgentToolBubbles = new LinkedHashMap<>();
    private ChatMessageView currentAssistantBubble;
    private final AtomicLong sendJobCounter = new AtomicLong();
    private final AtomicLong streamSessionCounter = new AtomicLong();
    private final AtomicLong providerSelectionCounter = new AtomicLong();
    private final AtomicLong providerRefreshCounter = new AtomicLong();
    private long providerScopeVersion;
    private final Map<Long, SendJob> activeSendJobs = new ConcurrentHashMap<>();
    private final Map<Long, StreamingSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Message> pendingCompletedAssistantRecoveries = new ConcurrentHashMap<>();
    private volatile long activeStreamSessionId = -1L;
    private volatile boolean streaming = false;
    private volatile boolean autoScrollEnabled = true;
    private volatile UUID activeConversationId;
    private List<PromptQuickAction> promptQuickActions = emptyList();
    private volatile SendPreparer sendPreparer = this::prepareUserMessage;
    private boolean autoScrollQueued = false;
    private int messageRow = 0;

    private record EditingUserMessage(int messageIndex, ComposerState savedComposerState) {
        @Override
        public String toString() {
            return "EditingUserMessage[messageIndex=%d]".formatted(messageIndex);
        }
    }

    public record PromptQuickAction(@NonNull String title, @NonNull Runnable action) {
        public PromptQuickAction {
            title = StringUtils.trimToEmpty(title);
            if (title.isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
        }

        @Override
        public String toString() {
            return "PromptQuickAction[title=%s]".formatted(title);
        }
    }

    public ChatPanel(ProviderModelCacheService modelCacheService) {
        this(modelCacheService, ModelFavoritesService.createInMemory());
        populateModels();
    }

    public ChatPanel(ProviderModelCacheService modelCacheService, ModelFavoritesService modelFavoritesService) {
        this(modelCacheService, modelFavoritesService, new ChatMessageViewFactory(), WebViewEngine.JEDITOR_PANE);
    }

    public ChatPanel(
            @NonNull ProviderModelCacheService modelCacheService,
            @NonNull ModelFavoritesService modelFavoritesService,
            @NonNull ChatMessageViewFactory messageViewFactory
    ) {
        this(modelCacheService, modelFavoritesService, messageViewFactory, WebViewEngine.JEDITOR_PANE);
    }

    public ChatPanel(
            @NonNull ProviderModelCacheService modelCacheService,
            @NonNull ModelFavoritesService modelFavoritesService,
            @NonNull ChatMessageViewFactory messageViewFactory,
            @NonNull WebViewEngine webViewEngine
    ) {
        this(modelCacheService, modelFavoritesService, messageViewFactory, webViewEngine, TextToSpeechService.disabled(), SpeechToTextService.disabled());
    }

    public ChatPanel(
            @NonNull ProviderModelCacheService modelCacheService,
            @NonNull ModelFavoritesService modelFavoritesService,
            @NonNull ChatMessageViewFactory messageViewFactory,
            @NonNull WebViewEngine webViewEngine,
            @NonNull TextToSpeechService textToSpeechService
    ) {
        this(modelCacheService, modelFavoritesService, messageViewFactory, webViewEngine, textToSpeechService, SpeechToTextService.disabled());
    }

    public ChatPanel(
            @NonNull ProviderModelCacheService modelCacheService,
            @NonNull ModelFavoritesService modelFavoritesService,
            @NonNull ChatMessageViewFactory messageViewFactory,
            @NonNull WebViewEngine webViewEngine,
            @NonNull TextToSpeechService textToSpeechService,
            @NonNull SpeechToTextService speechToTextService
    ) {
        this.modelCacheService = modelCacheService;
        this.modelFavoritesService = modelFavoritesService;
        this.messageViewFactory = messageViewFactory;
        this.webViewEngine = webViewEngine;
        this.textToSpeechService = textToSpeechService;
        this.speechToTextService = speechToTextService;
        this.systemWebView = webViewEngine == WebViewEngine.SYSTEM ? new SystemWebView() : null;
        this.jcefBrowserView = webViewEngine == WebViewEngine.JCEF ? new JcefBrowserView() : null;
        if (this.systemWebView != null) {
            this.systemWebView.setActionListener(this::handleWebTranscriptAction);
        }
        if (this.jcefBrowserView != null) {
            this.jcefBrowserView.setActionListener(this::handleWebTranscriptAction);
        }
        this.agentOrchestrator = AgentOrchestrator.createDefault();
        setLayout(new BorderLayout());

        modelSelectorBtn = new ModelSelectorButton();
        modelSelectorBtn.addActionListener(e -> toggleModelPopup());
        renderTogglePanel = createRenderTogglePanel();

        // Messages area — uses ScrollablePanel + GridBagLayout for proper width tracking
        messagesPanel = new ScrollablePanel();
        messagesPanel.setLayout(new GridBagLayout());
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(8, CHAT_COLUMN_SIDE_MARGIN, 12, CHAT_COLUMN_SIDE_MARGIN));
        messagesPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refreshMessageColumnInsets();
                refreshUserBubbleMaxWidths();
                if (autoScrollEnabled) {
                    scheduleAutoScroll();
                }
            }
        });

        scrollPane = new JScrollPane(messagesPanel);
        scrollPane.addPropertyChangeListener("UI", e -> SwingUtilities.invokeLater(this::applyScrollPaneStyles));
        applyScrollPaneStyles();

        messagesContainer = new JPanel(messagesCardLayout);
        emptyStatePanel = buildEmptyStatePanel();
        messagesContainer.add(emptyStatePanel, CARD_EMPTY);
        messagesContainer.add(chatTranscriptComponent(), CARD_CHAT);
        messagesCardLayout.show(messagesContainer, CARD_EMPTY);

        // Input bar at bottom
        inputBar = new InputBar();
        inputBar.addSendListener(e -> onSend());
        inputBar.addClearChatListener(e -> requestClearChat());
        inputBar.addCancelGenerationListener(e -> cancelStreamingAndMarkCancelled());
        inputBar.addSpeechToTextStartListener(e -> startSpeechToTextRecording());
        inputBar.addSpeechToTextStopListener(e -> speechToTextService.stopRecordingAndTranscribe());
        inputBar.addSpeechToTextCancelListener(e -> cancelSpeechToText());
        reloadSpeechToTextSettings();
        updateClearChatButtonVisibility();

        composerPanel = new ComposerPanel(inputBar);

        bodyContent = new JPanel(new BorderLayout());
        bodyContent.setOpaque(false);
        bodyContent.add(messagesContainer, BorderLayout.CENTER);
        bodyContent.add(composerPanel, BorderLayout.SOUTH);

        jumpToLatestOverlay = new JumpToLatestButton();
        jumpToLatestOverlay.setVisible(false);
        jumpToLatestOverlay.addActionListener(e -> onJumpToLatestRequested());

        configureReadAloudStatusLabel();
        readAloudStatusTimer = new Timer(READ_ALOUD_STATUS_CLEAR_DELAY_MILLIS, e -> readAloudStatusLabel.setVisible(false));
        readAloudStatusTimer.setRepeats(false);

        topFadeOverlay = new ChatFadeOverlay(ChatFadeOverlay.Direction.TOP, 0.70f);
        composerFadeOverlay = new ChatFadeOverlay(ChatFadeOverlay.Direction.BOTTOM, 1.0f);

        bodyLayered = new JLayeredPane() {
            @Override
            public Dimension getPreferredSize() {
                return bodyContent.getPreferredSize();
            }

            @Override
            public void doLayout() {
                bodyContent.setBounds(0, 0, getWidth(), getHeight());
                topFadeOverlay.setBounds(0, 0, getWidth(), Math.min(CHAT_TOP_FADE_HEIGHT, getHeight()));
                layoutJumpOverlay();
                layoutReadAloudStatusLabel();
            }
        };
        bodyLayered.add(bodyContent, JLayeredPane.DEFAULT_LAYER);
        bodyLayered.add(topFadeOverlay, COMPOSER_FADE_LAYER);
        bodyLayered.add(composerFadeOverlay, COMPOSER_FADE_LAYER);
        bodyLayered.add(jumpToLatestOverlay, JLayeredPane.PALETTE_LAYER);
        bodyLayered.add(readAloudStatusLabel, JLayeredPane.PALETTE_LAYER);
        add(bodyLayered, BorderLayout.CENTER);

        inputBar.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutJumpOverlay();
                layoutReadAloudStatusLabel();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                layoutJumpOverlay();
                layoutReadAloudStatusLabel();
            }
        });

        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> updateAtBottom());
        refreshJumpOverlay();
    }

    public void setPromptQuickActions(@NonNull List<PromptQuickAction> promptQuickActions) {
        this.promptQuickActions = List.copyOf(promptQuickActions);
        refreshEmptyStatePanel();
    }

    private void refreshEmptyStatePanel() {
        if (messagesContainer == null || emptyStatePanel == null) {
            return;
        }

        messagesContainer.remove(emptyStatePanel);
        emptyStatePanel = buildEmptyStatePanel();
        messagesContainer.add(emptyStatePanel, CARD_EMPTY, 0);
        messagesCardLayout.show(messagesContainer, history.isEmpty() ? CARD_EMPTY : CARD_CHAT);
        messagesContainer.revalidate();
        messagesContainer.repaint();
    }

    private void configureReadAloudStatusLabel() {
        readAloudStatusLabel.setVisible(false);
        readAloudStatusLabel.setOpaque(true);
        readAloudStatusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
        Fonts.apply(readAloudStatusLabel, Font.PLAIN, Fonts.SIZE_SMALL);
    }

    private void layoutJumpOverlay() {
        if (jumpToLatestOverlay == null || inputBar == null || bodyLayered == null) {
            return;
        }

        Dimension size = jumpToLatestOverlay.getPreferredSize();
        int inputTopY = inputBarTopY();
        int x = (bodyLayered.getWidth() - size.width) / 2;
        int y = inputTopY - size.height - JUMP_OVERLAY_BOTTOM_GAP;
        jumpToLatestOverlay.setBounds(x, y, size.width, size.height);

        if (composerFadeOverlay != null) {
            int fadeTop = Math.max(0, inputTopY - COMPOSER_FADE_HEIGHT);
            composerFadeOverlay.setBounds(0, fadeTop, bodyLayered.getWidth(), inputTopY - fadeTop);
        }
    }

    private void layoutReadAloudStatusLabel() {
        if (readAloudStatusLabel == null || inputBar == null || bodyLayered == null || !readAloudStatusLabel.isVisible()) {
            return;
        }
        Dimension size = readAloudStatusLabel.getPreferredSize();
        int x = Math.max(12, (bodyLayered.getWidth() - size.width) / 2);
        int y = inputBarTopY() - size.height - JUMP_OVERLAY_BOTTOM_GAP;
        readAloudStatusLabel.setBounds(x, Math.max(8, y), size.width, size.height);
    }

    private int inputBarTopY() {
        if (inputBar.getParent() == null) {
            return inputBar.getY();
        }
        return SwingUtilities.convertPoint(inputBar.getParent(), inputBar.getLocation(), bodyLayered).y;
    }

    private void updateAtBottom() {
        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        int bottomEdge = vertical.getValue() + vertical.getVisibleAmount();
        boolean nextAtBottom = bottomEdge >= vertical.getMaximum() - 4;
        if (nextAtBottom != atBottom) {
            atBottom = nextAtBottom;
            refreshJumpOverlay();
        }
    }

    private void refreshJumpOverlay() {
        if (jumpToLatestOverlay == null) {
            return;
        }

        boolean shouldShow = !atBottom;
        if (jumpToLatestOverlay.isVisible() != shouldShow) {
            jumpToLatestOverlay.setVisible(shouldShow);
            if (bodyLayered != null) {
                bodyLayered.revalidate();
                bodyLayered.repaint();
            }
        }
    }

    @Override
    public void updateUI() {
        super.updateUI();
        applyScrollPaneStyles();
        refreshWebTranscript(false, true);
    }

    private JComponent chatTranscriptComponent() {
        if (isSystemWebViewEnabled()) {
            return systemWebView.component();
        }
        return isJcefBrowserViewEnabled() ? jcefBrowserView.component() : scrollPane;
    }

    private boolean isSystemWebViewEnabled() {
        return webViewEngine == WebViewEngine.SYSTEM && systemWebView != null;
    }

    private boolean isJcefBrowserViewEnabled() {
        return webViewEngine == WebViewEngine.JCEF && jcefBrowserView != null;
    }

    private boolean isBrowserConversationEnabled() {
        return isSystemWebViewEnabled() || isJcefBrowserViewEnabled();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(this::preloadModelPopup);
    }

    @Override
    public void removeNotify() {
        providerRefreshCounter.incrementAndGet();
        providerSelectionCounter.incrementAndGet();
        modelCacheService.cancelScopeVersion(providerScopeVersion);
        speechToTextService.dispose();
        stopReadAloudPlayback();
        if (modelPopup != null) {
            modelPopup.dispose();
            modelPopup = null;
        }
        if (systemWebView != null && !systemWebView.isDisposed()) {
            systemWebView.dispose();
        }
        if (jcefBrowserView != null && !jcefBrowserView.isDisposed()) {
            jcefBrowserView.dispose();
        }
        super.removeNotify();
    }

    private void applyScrollPaneStyles() {
        if (scrollPane == null) {
            return;
        }

        scrollPane.setBorder(null);
        scrollPane.setViewportBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.putClientProperty("JScrollPane.smoothScrolling", false);
    }

    private JPanel createRenderTogglePanel() {
        ButtonGroup group = new ButtonGroup();
        group.add(previewToggle);
        group.add(markdownToggle);

        configureRenderToggleButton(
                previewToggle,
                "first",
                "Preview rendered markdown",
                "/icons/chat/render-preview.svg",
                RenderMode.PREVIEW.displayName()
        );
        configureRenderToggleButton(
                markdownToggle,
                "last",
                "Show raw markdown",
                "/icons/chat/markdown-mark.svg",
                RenderMode.MARKDOWN.displayName()
        );

        previewToggle.addActionListener(e -> {
            if (previewToggle.isSelected()) {
                setRenderMode(RenderMode.PREVIEW, true);
            }
        });
        markdownToggle.addActionListener(e -> {
            if (markdownToggle.isSelected()) {
                setRenderMode(RenderMode.MARKDOWN, true);
            }
        });

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        panel.setOpaque(false);
        panel.add(previewToggle);
        panel.add(markdownToggle);
        updateRenderModeToggleSelection();
        return panel;
    }

    private void configureRenderToggleButton(
            JToggleButton button,
            String segmentPosition,
            String tooltip,
            String iconPath,
            String accessibleName
    ) {
        button.putClientProperty("JButton.buttonType", "segmented");
        button.putClientProperty("JButton.segmentPosition", segmentPosition);
        button.setFocusable(false);
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.setIcon(loadRenderModeIcon(iconPath));
        button.setMargin(new Insets(2, 8, 2, 8));
        Fonts.apply(button, Font.PLAIN, Fonts.SIZE_SMALL);
        button.setPreferredSize(new Dimension(42, 22));
        button.setMinimumSize(new Dimension(42, 22));
    }

    private Icon loadRenderModeIcon(String iconPath) {
        URL url = ChatPanel.class.getResource(iconPath);
        return url == null ? null : new ThemeAwareSvgIcon(url, RENDER_MODE_ICON_SIZE);
    }

    private void populateModels() {
        long scopeVersion = modelCacheService.nextScopeVersion();
        providerScopeVersion = scopeVersion;
        List<ProviderRegistry.ProviderDef> providers = ProviderRegistry.availableProviders();
        prepareProviderModels(providers, scopeVersion);
        applyProviderModels(providers, scopeVersion);
    }

    private void prepareProviderModels(List<ProviderRegistry.ProviderDef> providers, long scopeVersion) {
        providers.forEach(provider -> modelCacheService.synchronizeScope(
                provider.name(),
                provider.baseUrl(),
                scopeVersion
        ));
    }

    private boolean applyProviderModels(List<ProviderRegistry.ProviderDef> providers, long scopeVersion) {
        if (!updateProviderMap(providers, scopeVersion)) {
            return false;
        }

        if (selectedProviderName != null
                && selectedModelId != null
                && providerMap.containsKey(selectedProviderName)
                && !modelCacheService.isInvalidated(selectedProviderName)
        ) {
            selectModel(selectedProviderName, selectedModelId);
            return true;
        }

        if (selectedProviderName != null
                && (!providerMap.containsKey(selectedProviderName) || modelCacheService.isInvalidated(selectedProviderName))) {
            clearSelectedModel();
        }

        // Prefer cached models fetched in previous sessions, but never reuse an invalidated cache.
        if (!providerMap.isEmpty()) {
            providerMap.values().stream()
                    .map(providerDef -> new ProviderModelSelection(
                        providerDef.name(),
                        initialProviderModels(providerDef)
                    )
                    )
                    .filter(selection -> !selection.models().isEmpty())
                    .findFirst()
                    .ifPresent(selection -> selectModel(selection.providerName(), selection.models().getFirst()));

            if (selectedProviderName != null && selectedModelId != null) {
                return true;
            }

            // Fallback to first provider that has seeded models.
            providerMap.values().stream()
                    .map(providerDef -> new ProviderModelSelection(
                        providerDef.name(),
                        sanitizeModelIds(providerDef.name(), providerDef.seedModels())
                    )
                    )
                    .filter(selection -> !selection.models().isEmpty())
                    .findFirst()
                    .ifPresent(selection -> selectModel(selection.providerName(), selection.models().getFirst()));
            return true;
        }

        clearSelectedModel();
        return true;
    }

    private boolean updateProviderModelsFromPopup(
            List<ProviderRegistry.ProviderDef> providers,
            long scopeVersion
    ) {
        Map<String, ProviderRegistry.ProviderDef> previousProviderMap = providerMap;
        if (!updateProviderMap(providers, scopeVersion)) {
            return false;
        }
        boolean providersChanged = previousProviderMap.size() != providers.size()
                || providers.stream().anyMatch(provider -> {
                    ProviderRegistry.ProviderDef existing = previousProviderMap.get(provider.name());
                    return existing == null || !Objects.equals(existing.baseUrl(), provider.baseUrl());
                });
        boolean cacheInvalidated = providers.stream()
                .map(ProviderRegistry.ProviderDef::name)
                .anyMatch(modelCacheService::isInvalidated);
        if (providersChanged || cacheInvalidated) {
            notifyModelCatalogChanged();
        }
        if (selectedProviderName != null
                && (!providerMap.containsKey(selectedProviderName)
                || modelCacheService.isInvalidated(selectedProviderName))) {
            clearSelectedModel();
        }
        return true;
    }

    private boolean updateProviderMap(List<ProviderRegistry.ProviderDef> providers, long scopeVersion) {
        Map<String, ProviderRegistry.ProviderDef> updatedProviderMap = providers.stream()
                .collect(toMap(
                        ProviderRegistry.ProviderDef::name,
                        Function.identity(),
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));
        return modelCacheService.runIfScopeVersionCurrent(
                scopeVersion,
                () -> providerMap = updatedProviderMap
        );
    }

    private void clearSelectedModel() {
        boolean selectionChanged = selectedProviderName != null || selectedModelId != null;
        providerSelectionCounter.incrementAndGet();
        selectedProviderName = null;
        selectedModelId = null;
        currentProvider = null;
        currentProviderApiKey = null;
        currentProviderResolving = false;
        modelSelectorBtn.setSelection("", "");
        inputBar.setThinkingAvailable(false);
        inputBar.setWebSearchOptions(emptyList(), null);
        inputBar.setAgentModeAvailable(false);
        refreshComposerAvailability();
        if (selectionChanged && selectedModelChangedListener != null) {
            selectedModelChangedListener.run();
        }
    }

    private List<String> initialProviderModels(ProviderRegistry.ProviderDef providerDef) {
        if (modelCacheService.isInvalidated(providerDef.name())) {
            return sanitizeModelIds(providerDef.name(), providerDef.seedModels());
        }
        return sanitizeModelIds(providerDef.name(), modelCacheService.getModels(providerDef.name()));
    }

    private void toggleModelPopup() {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before changing models.");
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        ModelSelectorPopup popup = ensureModelPopup(owner);
        if (popup.isVisible()) {
            popup.hidePopup();
            return;
        }

        showModelPopup(popup);
    }

    private void showModelPopup(ModelSelectorPopup popup) {
        popup.show(modelSelectorBtn, selectedProviderName, selectedModelId);
    }

    private ModelSelectorPopup ensureModelPopup(Window owner) {
        if (modelPopup == null) {
            modelPopup = new ModelSelectorPopup(
                owner,
                modelCacheService,
                modelFavoritesService,
                this::selectModel,
                this::updateProviderModelsFromPopup,
                this::notifyModelFavoritesChanged,
                this::notifyModelCatalogChanged
            );
        }

        return modelPopup;
    }

    private void preloadModelPopup() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (owner == null) {
            return;
        }

        ensureModelPopup(owner).preload();
    }

    private void selectModel(String providerName, String modelId) {
        long selectionId = providerSelectionCounter.incrementAndGet();
        selectedProviderName = providerName;
        selectedModelId = modelId;
        modelSelectorBtn.setSelection(providerName, modelId);

        currentProvider = null;
        currentProviderApiKey = null;
        currentProviderResolving = false;
        ProviderRegistry.ProviderDef providerDef = providerMap.get(providerName);
        if (providerDef != null) {
            resolveCurrentProviderAsync(selectionId, providerDef, modelId);
        } else {
            refreshComposerAvailability();
        }

        updateThinkingToggleAvailability(selectionId);
        updateWebSearchAvailability(selectionId);
        updateAgentToggleAvailability(selectionId);

        if (selectedModelChangedListener != null) {
            selectedModelChangedListener.run();
        }
    }

    private void resolveCurrentProviderAsync(
            long selectionId,
            ProviderRegistry.ProviderDef providerDef,
            String modelId
    ) {
        currentProviderResolving = true;
        refreshComposerAvailability();
        String providerNameSnapshot = providerDef.name();
        Thread.startVirtualThread(() -> {
            try {
                ProviderService resolvedProvider = providerDef.factory().create(modelId);
                String resolvedApiKey = resolveProviderApiKey(providerDef);
                SwingUtilities.invokeLater(() -> applyResolvedProvider(
                        selectionId,
                        providerNameSnapshot,
                        modelId,
                        resolvedProvider,
                        resolvedApiKey
                ));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> applyProviderResolutionFailure(
                        selectionId,
                        providerNameSnapshot,
                        modelId,
                        e
                ));
            }
        });
    }

    private void applyResolvedProvider(
            long selectionId,
            String providerName,
            String modelId,
            ProviderService resolvedProvider,
            String resolvedApiKey
    ) {
        if (!isSelectedModel(selectionId, providerName, modelId)) {
            return;
        }

        currentProvider = resolvedProvider;
        currentProviderApiKey = resolvedApiKey;
        currentProviderResolving = false;
        refreshComposerAvailability();
    }

    private void applyProviderResolutionFailure(
            long selectionId,
            String providerName,
            String modelId,
            Exception error
    ) {
        if (!isSelectedModel(selectionId, providerName, modelId)) {
            return;
        }

        currentProvider = null;
        currentProviderApiKey = null;
        currentProviderResolving = false;
        refreshComposerAvailability();
        log.warn("Failed to prepare provider {}::{}: {}", providerName, modelId, ExceptionUtils.getMessage(error));
    }

    private void onSend() {
        if (editingUserMessage != null) {
            saveEditedUserMessageAndRegenerate();
            return;
        }

        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before sending.");
            return;
        }

        if (conversationLoading) {
            inputBar.showValidationMessage("Conversation is still loading. Try again in a moment.");
            return;
        }

        if (isVisibleConversationBusy()) {
            return;
        }

        ComposerState composerState = inputBar.getComposerState();
        if (composerState.isEmpty()) {
            return;
        }

        ProviderService provider = currentProvider;
        if (currentProviderResolving) {
            inputBar.showValidationMessage("Selected provider is still loading. Try again in a moment.");
            return;
        }
        if (provider == null) {
            inputBar.showValidationMessage("Select a model/provider before sending.");
            return;
        }

        boolean agentModeEnabled = inputBar.isAgentModeEnabled();
        Path agentProjectRoot = inputBar.getAgentProjectRoot();
        if (agentModeEnabled && (agentProjectRoot == null || !Files.isDirectory(agentProjectRoot))) {
            inputBar.showValidationMessage("Select a valid project folder to enable Agent Mode.");
            return;
        }

        ProviderSelectionSnapshot providerSnapshot = captureProviderSelection();

        long sendJobId = sendJobCounter.incrementAndGet();
        SendJob sendJob = new SendJob(
                sendJobId,
                resolveConversationId(),
                selectedProviderName,
                selectedModelId,
                providerSnapshot.baseUrl(),
                providerSnapshot.apiKey(),
                providerSnapshot.capabilities(),
                provider,
                new ArrayList<>(history),
                inputBar.getEffectiveReasoningLevel(),
                inputBar.isWebSearchEnabled(),
                inputBar.getWebSearchOptionId(),
                inputBar.getWebBrowseTopN(),
                agentModeEnabled,
                agentProjectRoot,
                agentSystemPromptAppend
        );
        activeSendJobs.put(sendJobId, sendJob);
        beginPreparing(sendJob);

        sendJob.worker = Thread.startVirtualThread(() -> {
            try {
                Message userMessage = sendPreparer.prepare(composerState, providerSnapshot, sendJob.cancelled::get);
                SwingUtilities.invokeLater(() -> commitPreparedSend(sendJob, userMessage));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> handlePreparationFailure(sendJob, e));
            }
        });
    }

    private void beginPreparing(SendJob sendJob) {
        sendJob.phase = SendPhase.PREPARING;
        if (isVisibleConversation(sendJob.conversationId)) {
            inputBar.setEnabled(false);
        }
        updateGenerationIndicator();
    }

    private void commitPreparedSend(SendJob sendJob, Message userMessage) {
        if (!isPreparing(sendJob)) {
            finishSendJob(sendJob);
            return;
        }

        boolean visibleConversation = isVisibleConversation(sendJob.conversationId);
        boolean startsConversation = visibleConversation && history.isEmpty();
        clearPendingAssistantRecovery(sendJob.conversationId);
        if (visibleConversation) {
            inputBar.clear();
            history.add(userMessage);
            addUserBubble(userMessage, history.size() - 1);
            updateClearChatButtonVisibility();
        }

        UUID persistedConversationId = persistUserMessage(sendJob, userMessage, visibleConversation);
        if (persistedConversationId == null && visibleConversation) {
            UUID resolvedConversationId = resolveConversationId();
            if (resolvedConversationId != null) {
                persistedConversationId = resolvedConversationId;
            }
        }
        sendJob.conversationId = persistedConversationId;

        List<Message> streamHistory = new ArrayList<>(sendJob.historySnapshot);
        streamHistory.add(userMessage);

        if (visibleConversation) {
            if (sendJob.webSearchEnabled) {
                currentAssistantActivityBubble = new ActivityBubble(THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING);
                currentAssistantActivityBubble.setStreaming(true);
                currentAssistantActivityBubble.setVisible(false);
                addActivityBubble(currentAssistantActivityBubble, null);

                currentAssistantWebSearchBubble = new ActivityBubble("Web Search", WEB_SEARCH_COLLAPSED_BY_DEFAULT);
                currentAssistantWebSearchBubble.setVisible(false);
                addActivityBubble(currentAssistantWebSearchBubble, null);
            }

            if (!sendJob.agentModeEnabled) {
                currentAssistantBubble = createMessageView(Role.ASSISTANT);
                addAssistantBubble(currentAssistantBubble, null);
            }
        }

        startAssistantStream(sendJob, streamHistory);
    }

    private void finishSuccessfulStream(StreamingSession session, SendJob sendJob) {
        if (!session.isLive()) {
            return;
        }
        persistAssistantResponse(session, sendJob, true);
        if (isVisibleConversation(session.conversationId)) {
            if (currentAssistantActivityBubble != null) {
                currentAssistantActivityBubble.setStreaming(false);
            }
            removeCurrentWebSearchBubbleIfBlank();
            removeCurrentActivityBubbleIfBlank();
            removeCurrentAgentToolBubblesIfBlank();
            currentAssistantWebSearchBubble = null;
            currentAssistantActivityBubble = null;
            clearCurrentAgentToolBubbleState();
            currentAssistantBubble = null;
        }
        finishStreamingSession(session);
        finishSendJob(sendJob);
    }

    private void handlePreparationFailure(SendJob sendJob, Exception error) {
        if (!activeSendJobs.containsKey(sendJob.jobId)) {
            return;
        }

        finishSendJob(sendJob);

        if (!isVisibleConversation(sendJob.conversationId)) {
            return;
        }

        if (error instanceof SendCancelledException || sendJob.cancelled.get()) {
            inputBar.requestInputFocus();
            return;
        }

        String message = StringUtils.defaultIfBlank(error.getMessage(), "Failed to prepare message");
        inputBar.showValidationMessage(message);
        inputBar.requestInputFocus();
    }

    private UUID persistUserMessage(SendJob sendJob, Message userMessage, boolean visibleConversation) {
        UUID conversationId = sendJob.conversationId;
        if (userMessageSubmittedListener != null) {
            try {
                UUID persistedConversationId = userMessageSubmittedListener.persist(new UserMessageEvent(
                        conversationId,
                        userMessage,
                        sendJob.providerName,
                        sendJob.modelId,
                        sendJob.reasoningLevel,
                        sendJob.agentModeEnabled,
                        sendJob.agentProjectRoot,
                        sendJob.webSearchEnabled,
                        sendJob.webSearchOptionId,
                        visibleConversation
                ));
                if (persistedConversationId != null) {
                    return persistedConversationId;
                }
            } catch (Exception e) {
                log.warn("User message persistence listener failed: {}", ExceptionUtils.getMessage(e));
            }
        }

        persistAssistantMessageEvent(conversationId, userMessage);

        if (visibleConversation || conversationId == null) {
            notifyMessageSubmitted();
        }
        return conversationId;
    }

    private void startAssistantStream(UUID conversationId, ProviderService provider) {
        ProviderSelectionSnapshot providerSnapshot = captureProviderSelection();
        SendJob sendJob = new SendJob(
                sendJobCounter.incrementAndGet(),
                conversationId,
                selectedProviderName,
                selectedModelId,
                providerSnapshot.baseUrl(),
                providerSnapshot.apiKey(),
                providerSnapshot.capabilities(),
                provider,
                new ArrayList<>(history),
                inputBar.getEffectiveReasoningLevel(),
                inputBar.isWebSearchEnabled(),
                inputBar.getWebSearchOptionId(),
                inputBar.getWebBrowseTopN(),
                inputBar.isAgentModeEnabled(),
                inputBar.getAgentProjectRoot(),
                agentSystemPromptAppend
        );
        activeSendJobs.put(sendJob.jobId, sendJob);
        startAssistantStream(sendJob, new ArrayList<>(history));
    }

    private void startAssistantStream(SendJob sendJob, List<Message> requestHistory) {
        sendJob.phase = SendPhase.STREAMING;
        StreamingSession session = beginStreamingSession(sendJob.conversationId, sendJob.provider);
        sendJob.streamSessionId = session.sessionId;

        AgentRunCallbacks callbacks = new AgentRunCallbacks(
                token -> handleAssistantToken(session, sendJob, token),
                thinkingToken -> handleAssistantThinkingToken(session, sendJob, thinkingToken),
                part -> handleAssistantPart(session, part),
                citation -> handleAssistantCitation(session, citation),
                activity -> handleAgentToolActivity(session, activity),
                () -> {
                    if (!session.beginTerminalCallback()) {
                        return;
                    }
                    flushThinkTagParser(session, sendJob);
                    SwingUtilities.invokeLater(() -> finishSuccessfulStream(session, sendJob));
                },
                error -> {
                    if (!session.beginTerminalCallback()) {
                        return;
                    }
                    String details = StringUtils.defaultIfBlank(ExceptionUtils.getMessage(error), "Unknown error");
                    log.warn("Assistant stream failed for provider={} model={} conversationId={}: {}",
                            sendJob.providerName,
                            sendJob.modelId,
                            session.conversationId,
                            details);
                    String errorText = "\n\n[Error: %s]".formatted(details);
                    appendAssistantResponse(session, errorText);
                    SwingUtilities.invokeLater(() -> {
                        if (!session.isLive()) {
                            return;
                        }
                        if (currentAssistantBubble != null && isVisibleConversation(session.conversationId)) {
                            currentAssistantBubble.appendText(errorText);
                        }
                        persistAssistantResponse(session, sendJob, false);
                        if (isVisibleConversation(session.conversationId)) {
                            if (currentAssistantActivityBubble != null) {
                                currentAssistantActivityBubble.setStreaming(false);
                            }
                            removeCurrentWebSearchBubbleIfBlank();
                            removeCurrentActivityBubbleIfBlank();
                            removeCurrentAgentToolBubblesIfBlank();
                            currentAssistantWebSearchBubble = null;
                            currentAssistantActivityBubble = null;
                            clearCurrentAgentToolBubbleState();
                            currentAssistantBubble = null;
                        }
                        finishStreamingSession(session);
                        finishSendJob(sendJob);
                    });
                }
        );

        session.worker = Thread.startVirtualThread(() -> {
            if (sendJob.agentModeEnabled) {
                AgentRunRequest request = new AgentRunRequest(
                        requestHistory,
                        sendJob.reasoningLevel,
                        sendJob.agentProjectRoot,
                        emptyList(),
                        session.cancelled::get
                );
                agentOrchestrator.streamCompletion(
                        sendJob.providerName,
                        sendJob.modelId,
                        sendJob.baseUrl,
                        sendJob.apiKey,
                        sendJob.agentSystemPromptAppend,
                        sessionScopedProvider(session),
                        request,
                        callbacks
                );
                return;
            }

            try {
                List<Message> effectiveHistory = prepareWebSearchContext(sendJob, session, requestHistory, session.cancelled::get);
                session.provider.streamCompletion(
                        effectiveHistory,
                        sendJob.reasoningLevel,
                        new WebSearchRequestOptions(nativeWebSearchEnabled(sendJob, requestHistory), sendJob.webSearchOptionId),
                        callbacks.onToken(),
                        callbacks.onThinkingToken(),
                        callbacks.onPart(),
                        callbacks.onCitation(),
                        callbacks.onComplete(),
                        callbacks.onError(),
                        session.cancelled::get,
                        session::registerActiveRequest,
                        session::clearActiveRequest
                );
            } catch (Exception e) {
                callbacks.onError().accept(e);
            }
        });
    }

    private ProviderService sessionScopedProvider(StreamingSession session) {
        ProviderService delegate = session.provider;
        return new ProviderService() {
            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled
            ) {
                delegate.streamCompletion(
                        history,
                        reasoningLevel,
                        WebSearchRequestOptions.disabled(),
                        onToken,
                        onThinkingToken,
                        onComplete,
                        onError,
                        isCancelled,
                        session::registerActiveRequest,
                        session::clearActiveRequest
                );
            }

            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    WebSearchRequestOptions webSearchOptions,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Consumer<ContentPart> onPart,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled,
                    Consumer<AutoCloseable> registerActiveStream,
                    Runnable clearActiveStream
            ) {
                delegate.streamCompletion(
                        history,
                        reasoningLevel,
                        webSearchOptions,
                        onToken,
                        onThinkingToken,
                        onPart,
                        citation -> {
                        },
                        onComplete,
                        onError,
                        isCancelled,
                        session::registerActiveRequest,
                        session::clearActiveRequest
                );
            }

            @Override
            public void streamCompletion(
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    WebSearchRequestOptions webSearchOptions,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Consumer<ContentPart> onPart,
                    Consumer<CitationRef> onCitation,
                    Runnable onComplete,
                    Consumer<Exception> onError,
                    BooleanSupplier isCancelled,
                    Consumer<AutoCloseable> registerActiveStream,
                    Runnable clearActiveStream
            ) {
                delegate.streamCompletion(
                        history,
                        reasoningLevel,
                        webSearchOptions,
                        onToken,
                        onThinkingToken,
                        onPart,
                        onCitation,
                        onComplete,
                        onError,
                        isCancelled,
                        session::registerActiveRequest,
                        session::clearActiveRequest
                );
            }

            @Override
            public List<String> availableModels() {
                return delegate.availableModels();
            }

            @Override
            public void cancelActiveRequest() {
                session.cancelActiveRequest();
            }

            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public String envVarName() {
                return delegate.envVarName();
            }

            @Override
            public boolean isAvailable() {
                return delegate.isAvailable();
            }
        };
    }

    private List<Message> prepareWebSearchContext(
            SendJob sendJob,
            StreamingSession session,
            List<Message> requestHistory,
            BooleanSupplier isCancelled
    ) throws Exception {
        if (!sendJob.webSearchEnabled) {
            return requestHistory;
        }

        ensureNotCancelled(isCancelled);
        String query = latestUserText(requestHistory);
        if (StringUtils.isBlank(query)) {
            return requestHistory;
        }

        if (!Strings.CS.equals(sendJob.webSearchOptionId, WebSearchAvailabilityResolver.PERPLEXITY_OPTION_ID)) {
            if (nativeWebSearchEnabled(sendJob, requestHistory)) {
                recordWebSearchActivity(session, formatNativeWebSearchActivity(sendJob, query));
            }
            return requestHistory;
        }

        WebSearchContext webSearchContext;
        try {
            webSearchContext = webSearchCoordinator.buildExternalContextDetails(
                    sendJob.webSearchOptionId,
                    query,
                    Math.max(1, sendJob.webBrowseTopN),
                    isCancelled,
                    new ModelWebQueryPlanner(sendJob.provider, requestHistory)
            );
        } catch (Exception e) {
            ensureNotCancelled(isCancelled);
            log.warn("Web search failed for provider={} query={}: {}",
                    sendJob.webSearchOptionId,
                    query,
                    ExceptionUtils.getMessage(e));
            return requestHistory;
        }
        ensureNotCancelled(isCancelled);
        String context = webSearchContext.promptContext();
        if (StringUtils.isBlank(context)) {
            return requestHistory;
        }

        recordWebSearchActivity(session, formatWebSearchActivity(webSearchContext));

        return withWebContextMessage(requestHistory, context);
    }

    private boolean nativeWebSearchEnabled(SendJob sendJob, List<Message> requestHistory) {
        if (!sendJob.webSearchEnabled || !Strings.CS.equals(sendJob.webSearchOptionId, WebSearchAvailabilityResolver.NATIVE_OPTION_ID)) {
            return false;
        }
        if (!nativeWebSearchSupported(sendJob)) {
            return false;
        }
        if (!Strings.CS.equals(sendJob.providerName, "OpenAI") && !Strings.CS.equals(sendJob.providerName, "xAI")) {
            return true;
        }
        return requestHistory.stream()
                .flatMap(message -> message.parts().stream())
                .allMatch(part -> part instanceof TextPart);
    }

    private boolean nativeWebSearchSupported(SendJob sendJob) {
        return ProviderCapabilityResolver.supportsRuntimeNativeWebSearch(
                sendJob.capabilities,
                sendJob.providerName,
                sendJob.modelId,
                sendJob.baseUrl,
                sendJob.apiKey
        );
    }

    private List<Message> withWebContextMessage(List<Message> requestHistory, String context) {
        int latestUserIndex = latestUserIndex(requestHistory);
        if (latestUserIndex < 0) {
            return requestHistory;
        }

        List<Message> effectiveHistory = new ArrayList<>(requestHistory);
        effectiveHistory.set(latestUserIndex, mergeWebContextIntoUserMessage(requestHistory.get(latestUserIndex), context));
        return effectiveHistory;
    }

    private Message mergeWebContextIntoUserMessage(Message userMessage, String context) {
        List<ContentPart> parts = new ArrayList<>(userMessage.parts().size() + 1);
        parts.add(new TextPart(context));
        parts.addAll(userMessage.parts());
        return new Message(userMessage.role(), parts, userMessage.timestamp(), userMessage.meta());
    }

    private int latestUserIndex(List<Message> requestHistory) {
        return IntStream.range(0, requestHistory.size())
                .filter(index -> requestHistory.get(index).role() == Role.USER)
                .reduce((first, second) -> second)
                .orElse(-1);
    }

    private String latestUserText(List<Message> requestHistory) {
        return requestHistory.stream()
                .filter(message -> message.role() == Role.USER)
                .reduce((first, second) -> second)
                .map(Message::content)
                .orElse("");
    }

    private void recordWebSearchActivity(StreamingSession session, String webSearchActivity) {
        String normalizedActivity = normalizeWebSearchActivity(webSearchActivity);
        if (StringUtils.isBlank(normalizedActivity)) {
            return;
        }

        synchronized (session.webSearchActivity) {
            session.webSearchActivity.setLength(0);
            session.webSearchActivity.append(normalizedActivity);
        }
        SwingUtilities.invokeLater(() -> showWebSearchActivity(session, normalizedActivity));
    }

    private String formatNativeWebSearchActivity(SendJob sendJob, String query) {
        String provider = StringUtils.defaultIfBlank(sendJob.providerName, "Selected provider");
        String model = StringUtils.defaultIfBlank(sendJob.modelId, "selected model");
        return """
                **Searched**
                - %s

                **Sources**
                - Native web search is handled by %s (%s). Source URLs will appear here if the provider returns citation metadata in the answer.
                """.formatted(query, provider, model).trim();
    }

    private String formatWebSearchActivity(WebSearchContext context) {
        if (context == null || (context.responses().isEmpty() && context.browsedPages().isEmpty())) {
            return "";
        }

        // Internally, search results and browsed pages remain separate on WebSearchContext:
        // responses are URLs returned by the search provider.
        // browsedPages are URLs Chat4J fetched and parsed.
        // The bubble merges both into one Sources section because users mainly need one de-duplicated citation list.
        StringBuilder activity = new StringBuilder();
        context.responses().stream()
                .filter(Objects::nonNull)
                .forEach(response -> appendWebSearchResponseActivity(activity, response));
        appendBrowsedPageActivity(activity, context.browsedPages());
        return activity.toString().trim();
    }

    private void appendWebSearchResponseActivity(StringBuilder activity, WebSearchResponse response) {
        if (activity.length() > 0) {
            activity.append("\n\n");
        }

        activity.append("**Searched**\n");
        activity.append("- ").append(StringUtils.defaultIfBlank(response.query(), "latest information")).append("\n\n");
        activity.append("**Sources**\n");
        if (response.results().isEmpty()) {
            activity.append("- No source URLs returned.");
            return;
        }

        for (int i = 0; i < response.results().size(); i++) {
            appendWebSearchResultActivity(activity, i + 1, response.results().get(i));
        }
    }

    private void appendWebSearchResultActivity(StringBuilder activity, int index, WebSearchResult result) {
        String url = StringUtils.defaultString(result.url());
        String title = StringUtils.defaultIfBlank(result.title(), StringUtils.defaultIfBlank(result.domain(), url));
        activity.append("%d. [%s](%s)".formatted(index, escapeMarkdownLinkLabel(title), url));
        if (StringUtils.isNotBlank(result.snippet())) {
            activity.append(" — ").append(result.snippet().trim());
        }
        activity.append("\n");
    }

    private void appendBrowsedPageActivity(StringBuilder activity, List<BrowsedPage> pages) {
        if (ObjectUtils.isEmpty(pages)) {
            return;
        }

        if (activity.length() > 0) {
            activity.append("\n");
        }
        activity.append("\n**Sources**\n");
        for (int i = 0; i < pages.size(); i++) {
            BrowsedPage page = pages.get(i);
            String label = StringUtils.defaultIfBlank(page.title(), StringUtils.defaultIfBlank(page.domain(), page.url()));
            activity.append("%d. [%s](%s)".formatted(i + 1, escapeMarkdownLinkLabel(label), page.url()));
            if (!page.success()) {
                activity.append(" — failed: ").append(StringUtils.defaultIfBlank(page.error(), "unknown error"));
            } else if (StringUtils.isNotBlank(page.excerpt())) {
                activity.append(" — ").append(StringUtils.abbreviate(page.excerpt(), 220));
            }
            activity.append("\n");
        }
    }

    private String escapeMarkdownLinkLabel(String value) {
        return StringUtils.defaultString(value).replace("[", "\\[").replace("]", "\\]");
    }

    private void showWebSearchActivity(StreamingSession session, String webSearchActivity) {
        String normalizedActivity = normalizeWebSearchActivity(webSearchActivity);
        if (!session.isLive() || !isVisibleSession(session) || StringUtils.isBlank(normalizedActivity)) {
            return;
        }

        if (currentAssistantWebSearchBubble == null) {
            currentAssistantWebSearchBubble = new ActivityBubble("Web Search", WEB_SEARCH_COLLAPSED_BY_DEFAULT);
            addActivityBubble(currentAssistantWebSearchBubble, null);
        }

        currentAssistantWebSearchBubble.setVisible(true);
        currentAssistantWebSearchBubble.setText(normalizedActivity);
        refreshWebTranscript(true);
        scrollToBottom();
    }

    private void handleAgentToolActivity(StreamingSession session, AgentToolActivity activity) {
        if (session == null || activity == null || !session.isLive()) {
            return;
        }

        String formattedActivity = formatAgentToolActivity(activity);
        if (StringUtils.isBlank(formattedActivity)) {
            return;
        }

        session.agentToolActivities.add(activity);

        SwingUtilities.invokeLater(() -> {
            if (!session.isLive() || !isVisibleSession(session)) {
                return;
            }

            currentAssistantBubble = null;
            ActivityBubble toolBubble = currentAssistantAgentToolBubbles.computeIfAbsent(
                    agentToolBubbleKey(activity),
                    ignored -> createAgentToolBubble(activity)
            );
            toolBubble.setVisible(true);
            toolBubble.setTitle(formattedActivity);
            refreshWebTranscript(true);
            scrollToBottom();
        });
    }

    private ActivityBubble createAgentToolBubble(AgentToolActivity activity) {
        ActivityBubble bubble = new ActivityBubble(agentToolBubbleTitle(activity), AGENT_TOOLS_COLLAPSED_BY_DEFAULT);
        bubble.setCollapsible(false);
        addActivityBubble(bubble, null);
        return bubble;
    }

    private void addPersistedAgentToolBubble(AgentToolActivity activity) {
        String title = formatAgentToolActivity(activity);
        if (StringUtils.isBlank(title)) {
            return;
        }

        ActivityBubble bubble = new ActivityBubble(title, AGENT_TOOLS_COLLAPSED_BY_DEFAULT);
        bubble.setCollapsible(false);
        addActivityBubble(bubble, null);
    }

    private String agentToolBubbleKey(AgentToolActivity activity) {
        String id = StringUtils.trimToEmpty(activity.invocationId());
        return StringUtils.isNotBlank(id)
                ? id
                : "%s:%s".formatted(
                        StringUtils.defaultIfBlank(activity.toolName(), "tool"),
                        StringUtils.defaultString(activity.argumentsSummary())
                );
    }

    private String agentToolBubbleTitle(AgentToolActivity activity) {
        return formatAgentToolActivity(activity);
    }

    private String formatAgentToolActivity(AgentToolActivity activity) {
        String icon = switch (activity.status()) {
            case STARTED -> "•";
            case SUCCEEDED -> "✓";
            case FAILED -> "✗";
            case SKIPPED -> "↷";
        };
        String target = compactToolTarget(activity.argumentsSummary());
        String message = StringUtils.isBlank(activity.message())
                ? ""
                : " — %s".formatted(sanitizeToolActivityText(activity.message()));
        return "%s %s%s%s".formatted(
                icon,
                sanitizeToolActivityText(activity.toolName()),
                StringUtils.isBlank(target) ? "" : " %s".formatted(target),
                message
        );
    }

    private String compactToolTarget(String argumentsSummary) {
        String summary = sanitizeToolActivityText(argumentsSummary);
        if (StringUtils.isBlank(summary) || Strings.CS.equals(summary, "arguments omitted")) {
            return "";
        }

        return summary
                .replaceFirst("^path=", "")
                .replaceFirst("^command=", "")
                .replaceFirst("^query=", "")
                .replace(", pattern=", " ")
                .replace(", query=", " ")
                .replace(", edits=", " edits=");
    }

    private String sanitizeToolActivityText(String value) {
        return StringUtils.defaultString(value).replace("`", "ʼ");
    }

    private Message prepareUserMessage(
            ComposerState composerState,
            ProviderSelectionSnapshot providerSnapshot,
            BooleanSupplier isCancelled
    ) throws IOException {
        ensureNotCancelled(isCancelled);

        List<ContentPart> parts = new ArrayList<>();

        if (!composerState.activeSkills().isEmpty()) {
            String skillDirective = "Activated skills: %s".formatted(String.join(", ", composerState.activeSkills()));
            parts.add(new TextPart(skillDirective));
        }

        String text = composerState.text().trim();
        if (!text.isEmpty()) {
            parts.add(new TextPart(text));
        }

        for (ComposerAttachment attachment : composerState.attachments()) {
            ensureNotCancelled(isCancelled);
            parts.add(toAttachmentPart(attachment, isCancelled));
        }

        ensureNotCancelled(isCancelled);
        List<String> fallbackNotices = buildFallbackNotices(
                composerState.attachments(),
                providerSnapshot,
                isCancelled
        );
        MessageMeta meta = new MessageMeta(composerState.activeSkills(), fallbackNotices, false, "");
        return new Message(Role.USER, parts, Instant.now(), meta);
    }

    private ContentPart toAttachmentPart(ComposerAttachment attachment, BooleanSupplier isCancelled) throws IOException {
        ensureNotCancelled(isCancelled);
        AttachmentRef attachmentRef = attachmentStager.stage(attachment);
        return attachment.image()
                ? new ImagePart(attachmentRef, null, null)
                : new FilePart(attachmentRef);
    }

    private List<String> buildFallbackNotices(
            List<ComposerAttachment> attachments,
            ProviderSelectionSnapshot providerSnapshot,
            BooleanSupplier isCancelled
    ) {
        if (ObjectUtils.isEmpty(attachments)) {
            return emptyList();
        }

        ensureNotCancelled(isCancelled);

        List<String> notices = new ArrayList<>();
        boolean hasImage = attachments.stream().anyMatch(ComposerAttachment::image);
        boolean hasFile = attachments.stream().anyMatch(attachment -> !attachment.image());

        boolean supportsImageInput = ProviderCapabilityResolver.supportsImageInput(
                providerSnapshot.capabilities(),
                providerSnapshot.providerName(),
                providerSnapshot.modelId(),
                providerSnapshot.baseUrl(),
                providerSnapshot.apiKey()
        );
        ensureNotCancelled(isCancelled);

        boolean supportsFileInput = ProviderCapabilityResolver.supportsFileInput(
                providerSnapshot.capabilities(),
                providerSnapshot.providerName(),
                providerSnapshot.modelId()
        );

        if (hasImage && !supportsImageInput) {
            notices.add(buildImageFallbackNotice(providerSnapshot));
        }

        if (hasFile && !supportsFileInput) {
            notices.add(buildFileFallbackNotice(providerSnapshot, supportsImageInput));
        }

        return notices;
    }

    private String buildImageFallbackNotice(ProviderSelectionSnapshot providerSnapshot) {
        String providerLabel = StringUtils.defaultIfBlank(providerSnapshot.providerName(), "Selected provider");
        String modelLabel = StringUtils.defaultIfBlank(providerSnapshot.modelId(), "current model");

        return "%s (%s) is currently mapped to text-only image references.".formatted(providerLabel, modelLabel);
    }

    private String buildFileFallbackNotice(
            ProviderSelectionSnapshot providerSnapshot,
            boolean supportsImageInput
    ) {
        String providerLabel = StringUtils.defaultIfBlank(providerSnapshot.providerName(), "Selected provider");
        String modelLabel = StringUtils.defaultIfBlank(providerSnapshot.modelId(), "current model");

        if (supportsImageInput) {
            return "Extracted text sent; native file upload is not mapped for %s (%s).".formatted(
                    providerLabel,
                    modelLabel
            );
        }

        return "Extracted text sent; native file upload is unavailable for %s (%s).".formatted(providerLabel, modelLabel);
    }

    private void updateThinkingToggleAvailability(long selectionId) {
        ProviderRegistry.ProviderDef providerDef = selectedProviderDef();
        if (providerDef == null || StringUtils.isBlank(selectedModelId)) {
            inputBar.setThinkingAvailable(false);
            return;
        }

        boolean fallbackSupportsThinking = ProviderCapabilityResolver.supportsReasoning(
                providerDef.capabilities(),
                providerDef.name(),
                selectedModelId
        );
        inputBar.setThinkingAvailable(fallbackSupportsThinking);

        if (StringUtils.isBlank(providerDef.baseUrl())) {
            return;
        }

        String providerNameSnapshot = providerDef.name();
        String modelIdSnapshot = selectedModelId;
        ProviderCapabilities capabilitiesSnapshot = providerDef.capabilities();
        String baseUrlSnapshot = providerDef.baseUrl();

        Thread.startVirtualThread(() -> {
            try {
                String apiKey = resolveProviderApiKey(providerDef);
                boolean resolvedSupportsThinking = ProviderCapabilityResolver.supportsReasoning(
                        capabilitiesSnapshot,
                        providerNameSnapshot,
                        modelIdSnapshot,
                        baseUrlSnapshot,
                        apiKey
                );

                SwingUtilities.invokeLater(() -> {
                    if (!isSelectedModel(selectionId, providerNameSnapshot, modelIdSnapshot)) {
                        return;
                    }

                    inputBar.setThinkingAvailable(resolvedSupportsThinking);
                });
            } catch (Exception e) {
                log.debug("Failed to refresh thinking capability for {}::{}",
                        providerNameSnapshot, modelIdSnapshot, e);
            }
        });
    }

    private void updateWebSearchAvailability(long selectionId) {
        ProviderRegistry.ProviderDef providerDef = selectedProviderDef();
        if (providerDef == null || StringUtils.isBlank(selectedModelId)) {
            inputBar.setWebSearchLockedEnabled(false);
            inputBar.setWebSearchOptions(emptyList(), null);
            return;
        }

        WebSearchAvailability availability = webSearchAvailabilityResolver.resolve(
                providerDef,
                selectedModelId,
                new ArrayList<>(providerMap.values())
        );
        inputBar.setWebSearchLockedEnabled(Strings.CS.equals(providerDef.name(), "Perplexity"));
        inputBar.setWebSearchOptions(availability.options(), availability.defaultOptionId());

        if (StringUtils.isBlank(providerDef.baseUrl())) {
            return;
        }

        String providerNameSnapshot = providerDef.name();
        String modelIdSnapshot = selectedModelId;
        ProviderCapabilities capabilitiesSnapshot = providerDef.capabilities();
        String baseUrlSnapshot = providerDef.baseUrl();
        List<ProviderRegistry.ProviderDef> providersSnapshot = new ArrayList<>(providerMap.values());

        Thread.startVirtualThread(() -> {
            try {
                String apiKey = resolveProviderApiKey(providerDef);
                boolean resolvedSupportsNative = ProviderCapabilityResolver.supportsRuntimeNativeWebSearch(
                        capabilitiesSnapshot,
                        providerNameSnapshot,
                        modelIdSnapshot,
                        baseUrlSnapshot,
                        apiKey
                );
                WebSearchAvailability resolvedAvailability = webSearchAvailabilityResolver.resolve(
                        providerDef,
                        modelIdSnapshot,
                        providersSnapshot,
                        resolvedSupportsNative
                );

                SwingUtilities.invokeLater(() -> {
                    if (!isSelectedModel(selectionId, providerNameSnapshot, modelIdSnapshot)) {
                        return;
                    }

                    inputBar.setWebSearchOptions(
                            resolvedAvailability.options(),
                            resolvedAvailability.defaultOptionId()
                    );
                });
            } catch (Exception e) {
                log.debug("Failed to refresh web search capability for {}::{}",
                        providerNameSnapshot, modelIdSnapshot, e);
            }
        });
    }

    private void updateAgentToggleAvailability(long selectionId) {
        ProviderRegistry.ProviderDef providerDef = selectedProviderDef();
        if (providerDef == null || StringUtils.isBlank(selectedModelId)) {
            inputBar.setAgentModeAvailable(false);
            return;
        }

        boolean fallbackSupportsTools = ProviderCapabilityResolver.supportsToolInvocation(
                providerDef.capabilities(),
                providerDef.name(),
                selectedModelId
        );
        inputBar.setAgentModeAvailable(fallbackSupportsTools);

        if (StringUtils.isBlank(providerDef.baseUrl())) {
            return;
        }

        String providerNameSnapshot = providerDef.name();
        String modelIdSnapshot = selectedModelId;
        ProviderCapabilities capabilitiesSnapshot = providerDef.capabilities();
        String baseUrlSnapshot = providerDef.baseUrl();

        Thread.startVirtualThread(() -> {
            try {
                String apiKey = resolveProviderApiKey(providerDef);
                boolean resolvedSupportsTools = ProviderCapabilityResolver.supportsToolInvocation(
                        capabilitiesSnapshot,
                        providerNameSnapshot,
                        modelIdSnapshot,
                        baseUrlSnapshot,
                        apiKey
                );

                SwingUtilities.invokeLater(() -> {
                    if (!isSelectedModel(selectionId, providerNameSnapshot, modelIdSnapshot)) {
                        return;
                    }

                    inputBar.setAgentModeAvailable(resolvedSupportsTools);
                });
            } catch (Exception e) {
                log.debug("Failed to refresh agent capability for {}::{}",
                        providerNameSnapshot, modelIdSnapshot, e);
            }
        });
    }

    private boolean isSelectedModel(long selectionId, String providerName, String modelId) {
        return providerSelectionCounter.get() == selectionId
                && Strings.CS.equals(selectedProviderName, providerName)
                && Strings.CS.equals(selectedModelId, modelId);
    }

    private ProviderSelectionSnapshot captureProviderSelection() {
        ProviderRegistry.ProviderDef providerDef = selectedProviderDef();
        ProviderCapabilities capabilities = providerDef == null ? null : providerDef.capabilities();
        String baseUrl = providerDef == null ? null : providerDef.baseUrl();

        return new ProviderSelectionSnapshot(
                selectedProviderName,
                selectedModelId,
                capabilities,
                baseUrl,
                currentProviderApiKey
        );
    }

    private String resolveProviderApiKey(ProviderRegistry.ProviderDef providerDef) {
        if (providerDef == null) {
            return null;
        }

        String apiKey = CredentialResolver.resolveApiKey(providerDef.envVar(), null);
        if (StringUtils.isNotBlank(apiKey)) {
            return apiKey;
        }

        if (Strings.CS.equals(providerDef.name(), "OpenAI Codex")) {
            return codexAuthResolver.resolveBearerTokenOrNull();
        }

        if (Strings.CS.equals(providerDef.name(), "GitHub Copilot")) {
            return copilotAuthResolver.resolveBearerTokenOrNull();
        }

        return null;
    }

    private void ensureNotCancelled(BooleanSupplier isCancelled) {
        if (isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new SendCancelledException();
        }
    }

    private ProviderRegistry.ProviderDef selectedProviderDef() {
        if (StringUtils.isBlank(selectedProviderName)) {
            return null;
        }

        return providerMap.get(selectedProviderName);
    }

    private String formatUserBubbleText(Message message) {
        List<String> lines = new ArrayList<>();
        if (message.meta() != null && !message.meta().activeSkills().isEmpty()) {
            lines.add("[SKILL] %s".formatted(String.join(", ", message.meta().activeSkills())));
        }

        if (message.meta() != null) {
            message.meta().fallbackNotices().stream()
                    .map(notice -> "[FALLBACK] %s".formatted(notice))
                    .forEach(lines::add);
        }

        userTextLines(message).forEach(lines::add);
        return lines.isEmpty() ? "" : String.join("\n", lines);
    }

    private List<String> userTextLines(Message message) {
        boolean suppressSkillDirective = message.meta() != null && !message.meta().activeSkills().isEmpty();

        List<String> lines = message.parts().stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .filter(StringUtils::isNotBlank)
                .flatMap(String::lines)
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !suppressSkillDirective || !line.startsWith("Activated skills:"))
                .toList();
        if (!lines.isEmpty()) {
            return lines;
        }

        if (!message.parts().isEmpty() || message.content().isBlank()) {
            return emptyList();
        }

        return message.content().lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !suppressSkillDirective || !line.startsWith("Activated skills:"))
                .collect(joining("\n"))
                .lines()
                .toList();
    }

    private void addUserBubble(Message message, int messageIndex) {
        ChatMessageView bubble = createMessageView(Role.USER);
        bubble.component().putClientProperty(MESSAGE_VIEW_PROPERTY, bubble);
        bubble.component().putClientProperty(MESSAGE_INDEX_PROPERTY, messageIndex);
        bubble.component().putClientProperty(MESSAGE_META_PROPERTY, message.meta());
        bubble.setRenderMode(renderMode);
        bubble.setText(formatUserBubbleText(message));
        bubble.setMaxContentWidth(userBubbleMaxContentWidth());
        installBubbleContextMenu(bubble);
        addMessageComponent(Role.USER, bubble.component(), createAttachmentChipsPanel(userAttachmentRefs(message)));
    }

    private void refreshUserBubbleMaxWidths() {
        int maxWidth = userBubbleMaxContentWidth();
        for (ChatMessageView bubble : collectBubbles()) {
            if (bubble.getRole() == Role.USER) {
                bubble.setMaxContentWidth(maxWidth);
            }
        }
    }

    private int userBubbleMaxContentWidth() {
        int viewport = 0;
        if (scrollPane != null && scrollPane.getViewport() != null) {
            viewport = scrollPane.getViewport().getWidth();
        }
        if (viewport <= 0) {
            viewport = 800;
        }
        int columnWidth = chatColumnAvailableWidth();
        int reserved = USER_LEFT_GUTTER + USER_BUBBLE_INSET + USER_ROW_PADDING;
        int preferredWidth = Math.round(columnWidth * 0.72f);
        return Math.max(160, Math.min(columnWidth - reserved, preferredWidth));
    }

    private static final int USER_LEFT_GUTTER = 120;
    private static final int USER_BUBBLE_INSET = 28;
    private static final int USER_ROW_PADDING = 24;

    private List<AttachmentRef> userAttachmentRefs(Message message) {
        return message.parts().stream()
                .map(this::attachmentRef)
                .filter(ref -> ref != null && (!ref.originalName().isBlank() || !ref.storagePath().isBlank()))
                .toList();
    }

    private AttachmentRef attachmentRef(ContentPart part) {
        if (part instanceof FilePart filePart) {
            return filePart.attachmentRef();
        }
        if (part instanceof ImagePart imagePart) {
            return imagePart.attachmentRef();
        }
        if (part instanceof GeneratedImagePart generatedImagePart) {
            return generatedImagePart.attachmentRef();
        }
        return null;
    }

    private JComponent createAttachmentChipsPanel(List<AttachmentRef> attachmentRefs) {
        if (attachmentRefs.isEmpty()) {
            return null;
        }

        JPanel chipsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        chipsPanel.setOpaque(false);
        attachmentRefs.forEach(ref -> chipsPanel.add(createAttachmentComponent(ref)));
        return chipsPanel;
    }

    private JComponent createAttachmentComponent(AttachmentRef attachmentRef) {
        if (isImageAttachment(attachmentRef)) {
            return new ImageAttachmentPreview(attachmentRef);
        }
        return new FileAttachmentChip(attachmentRef);
    }

    private boolean isImageAttachment(AttachmentRef attachmentRef) {
        if (attachmentRef == null) {
            return false;
        }
        String mime = attachmentRef.mimeType();
        return mime != null && mime.startsWith("image/");
    }

    private void addMessageComponent(Role role, JComponent primaryContent, JComponent topContent) {
        JPanel wrapper = createMessageWrapper(role, primaryContent, topContent);
        addMessageWrapper(wrapper);
    }

    private JPanel createMessageWrapper(Role role, JComponent primaryContent, JComponent topContent) {
        int vgap = topContent != null ? 8 : 0;
        JPanel wrapper = new JPanel(new BorderLayout(0, vgap));
        wrapper.setOpaque(false);
        wrapper.putClientProperty(MESSAGE_ROLE_PROPERTY, role);
        applyMessageWrapperBorder(wrapper, role);
        if (topContent != null) {
            wrapper.add(topContent, BorderLayout.NORTH);
        }

        ChatMessageView bubble = chatMessageView(primaryContent);
        if (bubble != null) {
            JPanel hoverGroup = new JPanel(new BorderLayout());
            hoverGroup.setOpaque(false);
            hoverGroup.add(bubble.component(), BorderLayout.CENTER);
            JComponent actionBar = createBubbleActionBar(bubble, role);
            hoverGroup.add(actionBar, BorderLayout.SOUTH);
            installActionBarHoverListener(hoverGroup, actionBar);

            if (role == Role.USER) {
                JPanel rightAlignRow = new JPanel(new BorderLayout());
                rightAlignRow.setOpaque(false);
                rightAlignRow.add(hoverGroup, BorderLayout.EAST);
                wrapper.add(rightAlignRow, BorderLayout.CENTER);
            } else {
                wrapper.add(hoverGroup, BorderLayout.CENTER);
            }
        } else {
            wrapper.add(primaryContent, BorderLayout.CENTER);
        }
        return wrapper;
    }

    private JComponent createBubbleActionBar(ChatMessageView bubble, Role role) {
        int alignment = role == Role.USER ? FlowLayout.RIGHT : FlowLayout.LEFT;
        JPanel bar = new JPanel(new FlowLayout(alignment, 2, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        bar.putClientProperty(MESSAGE_ACTION_BAR_PROPERTY, true);
        bar.putClientProperty(MESSAGE_VIEW_PROPERTY, bubble);
        bar.putClientProperty(MESSAGE_ROLE_PROPERTY, role);
        updateBubbleActionBar(bar, bubble, role);
        return bar;
    }

    private void updateBubbleActionBar(JPanel bar, ChatMessageView bubble, Role role) {
        boolean buttonsWereVisible = Arrays.stream(bar.getComponents()).anyMatch(Component::isVisible);
        bar.removeAll();
        bar.add(createCopyMessageButton(bubble));
        if (canReadAloud(bubble, role) || textToSpeechService.isReadAloudActive(swingReadAloudKey(bubble))) {
            bar.add(createReadAloudButton(bubble));
        }
        bar.add(createRegenerateButton(bubble));
        if (role == Role.USER) {
            bar.add(createEditMessageButton(bubble));
        }
        int buttonCount = bar.getComponentCount();
        int buttonGapWidth = Math.max(0, buttonCount - 1) * 2;
        Dimension size = new Dimension(BUBBLE_ACTION_BUTTON_SIZE * buttonCount + buttonGapWidth, BUBBLE_ACTION_BAR_HEIGHT);
        bar.setPreferredSize(size);
        bar.setMinimumSize(size);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUBBLE_ACTION_BAR_HEIGHT));
        setBubbleActionButtonsVisible(bar, buttonsWereVisible);
        bar.revalidate();
        bar.repaint();
    }

    private JButton createRegenerateButton(ChatMessageView bubble) {
        Icon icon = chatMenuIcon("/icons/chat/refresh-cw.svg");
        String tooltip = bubble.getRole() == Role.USER
                ? "Regenerate response"
                : "Regenerate this response";
        return createBubbleActionButton(icon, tooltip, () -> regenerateFromBubble(bubble));
    }

    private JButton createEditMessageButton(ChatMessageView bubble) {
        Icon icon = chatMenuIcon("/icons/chat/pencil.svg");
        return createBubbleActionButton(icon, "Edit message", () -> startEditingUserMessage(bubble));
    }

    private JButton createCopyMessageButton(ChatMessageView bubble) {
        Icon copyIcon = chatMenuIcon("/icons/input/copy.svg");
        Icon confirmIcon = chatMenuIcon("/icons/chat/check.svg");
        JButton button = createBubbleActionButton(copyIcon, "Copy message", null);
        button.addActionListener(e -> {
            copyBubbleTextToClipboard(bubble);
            button.setIcon(confirmIcon);
            Timer revertTimer = new Timer(1000, event -> button.setIcon(copyIcon));
            revertTimer.setRepeats(false);
            revertTimer.start();
        });
        return button;
    }

    private JButton createReadAloudButton(ChatMessageView bubble) {
        boolean active = textToSpeechService.isReadAloudActive(swingReadAloudKey(bubble));
        Icon icon = chatMenuIcon(active ? "/icons/chat/player-stop.svg" : "/icons/chat/volume-2.svg");
        return createBubbleActionButton(icon, active ? "Stop" : "Read aloud", () -> readBubbleAloud(bubble));
    }

    private boolean canReadAloud(ChatMessageView bubble, Role role) {
        return role == Role.ASSISTANT
                && !speechToTextService.active()
                && textToSpeechService.isReadAloudAvailable()
                && StringUtils.isNotBlank(speakableText(bubble));
    }

    private void readBubbleAloud(ChatMessageView bubble) {
        if (speechToTextService.active()) {
            showReadAloudStatus("Finish or cancel transcription before using read aloud.");
            return;
        }
        textToSpeechService.readAloud(
                swingReadAloudKey(bubble),
                speakableText(bubble),
                this::showReadAloudError,
                this::showReadAloudStatus,
                this::refreshReadAloudControls
        );
    }

    private String swingReadAloudKey(ChatMessageView bubble) {
        return "swing:%d".formatted(System.identityHashCode(bubble));
    }

    private String speakableText(ChatMessageView bubble) {
        String renderedText = StringUtils.trimToEmpty(bubble.contentTextSnapshot());
        return StringUtils.defaultIfBlank(renderedText, bubble.getFullText());
    }

    private void showReadAloudError(String message) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, message, "Read aloud", JOptionPane.WARNING_MESSAGE));
    }

    private void showReadAloudStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            if (StringUtils.isBlank(message)) {
                readAloudStatusLabel.setVisible(false);
                return;
            }
            readAloudStatusLabel.setText(message);
            readAloudStatusLabel.setBackground(UIManager.getColor("Panel.background"));
            readAloudStatusLabel.setForeground(UIManager.getColor("Label.foreground"));
            readAloudStatusLabel.setVisible(true);
            layoutReadAloudStatusLabel();
            readAloudStatusTimer.restart();
        });
    }

    private void stopReadAloudPlayback() {
        textToSpeechService.stop();
        readAloudStatusTimer.stop();
        readAloudStatusLabel.setVisible(false);
    }

    private void installActionBarHoverListener(JComponent hoverGroup, JComponent actionBar) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setBubbleActionButtonsVisible(actionBar, true);
                actionBar.revalidate();
                actionBar.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!hoverGroup.isShowing()) {
                    return;
                }
                Point screenPoint = new Point(e.getXOnScreen(), e.getYOnScreen());
                SwingUtilities.convertPointFromScreen(screenPoint, hoverGroup);
                if (!hoverGroup.contains(screenPoint)) {
                    setBubbleActionButtonsVisible(actionBar, false);
                    actionBar.revalidate();
                    actionBar.repaint();
                }
            }
        };
        addMouseListenerRecursively(hoverGroup, adapter);
    }

    private void addMouseListenerRecursively(Component component, MouseAdapter adapter) {
        component.addMouseListener(adapter);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                addMouseListenerRecursively(child, adapter);
            }
        }
    }

    private void setBubbleActionButtonsVisible(JComponent actionBar, boolean visible) {
        Arrays.stream(actionBar.getComponents()).forEach(component -> component.setVisible(visible));
    }

    private JButton createBubbleActionButton(Icon icon, String tooltip, Runnable action) {
        JButton button = new JButton();
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:8");
        button.setIcon(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        Dimension size = new Dimension(BUBBLE_ACTION_BUTTON_SIZE, BUBBLE_ACTION_BUTTON_SIZE);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        if (action != null) {
            button.addActionListener(e -> action.run());
        }
        return button;
    }

    private void copyBubbleTextToClipboard(ChatMessageView bubble) {
        copyTextToClipboard(bubble.getFullText());
    }

    public boolean canCopyRecentResponse() {
        return history.stream().anyMatch(message -> message.role() == Role.ASSISTANT && StringUtils.isNotBlank(message.content()));
    }

    public void copyRecentResponseToClipboard() {
        history.stream()
                .filter(message -> message.role() == Role.ASSISTANT && StringUtils.isNotBlank(message.content()))
                .reduce((first, second) -> second)
                .map(Message::content)
                .ifPresent(this::copyTextToClipboard);
    }

    private void copyTextToClipboard(String text) {
        if (StringUtils.isBlank(text)) {
            return;
        }
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
    }

    private void startEditingUserMessage(ChatMessageView bubble) {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before editing messages.");
            return;
        }
        if (isVisibleConversationBusy()) {
            return;
        }

        int messageIndex = messageIndex(bubble);
        if (messageIndex < 0 || messageIndex >= history.size() || history.get(messageIndex).role() != Role.USER) {
            return;
        }

        if (editingUserMessage != null) {
            cancelEditingUserMessage();
        }

        Message message = history.get(messageIndex);
        editingUserMessage = new EditingUserMessage(messageIndex, inputBar.getComposerState());
        inputBar.clear();
        inputBar.setText(editableUserText(message));
        composerPanel.setComposer(new EditComposerPanel(
                inputBar,
                this::saveEditedUserMessageOnly,
                this::saveEditedUserMessageAndRegenerate,
                this::cancelEditingUserMessage
        ));
        refreshComposerAvailability();
        inputBar.requestInputFocus();
    }

    private int messageIndex(ChatMessageView bubble) {
        if (bubble == null) {
            return -1;
        }
        Object value = bubble.component().getClientProperty(MESSAGE_INDEX_PROPERTY);
        return value instanceof Integer index ? index : -1;
    }

    private String editableUserText(Message message) {
        return String.join("\n", userTextLines(message));
    }

    private void cancelEditingUserMessage() {
        EditingUserMessage state = editingUserMessage;
        if (state == null) {
            return;
        }

        editingUserMessage = null;
        composerPanel.setComposer(inputBar);
        inputBar.setComposerState(state.savedComposerState());
        refreshComposerAvailability();
        inputBar.requestInputFocus();
    }

    private void saveEditedUserMessageOnly() {
        EditingUserMessage state = editingUserMessage;
        if (state == null) {
            return;
        }

        Message replacement = editedReplacementMessage(state.messageIndex());
        if (replacement == null) {
            return;
        }

        history.set(state.messageIndex(), replacement);
        clearPendingAssistantRecovery(resolveConversationId());
        finishEditingAndRestoreComposer(state);
        loadHistory(new ArrayList<>(history));
        notifyMessageSubmitted();
        inputBar.requestInputFocus();
    }

    private void saveEditedUserMessageAndRegenerate() {
        EditingUserMessage state = editingUserMessage;
        if (state == null) {
            return;
        }
        if (currentProviderResolving) {
            inputBar.showValidationMessage("Selected provider is still loading. Try again in a moment.");
            return;
        }
        if (currentProvider == null) {
            inputBar.showValidationMessage("Select a model/provider before regenerating.");
            return;
        }
        if (isVisibleConversationBusy()) {
            return;
        }

        Message replacement = editedReplacementMessage(state.messageIndex());
        if (replacement == null) {
            return;
        }

        history.set(state.messageIndex(), replacement);
        UUID conversationId = resolveConversationId();
        clearPendingAssistantRecovery(conversationId);
        int keepCount = state.messageIndex() + 1;
        if (history.size() > keepCount) {
            history.subList(keepCount, history.size()).clear();
        }

        finishEditingAndRestoreComposer(state);
        loadHistory(new ArrayList<>(history));
        if (historyTruncatedListener != null && conversationId != null) {
            historyTruncatedListener.accept(new HistoryTruncatedEvent(conversationId, keepCount));
        }
        notifyMessageSubmitted();

        if (inputBar.isWebSearchEnabled()) {
            currentAssistantActivityBubble = new ActivityBubble(THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING);
            currentAssistantActivityBubble.setStreaming(true);
            currentAssistantActivityBubble.setVisible(false);
            addActivityBubble(currentAssistantActivityBubble, null);

            currentAssistantWebSearchBubble = new ActivityBubble("Web Search", WEB_SEARCH_COLLAPSED_BY_DEFAULT);
            currentAssistantWebSearchBubble.setVisible(false);
            addActivityBubble(currentAssistantWebSearchBubble, null);
        }

        currentAssistantBubble = createMessageView(Role.ASSISTANT);
        addAssistantBubble(currentAssistantBubble, null);
        startAssistantStream(conversationId, currentProvider);
    }

    private Message editedReplacementMessage(int messageIndex) {
        if (messageIndex < 0 || messageIndex >= history.size()) {
            return null;
        }

        Message original = history.get(messageIndex);
        String editedText = inputBar.getRawText().trim();
        boolean hasAttachments = original.parts().stream().anyMatch(part -> !(part instanceof TextPart));
        if (StringUtils.isBlank(editedText) && !hasAttachments) {
            inputBar.showValidationMessage("Message text cannot be empty.");
            return null;
        }

        List<ContentPart> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(editedText)) {
            parts.add(new TextPart(editedText));
        }
        original.parts().stream()
                .filter(part -> !(part instanceof TextPart))
                .forEach(parts::add);
        return new Message(original.role(), parts, original.timestamp(), original.meta());
    }

    private void finishEditingAndRestoreComposer(EditingUserMessage state) {
        editingUserMessage = null;
        composerPanel.setComposer(inputBar);
        inputBar.setComposerState(state.savedComposerState());
        refreshComposerAvailability();
    }

    private boolean canRegenerateFrom(ChatMessageView bubble) {
        if (currentProvider == null || isVisibleConversationBusy()) {
            return false;
        }
        int historyMessageIndex = messageIndex(bubble);
        if (historyMessageIndex < 0 || historyMessageIndex >= history.size()) {
            return false;
        }
        int keepCount = bubble.getRole() == Role.USER ? historyMessageIndex + 1 : historyMessageIndex;
        return keepCount > 0 && history.get(keepCount - 1).role() == Role.USER;
    }

    public boolean canRegenerateRecentResponse() {
        if (currentProvider == null || isVisibleConversationBusy()) {
            return false;
        }
        List<ChatMessageView> bubbles = collectBubbles();
        return !bubbles.isEmpty() && canRegenerateFrom(bubbles.getLast());
    }

    public void regenerateRecentResponse() {
        List<ChatMessageView> bubbles = collectBubbles();
        if (!bubbles.isEmpty()) {
            regenerateFromBubble(bubbles.getLast());
        }
    }

    private void regenerateFromBubble(ChatMessageView bubble) {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before regenerating.");
            return;
        }
        if (currentProviderResolving) {
            inputBar.showValidationMessage("Selected provider is still loading. Try again in a moment.");
            return;
        }
        if (currentProvider == null) {
            inputBar.showValidationMessage("Select a model/provider before regenerating.");
            return;
        }
        if (isVisibleConversationBusy()) {
            return;
        }

        int historyMessageIndex = messageIndex(bubble);
        if (historyMessageIndex < 0 || historyMessageIndex >= history.size()) {
            return;
        }

        int keepCount = bubble.getRole() == Role.USER ? historyMessageIndex + 1 : historyMessageIndex;
        if (keepCount <= 0 || history.get(keepCount - 1).role() != Role.USER) {
            return;
        }

        UUID conversationId = resolveConversationId();
        clearPendingAssistantRecovery(conversationId);
        truncateHistoryAndBubbles(keepCount);
        if (historyTruncatedListener != null && conversationId != null) {
            historyTruncatedListener.accept(new HistoryTruncatedEvent(conversationId, keepCount));
        }

        if (inputBar.isWebSearchEnabled()) {
            currentAssistantActivityBubble = new ActivityBubble(THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING);
            currentAssistantActivityBubble.setStreaming(true);
            currentAssistantActivityBubble.setVisible(false);
            addActivityBubble(currentAssistantActivityBubble, null);

            currentAssistantWebSearchBubble = new ActivityBubble("Web Search", WEB_SEARCH_COLLAPSED_BY_DEFAULT);
            currentAssistantWebSearchBubble.setVisible(false);
            addActivityBubble(currentAssistantWebSearchBubble, null);
        }

        currentAssistantBubble = createMessageView(Role.ASSISTANT);
        addAssistantBubble(currentAssistantBubble, null);
        startAssistantStream(conversationId, currentProvider);
    }

    private void truncateHistoryAndBubbles(int keepCount) {
        if (history.size() > keepCount) {
            history.subList(keepCount, history.size()).clear();
        }

        loadHistory(new ArrayList<>(history));
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        currentAssistantBubble = null;
    }

    private void addMessageWrapper(JPanel wrapper) {
        GridBagConstraints gbc = messageRowConstraints(messageRow++);
        messagesPanel.add(wrapper, gbc);
        finishMessageWrapperAdd();
    }

    private void addMessageWrapperBefore(JPanel wrapper, JComponent beforeComponent) {
        Component beforeWrapper = findMessagePanelChild(beforeComponent);
        if (beforeWrapper == null) {
            addMessageWrapper(wrapper);
            return;
        }

        removeBottomFiller();
        GridBagLayout layout = (GridBagLayout) messagesPanel.getLayout();
        int insertRow = layout.getConstraints(beforeWrapper).gridy;
        for (Component component : messagesPanel.getComponents()) {
            GridBagConstraints constraints = layout.getConstraints(component);
            if (constraints.gridy >= insertRow) {
                constraints.gridy++;
                layout.setConstraints(component, constraints);
            }
        }

        messagesPanel.add(wrapper, messageRowConstraints(insertRow));
        messageRow++;
        finishMessageWrapperAdd();
    }

    private void refreshMessageColumnInsets() {
        if (messagesPanel == null) {
            return;
        }

        for (Component component : messagesPanel.getComponents()) {
            if (component instanceof JPanel wrapper) {
                Object role = wrapper.getClientProperty(MESSAGE_ROLE_PROPERTY);
                if (role instanceof Role messageRole) {
                    applyMessageWrapperBorder(wrapper, messageRole);
                }
            }
        }
    }

    private void applyMessageWrapperBorder(JPanel wrapper, Role role) {
        int sideInset = messageColumnSideInset();
        wrapper.setBorder(role == Role.USER
                ? BorderFactory.createEmptyBorder(2, sideInset + USER_LEFT_GUTTER, 2, sideInset)
                : BorderFactory.createEmptyBorder(4, ASSISTANT_MESSAGE_SIDE_MARGIN, 4, ASSISTANT_MESSAGE_SIDE_MARGIN));
    }

    private int messageColumnSideInset() {
        return CHAT_COLUMN_SIDE_MARGIN;
    }

    private int chatColumnAvailableWidth() {
        int viewport = 0;
        if (scrollPane != null && scrollPane.getViewport() != null) {
            viewport = scrollPane.getViewport().getWidth();
        }
        if (viewport <= 0) {
            viewport = 800;
        }
        return Math.max(320, viewport - messageColumnSideInset() * 2);
    }

    private GridBagConstraints messageRowConstraints(int row) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTH;
        return gbc;
    }

    private Component findMessagePanelChild(Component component) {
        Component current = component;
        while (current != null && current.getParent() != messagesPanel) {
            current = current.getParent();
        }
        return current;
    }

    private void finishMessageWrapperAdd() {
        if (batchMessageRefresh) {
            return;
        }
        addBottomFiller();
        refreshMessageColumnInsets();
        messagesPanel.revalidate();
        refreshWebTranscript(true);
        messagesCardLayout.show(messagesContainer, CARD_CHAT);
        scrollToBottom();
    }

    public void reloadTextToSpeechSettings() {
        stopReadAloudPlayback();
        refreshBubbleActionBars();
        refreshWebTranscript(false, true);
    }

    public void reloadSpeechToTextSettings() {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before reloading Speech to Text settings.");
            return;
        }
        inputBar.setSpeechToTextAvailable(speechToTextService.available());
        refreshComposerAvailability();
    }

    public boolean isSpeechToTextActive() {
        return speechToTextService.active();
    }

    public void cancelSpeechToText() {
        speechToTextService.cancel(speechToTextCallbacks());
    }

    private void startSpeechToTextRecording() {
        if (editingUserMessage != null || conversationLoading || isVisibleConversationBusy()) {
            inputBar.showValidationMessage("Speech to Text is not available right now.");
            return;
        }
        stopReadAloudPlayback();
        speechToTextComposerSnapshot = inputBar.getComposerState();
        inputBar.showPreparingSpeechToTextState();
        speechToTextService.startRecording(speechToTextCallbacks());
        refreshBubbleActionBars();
        refreshWebTranscript(false, true);
    }

    private SpeechToTextService.Callbacks speechToTextCallbacks() {
        return new SpeechToTextService.Callbacks() {
            @Override
            public void stateChanged() {
                if (speechToTextService.recording()) {
                    inputBar.showRecordingState();
                } else if (speechToTextService.transcribing()) {
                    inputBar.showTranscribingState();
                } else {
                    inputBar.clearSpeechToTextState();
                    refreshComposerAvailability();
                }
                refreshBubbleActionBars();
                refreshWebTranscript(false, true);
            }

            @Override
            public void status(String message) {
                if (StringUtils.isNotBlank(message)) {
                    inputBar.showValidationMessage(message);
                }
            }

            @Override
            public void error(String message) {
                inputBar.setComposerState(speechToTextComposerSnapshot);
                inputBar.clearSpeechToTextState();
                inputBar.showValidationMessage(message);
                refreshComposerAvailability();
            }

            @Override
            public void transcript(String text) {
                inputBar.setComposerState(speechToTextComposerSnapshot);
                inputBar.appendTranscriptToRawSnapshot(speechToTextComposerSnapshot.text(), text);
                inputBar.clearSpeechToTextState();
                inputBar.requestInputFocus();
                refreshComposerAvailability();
            }

            @Override
            public void level(double rms, double peak) {
                inputBar.updateSpeechToTextLevel(rms, peak);
            }
        };
    }

    private void refreshComposerAvailability() {
        inputBar.setConversationBusy(conversationLoading || isVisibleConversationBusy());
        inputBar.setProviderReady(currentProvider != null && !currentProviderResolving);
        inputBar.setNormalComposeMode(editingUserMessage == null);
    }

    private void refreshBubbleActionBars() {
        refreshBubbleActionBars(messagesPanel);
    }

    private void refreshBubbleActionBars(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JPanel panel && Boolean.TRUE.equals(panel.getClientProperty(MESSAGE_ACTION_BAR_PROPERTY))) {
                ChatMessageView bubble = chatMessageView(panel);
                Object roleValue = panel.getClientProperty(MESSAGE_ROLE_PROPERTY);
                if (bubble != null && roleValue instanceof Role role) {
                    updateBubbleActionBar(panel, bubble, role);
                }
            }
            if (child instanceof Container nested) {
                refreshBubbleActionBars(nested);
            }
        }
    }

    private void refreshWebTranscript(boolean scrollToBottom) {
        refreshWebTranscript(scrollToBottom, false);
    }

    private void refreshWebTranscript(boolean scrollToBottom, boolean forceReload) {
        if (!isBrowserConversationEnabled() || messagesPanel == null) {
            return;
        }
        if (isSystemWebViewEnabled() && systemWebView.isDisposed()) {
            return;
        }
        if (isJcefBrowserViewEnabled() && jcefBrowserView.isDisposed()) {
            return;
        }

        int[] messageIndex = {0};
        List<ConversationEntry> entries = Arrays.stream(messagesPanel.getComponents())
                .filter(component -> !"filler".equals(component.getName()))
                .map(component -> toConversationEntry(component, messageIndex))
                .filter(Objects::nonNull)
                .toList();
        boolean shouldScrollToBottom = autoScrollEnabled && scrollToBottom;
        boolean showJumpButton = streaming;
        boolean readAloudAvailable = !speechToTextService.active() && textToSpeechService.isReadAloudAvailable();
        int activeReadAloudMessageIndex = activeWebReadAloudMessageIndex(entries);
        if (isSystemWebViewEnabled()) {
            systemWebView.setTranscript(entries, renderMode, detectDarkMode(), shouldScrollToBottom, showJumpButton, readAloudAvailable, activeReadAloudMessageIndex);
            if (forceReload) {
                systemWebView.reload(shouldScrollToBottom);
            }
            return;
        }
        jcefBrowserView.setTranscript(entries, renderMode, detectDarkMode(), shouldScrollToBottom, showJumpButton, readAloudAvailable, activeReadAloudMessageIndex);
        if (forceReload) {
            jcefBrowserView.reload(shouldScrollToBottom);
        }
    }

    private int activeWebReadAloudMessageIndex(List<ConversationEntry> entries) {
        return entries.stream()
                .mapToInt(ConversationEntry::messageIndex)
                .filter(index -> index >= 0 && textToSpeechService.isReadAloudActive(webReadAloudKey(index)))
                .findFirst()
                .orElse(-1);
    }

    private ConversationEntry toConversationEntry(Component component, int[] messageIndex) {
        ActivityBubble activityBubble = findActivityBubble(component);
        if (activityBubble != null) {
            return ConversationEntry.activity(
                    activityBubble.getTitleText(),
                    activityBubble.getFullText(),
                    activityBubble.isCollapsed()
            );
        }

        ChatMessageView messageView = findChatMessageView(component);
        if (messageView == null) {
            return null;
        }
        int fallbackMessageIndex = messageIndex[0]++;
        int historyMessageIndex = findHistoryMessageIndex(component);
        int transcriptMessageIndex = historyMessageIndex >= 0 ? historyMessageIndex : fallbackMessageIndex;
        List<ConversationAttachment> attachments = messageView.getRole() == Role.USER
                ? conversationAttachments(component)
                : emptyList();
        return ConversationEntry.message(
                messageView.getRole(),
                messageView.getFullText(),
                transcriptMessageIndex,
                attachments,
                messageView.contentPartsSnapshot(),
                messageMeta(component)
        );
    }

    private MessageMeta messageMeta(Component component) {
        if (component instanceof JComponent jComponent) {
            Object value = jComponent.getClientProperty(MESSAGE_META_PROPERTY);
            if (value instanceof MessageMeta meta) {
                return meta;
            }
        }
        if (!(component instanceof Container container)) {
            return MessageMeta.empty();
        }
        return Arrays.stream(container.getComponents())
                .map(this::messageMeta)
                .filter(meta -> !meta.equals(MessageMeta.empty()))
                .findFirst()
                .orElse(MessageMeta.empty());
    }

    private List<ConversationAttachment> conversationAttachments(Component component) {
        int historyMessageIndex = findHistoryMessageIndex(component);
        if (historyMessageIndex < 0 || historyMessageIndex >= history.size()) {
            return emptyList();
        }

        Message message = history.get(historyMessageIndex);
        if (message.role() != Role.USER) {
            return emptyList();
        }

        return userAttachmentRefs(message).stream()
                .map(ref -> new ConversationAttachment(
                        ref.storagePath(),
                        ref.originalName(),
                        ref.mimeType(),
                        ref.sizeBytes(),
                        isImageAttachment(ref)
                ))
                .toList();
    }

    private int findHistoryMessageIndex(Component component) {
        if (component instanceof JComponent jComponent) {
            Object value = jComponent.getClientProperty(MESSAGE_INDEX_PROPERTY);
            if (value instanceof Integer index) {
                return index;
            }
        }
        if (!(component instanceof Container container)) {
            return -1;
        }
        return Arrays.stream(container.getComponents())
                .mapToInt(this::findHistoryMessageIndex)
                .filter(index -> index >= 0)
                .findFirst()
                .orElse(-1);
    }

    private ActivityBubble findActivityBubble(Component component) {
        if (component instanceof ActivityBubble activityBubble) {
            return activityBubble;
        }
        if (!(component instanceof Container container)) {
            return null;
        }
        return Arrays.stream(container.getComponents())
                .map(this::findActivityBubble)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private ChatMessageView findChatMessageView(Component component) {
        if (component instanceof JComponent jComponent) {
            Object value = jComponent.getClientProperty(MESSAGE_VIEW_PROPERTY);
            if (value instanceof ChatMessageView messageView) {
                return messageView;
            }
        }
        if (!(component instanceof Container container)) {
            return null;
        }
        return Arrays.stream(container.getComponents())
                .map(this::findChatMessageView)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean detectDarkMode() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            return false;
        }
        float[] hsb = Color.RGBtoHSB(bg.getRed(), bg.getGreen(), bg.getBlue(), null);
        return hsb[2] <= 0.5f;
    }

    private JPanel buildEmptyStatePanel() {
        return new ChatEmptyStatePanel(
                promptQuickActions,
                new EmptyStateActions(
                        text -> runEmptyStateAction(() -> inputBar.setText(text)),
                        () -> runEmptyStateAction(() -> inputBar.requestAgentModeEnabled(true)),
                        () -> runEmptyStateAction(() -> inputBar.requestAttachmentPicker()),
                        () -> runEmptyStateAction(() -> inputBar.requestWebSearchEnabled(true)),
                        () -> runEmptyStateAction(inputBar::requestInputFocus)
                )
        );
    }

    private void runEmptyStateAction(Runnable action) {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before using quick actions.");
            return;
        }
        action.run();
    }

    private ChatMessageView createMessageView(Role role) {
        return messageViewFactory.create(role);
    }

    private void addAssistantBubble(ChatMessageView bubble, String text) {
        bubble.setRenderMode(renderMode);

        if (text != null) {
            bubble.setText(text);
        }

        bubble.component().putClientProperty(MESSAGE_VIEW_PROPERTY, bubble);
        bubble.component().putClientProperty(MESSAGE_META_PROPERTY, MessageMeta.empty());
        setMessageIndex(bubble, history.size());
        assistantBubbles.add(bubble);
        installBubbleContextMenu(bubble);
        addMessageComponent(Role.ASSISTANT, bubble.component(), null);
    }

    private void addBubble(ChatMessageView bubble, Message message, Role role, int messageIndex) {
        bubble.setRenderMode(renderMode);
        if (message != null) {
            bubble.setContentParts(message.parts());
        }
        bubble.component().putClientProperty(MESSAGE_VIEW_PROPERTY, bubble);
        bubble.component().putClientProperty(MESSAGE_META_PROPERTY, message == null ? MessageMeta.empty() : message.meta());
        if (role == Role.ASSISTANT) {
            setMessageIndex(bubble, messageIndex);
            assistantBubbles.add(bubble);
        }
        installBubbleContextMenu(bubble);
        addMessageComponent(role, bubble.component(), null);
    }

    private void setMessageIndex(ChatMessageView bubble, int messageIndex) {
        if (messageIndex >= 0) {
            bubble.component().putClientProperty(MESSAGE_INDEX_PROPERTY, messageIndex);
        }
    }

    private void addActivityBubble(ActivityBubble bubble, String text) {
        bubble.setRenderMode(renderMode);
        thinkingBubbles.add(bubble);

        if (text != null) {
            bubble.setText(text);
        }

        JPanel secondaryInfoWrapper = new JPanel(new BorderLayout());
        secondaryInfoWrapper.setOpaque(false);
        secondaryInfoWrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        secondaryInfoWrapper.add(bubble, BorderLayout.CENTER);

        JComponent beforeComponent = thinkingBubbleInsertTarget(bubble);
        if (beforeComponent == null) {
            addMessageComponent(Role.ASSISTANT, secondaryInfoWrapper, null);
        } else {
            addMessageWrapperBefore(createMessageWrapper(Role.ASSISTANT, secondaryInfoWrapper, null), beforeComponent);
        }
    }

    private JComponent thinkingBubbleInsertTarget(ActivityBubble bubble) {
        if (bubble == currentAssistantActivityBubble && currentAssistantWebSearchBubble != null) {
            return currentAssistantWebSearchBubble;
        }
        if (bubble == currentAssistantActivityBubble || bubble == currentAssistantWebSearchBubble) {
            return currentAssistantBubble == null ? null : currentAssistantBubble.component();
        }
        return null;
    }

    private void addBottomFiller() {
        removeBottomFiller();
        JPanel filler = new JPanel();
        filler.setName("filler");
        filler.setOpaque(false);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = messageRow;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.VERTICAL;
        messagesPanel.add(filler, gbc);
    }

    private void removeBottomFiller() {
        for (Component c : messagesPanel.getComponents()) {
            if ("filler".equals(c.getName())) {
                messagesPanel.remove(c);
                return;
            }
        }
    }

    private void installBubbleContextMenu(ChatMessageView bubble) {
        JPopupMenu popup = buildBubbleContextMenu(bubble);
        bubble.setContextMenu(popup);

        int shortcut = menuShortcutKeyMask();
        KeyStroke shiftCmdA = KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut | InputEvent.SHIFT_DOWN_MASK);
        bubble.installKeyBinding(shiftCmdA, "selectConversation", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectAndCopyConversation();
            }
        });
    }

    private JPopupMenu buildBubbleContextMenu(ChatMessageView bubble) {
        int shortcut = menuShortcutKeyMask();

        JMenuItem copyItem = buildChatMenuItem(
                "Copy",
                "/icons/input/copy.svg",
                KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcut),
                bubble::copySelectedContent
        );

        JMenuItem selectMessageItem = buildChatMenuItem(
                "Select Message",
                "/icons/chat/text-select.svg",
                KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut),
                () -> {
                    bubble.requestContentFocus();
                    bubble.selectAllContent();
                }
        );

        JMenuItem selectConversationItem = buildChatMenuItem(
                "Select Conversation",
                "/icons/chat/messages-square.svg",
                KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut | InputEvent.SHIFT_DOWN_MASK),
                this::selectAndCopyConversation
        );

        JMenuItem readAloudItem = buildChatMenuItem(
                "Read aloud",
                "/icons/chat/volume-2.svg",
                null,
                () -> readBubbleAloud(bubble)
        );

        String regenerateLabel = bubble.getRole() == Role.USER
                ? "Regenerate Response"
                : "Regenerate This Response";
        JMenuItem regenerateItem = buildChatMenuItem(
                regenerateLabel,
                "/icons/chat/refresh-cw.svg",
                null,
                () -> regenerateFromBubble(bubble)
        );

        JMenuItem clearChatItem = buildChatMenuItem(
                "Clear Chat",
                "/icons/input/eraser.svg",
                null,
                this::requestClearChat
        );

        JPopupMenu popup = PopupMenuSupport.configureNativeSafePopup(new JPopupMenu());
        popup.add(copyItem);
        popup.addSeparator();
        popup.add(selectMessageItem);
        popup.add(selectConversationItem);
        if (bubble.getRole() == Role.ASSISTANT) {
            popup.addSeparator();
            popup.add(readAloudItem);
        }
        popup.addSeparator();
        popup.add(regenerateItem);
        popup.add(clearChatItem);

        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                copyItem.setEnabled(bubble.hasContentSelection());
                selectMessageItem.setEnabled(!bubble.getFullText().isEmpty());
                selectConversationItem.setEnabled(hasAnyConversationText());
                readAloudItem.setVisible(bubble.getRole() == Role.ASSISTANT);
                updateReadAloudMenuItem(readAloudItem, bubble);
                regenerateItem.setEnabled(canRegenerateFrom(bubble));
                clearChatItem.setVisible(canClearChat());
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

    private void updateReadAloudMenuItem(JMenuItem item, ChatMessageView bubble) {
        boolean active = textToSpeechService.isReadAloudActive(swingReadAloudKey(bubble));
        item.setText(active ? "Stop" : "Read aloud");
        item.setIcon(chatMenuIcon(active ? "/icons/chat/player-stop.svg" : "/icons/chat/volume-2.svg"));
        item.setEnabled(active || canReadAloud(bubble, bubble.getRole()));
    }

    private int menuShortcutKeyMask() {
        return GraphicsEnvironment.isHeadless()
                ? InputEvent.CTRL_DOWN_MASK
                : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    private JMenuItem buildChatMenuItem(String label, String iconPath, KeyStroke accelerator, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        Fonts.apply(item, Font.PLAIN, Fonts.SIZE_BODY);
        item.setIcon(chatMenuIcon(iconPath));
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private Icon chatMenuIcon(String path) {
        return CHAT_MENU_ICON_CACHE.computeIfAbsent(path, key -> {
            URL iconUrl = ChatPanel.class.getResource(path);
            if (iconUrl == null) {
                return null;
            }
            return new ThemeAwareSvgIcon(iconUrl, CHAT_MENU_ICON_SIZE);
        });
    }

    private List<ChatMessageView> collectBubbles() {
        List<ChatMessageView> bubbles = new ArrayList<>();
        collectBubbles(messagesPanel, bubbles);
        return bubbles;
    }

    private void collectBubbles(Container container, List<ChatMessageView> collected) {
        for (Component child : container.getComponents()) {
            if (child instanceof ActivityBubble) {
                continue;
            }
            ChatMessageView bubble = child instanceof JComponent component ? chatMessageView(component) : null;
            if (bubble != null) {
                if (!collected.contains(bubble)) {
                    collected.add(bubble);
                }
            } else if (child instanceof Container nested) {
                collectBubbles(nested, collected);
            }
        }
    }

    private ChatMessageView chatMessageView(JComponent component) {
        if (component instanceof ChatMessageView view) {
            return view;
        }

        Object value = component.getClientProperty(MESSAGE_VIEW_PROPERTY);
        return value instanceof ChatMessageView view ? view : null;
    }

    private boolean hasAnyConversationText() {
        return collectBubbles().stream().anyMatch(bubble -> !bubble.getFullText().isEmpty());
    }

    private void selectAndCopyConversation() {
        List<ChatMessageView> bubbles = collectBubbles();
        StringBuilder joined = new StringBuilder();
        for (ChatMessageView bubble : bubbles) {
            bubble.selectAllContent();
            String text = bubble.getFullText();
            if (text.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append("\n\n");
            }
            String prefix = bubble.getRole() == Role.USER ? "User: " : "Assistant: ";
            joined.append(prefix).append(text);
        }

        if (joined.length() == 0) {
            return;
        }

        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(joined.toString()), null);
    }

    private void handleWebTranscriptAction(String action, int messageIndex, String text) {
        SwingUtilities.invokeLater(() -> {
            if (Strings.CS.equalsAny(action, "copy-selected", "copy-text")) {
                copyTextToClipboard(text);
                return;
            }
            if (speechToTextService.active()) {
                if (Strings.CS.equalsAny(action, "read-aloud", "regenerate", "open-attachment", "open-diagram-html")) {
                    inputBar.showValidationMessage("Finish or cancel transcription before using this action.");
                    return;
                }
            }
            if (Strings.CS.equals(action, "open-attachment")) {
                openConversationAttachment(text);
                return;
            }
            if (Strings.CS.equals(action, "open-diagram-html")) {
                openDiagramHtml(text);
                return;
            }

            List<ChatMessageView> bubbles = collectBubbles();
            if (messageIndex < 0) {
                if (Strings.CS.equals(action, "read-aloud") && StringUtils.isNotBlank(text)) {
                    readWebTranscriptAloud(messageIndex, text);
                }
                return;
            }

            ChatMessageView bubble = webTranscriptBubble(messageIndex, bubbles);
            if (Strings.CS.equals(action, "read-aloud")) {
                ChatMessageView readAloudBubble = bubble == null ? webTranscriptBubbleByText(text, bubbles) : bubble;
                readWebTranscriptAloud(
                        resolvedWebReadAloudMessageIndex(messageIndex, readAloudBubble),
                        webTranscriptReadAloudText(messageIndex, text, readAloudBubble)
                );
                return;
            }
            if (bubble == null) {
                return;
            }
            if (Strings.CS.equals(action, "copy")) {
                copyBubbleTextToClipboard(bubble);
                return;
            }
            if (Strings.CS.equals(action, "regenerate")) {
                regenerateFromBubble(bubble);
            }
        });
    }

    private ChatMessageView webTranscriptBubble(int messageIndex, List<ChatMessageView> bubbles) {
        ChatMessageView indexedBubble = bubbles.stream()
                .filter(bubble -> messageIndex(bubble) == messageIndex)
                .findFirst()
                .orElse(null);
        if (indexedBubble != null) {
            return indexedBubble;
        }
        if (messageIndex >= 0 && messageIndex < history.size()) {
            Message message = history.get(messageIndex);
            ChatMessageView matchingBubble = bubbles.stream()
                    .filter(bubble -> bubble.getRole() == message.role())
                    .filter(bubble -> Strings.CS.equals(speakableText(bubble), message.content()))
                    .findFirst()
                    .orElse(null);
            if (matchingBubble != null) {
                return matchingBubble;
            }
        }
        return messageIndex >= 0 && messageIndex < bubbles.size() ? bubbles.get(messageIndex) : null;
    }

    private int resolvedWebReadAloudMessageIndex(int messageIndex, ChatMessageView bubble) {
        int bubbleMessageIndex = bubble == null ? -1 : messageIndex(bubble);
        return bubbleMessageIndex >= 0 && bubble.getRole() == Role.ASSISTANT
                ? bubbleMessageIndex
                : messageIndex;
    }

    private ChatMessageView webTranscriptBubbleByText(String text, List<ChatMessageView> bubbles) {
        String normalizedText = StringUtils.normalizeSpace(text);
        if (StringUtils.isBlank(normalizedText)) {
            return null;
        }
        return bubbles.stream()
                .filter(bubble -> bubble.getRole() == Role.ASSISTANT)
                .filter(bubble -> Strings.CS.equals(StringUtils.normalizeSpace(speakableText(bubble)), normalizedText))
                .findFirst()
                .orElse(null);
    }

    private String webTranscriptReadAloudText(int messageIndex, String text, ChatMessageView bubble) {
        String storedText = StringUtils.defaultIfBlank(
                storedAssistantMessageText(messageIndex),
                storedAssistantMessageText(messageIndex(bubble))
        );
        String bubbleText = bubble != null && bubble.getRole() == Role.ASSISTANT ? speakableText(bubble) : "";
        return StringUtils.defaultIfBlank(storedText, StringUtils.defaultIfBlank(text, bubbleText));
    }

    private String storedAssistantMessageText(int messageIndex) {
        return messageIndex >= 0 && messageIndex < history.size() && history.get(messageIndex).role() == Role.ASSISTANT
                ? history.get(messageIndex).content()
                : "";
    }

    private void readWebTranscriptAloud(int messageIndex, String text) {
        if (speechToTextService.active()) {
            showReadAloudStatus("Finish or cancel transcription before using read aloud.");
            return;
        }
        textToSpeechService.readAloud(
                webReadAloudKey(messageIndex),
                text,
                this::showReadAloudError,
                this::showReadAloudStatus,
                this::refreshReadAloudControls
        );
    }

    private String webReadAloudKey(int messageIndex) {
        return "web:%d".formatted(messageIndex);
    }

    private void refreshReadAloudControls() {
        SwingUtilities.invokeLater(() -> {
            refreshBubbleActionBars();
            refreshWebTranscript(false);
        });
    }

    private void openDiagramHtml(String payload) {
        try {
            Path path = DiagramHtmlExporter.exportMermaidHtml(payload);
            openHtmlFile(path);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Open Diagram", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void openHtmlFile(Path path) throws IOException {
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Opening diagrams is not supported on this system.");
        }

        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.OPEN)) {
            desktop.open(path.toFile());
            return;
        }
        if (desktop.isSupported(Desktop.Action.BROWSE)) {
            desktop.browse(path.toUri());
            return;
        }
        throw new IOException("Opening diagrams is not supported on this system.");
    }

    private void openConversationAttachment(String storagePath) {
        if (StringUtils.isBlank(storagePath) || !isKnownConversationAttachment(storagePath)) {
            showOpenAttachmentError("Attachment file is not available on disk.");
            return;
        }

        Path path;
        try {
            path = Path.of(storagePath);
        } catch (Exception e) {
            showOpenAttachmentError("Attachment file is not available on disk.");
            return;
        }

        if (!Files.exists(path)) {
            showOpenAttachmentError("Attachment file is not available on disk.");
            return;
        }

        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            showOpenAttachmentError("Opening attachments is not supported on this system.");
            return;
        }

        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException e) {
            showOpenAttachmentError("Unable to open attachment: %s".formatted(path.getFileName()));
        }
    }

    private boolean isKnownConversationAttachment(String storagePath) {
        return history.stream()
                .flatMap(message -> message.parts().stream())
                .map(this::attachmentRef)
                .filter(Objects::nonNull)
                .map(AttachmentRef::storagePath)
                .anyMatch(path -> Strings.CS.equals(path, storagePath));
    }

    private void showOpenAttachmentError(String message) {
        JOptionPane.showMessageDialog(this, message, "Open Attachment", JOptionPane.WARNING_MESSAGE);
    }

    private void scrollToBottom() {
        if (!autoScrollEnabled) {
            return;
        }
        scheduleAutoScroll();
    }

    private void forceScrollToBottom() {
        runOnEdt(() -> {
            scrollToBottomNow();
            SwingUtilities.invokeLater(this::scrollToBottomNow);
        });
    }

    private void onJumpToLatestRequested() {
        forceScrollToBottom();
    }

    private void scheduleAutoScroll() {
        runOnEdt(() -> {
            if (!autoScrollEnabled || autoScrollQueued) {
                return;
            }

            autoScrollQueued = true;
            SwingUtilities.invokeLater(() -> {
                autoScrollQueued = false;
                if (!autoScrollEnabled) {
                    return;
                }
                scrollToBottomNow();
            });
        });
    }

    private void scrollToBottomNow() {
        if (isSystemWebViewEnabled()) {
            systemWebView.scrollToBottom();
            return;
        }
        if (isJcefBrowserViewEnabled()) {
            jcefBrowserView.scrollToBottom();
            return;
        }

        JScrollBar vertical = scrollPane.getVerticalScrollBar();
        int target = Math.max(0, vertical.getMaximum() - vertical.getVisibleAmount());
        if (vertical.getValue() != target) {
            vertical.setValue(target);
        }
    }

    private void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    public void clearChat() {
        clearChat(true);
    }

    public void clearChatView() {
        clearChat(false);
    }

    public void discardConversations(Collection<UUID> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> discardConversations(conversationIds));
            return;
        }

        Set<UUID> ids = conversationIds.stream()
                .filter(Objects::nonNull)
                .collect(toCollection(HashSet::new));
        if (ids.isEmpty()) {
            return;
        }

        pendingCompletedAssistantRecoveries.keySet().removeAll(ids);
        activeSendJobs.values().stream()
                .filter(sendJob -> ids.contains(sendJob.conversationId))
                .toList()
                .forEach(this::discardSendJob);
        activeSessions.values().stream()
                .filter(session -> ids.contains(session.conversationId))
                .toList()
                .forEach(this::discardStreamingSession);

        if (ids.contains(activeConversationId)) {
            clearChat(false);
            setActiveConversationId(null);
        }
        updateGenerationIndicator();
    }

    private void discardSendJob(SendJob sendJob) {
        if (sendJob == null) {
            return;
        }
        sendJob.cancelled.set(true);
        sendJob.finished = true;
        activeSendJobs.remove(sendJob.jobId);
        Thread worker = sendJob.worker;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void discardStreamingSession(StreamingSession session) {
        if (session == null) {
            return;
        }
        session.cancelled.set(true);
        session.finished = true;
        activeSessions.remove(session.sessionId);
        cancelSessionActiveRequest(session, false);
        Thread worker = session.worker;
        if (worker != null) {
            worker.interrupt();
        }
        if (!hasLiveStreamingSession(session.conversationId)) {
            notifyConversationStreamingChanged(session.conversationId, false);
        }
    }

    private void clearChat(boolean cancelActiveStream) {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before clearing the chat.");
            return;
        }
        if (cancelActiveStream) {
            clearPendingAssistantRecovery(activeConversationId);
            cancelStreaming();
        }

        stopReadAloudPlayback();
        disposeMessageViews();
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        currentAssistantBubble = null;
        history.clear();
        assistantBubbles.clear();
        thinkingBubbles.clear();
        messagesPanel.removeAll();
        messageRow = 0;
        messagesPanel.revalidate();
        messagesPanel.repaint();
        refreshWebTranscript(false, true);
        messagesCardLayout.show(messagesContainer, CARD_EMPTY);
        updateClearChatButtonVisibility();
    }

    private void clearChatForHistoryLoad() {
        stopReadAloudPlayback();
        disposeMessageViews();
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        currentAssistantBubble = null;
        history.clear();
        assistantBubbles.clear();
        thinkingBubbles.clear();
        messagesPanel.removeAll();
        messageRow = 0;
    }

    private void finishHistoryLoadRefresh() {
        addBottomFiller();
        refreshMessageColumnInsets();
        messagesPanel.revalidate();
        messagesPanel.repaint();
        messagesCardLayout.show(messagesContainer, history.isEmpty() ? CARD_EMPTY : CARD_CHAT);
        updateClearChatButtonVisibility();
        SwingUtilities.invokeLater(() -> {
            updateAtBottom();
            refreshJumpOverlay();
        });
        if (history.isEmpty()) {
            refreshWebTranscript(false);
            return;
        }
        SwingUtilities.invokeLater(() -> refreshWebTranscript(false, true));
    }

    private void disposeMessageViews() {
        thinkingBubbles.forEach(ActivityBubble::dispose);
        collectBubbles().stream()
                .filter(bubble -> !bubble.isDisposed())
                .forEach(ChatMessageView::dispose);
    }

    public List<Message> getHistory() {
        return new ArrayList<>(history);
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void loadConversationHistory(UUID conversationId, List<Message> messages) {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before switching conversations.");
            return;
        }
        setActiveConversationId(conversationId);
        loadHistory(messages);
    }

    public void loadHistory(List<Message> messages) {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before loading history.");
            return;
        }
        clearChatForHistoryLoad();

        batchMessageRefresh = true;
        try {
            List<Message> normalizedMessages = new ArrayList<>(normalizeLoadedHistory(messages));
            recoverPendingCompletedAssistantMessage(activeConversationId, normalizedMessages);
            for (Message msg : normalizedMessages) {
                history.add(msg);
                int messageIndex = history.size() - 1;
                if (msg.role() == Role.USER) {
                    addUserBubble(msg, messageIndex);
                    continue;
                }

                if (msg.role() == Role.ASSISTANT) {
                    String assistantThinking = normalizeThinkingText(msg.meta() == null
                            ? ""
                            : StringUtils.defaultString(msg.meta().assistantThinking()));
                    if (hasVisibleThinkingContent(assistantThinking)) {
                        addActivityBubble(new ActivityBubble(THINKING_COLLAPSED_BY_DEFAULT_WHEN_LOADING_HISTORY), assistantThinking);
                    }

                    String assistantWebSearch = normalizeWebSearchActivity(msg.meta() == null
                            ? ""
                            : StringUtils.defaultString(msg.meta().assistantWebSearch()));
                    if (StringUtils.isNotBlank(assistantWebSearch)) {
                        addActivityBubble(new ActivityBubble("Web Search", WEB_SEARCH_COLLAPSED_BY_DEFAULT), assistantWebSearch);
                    }

                    List<AgentToolActivityMeta> agentToolActivities = msg.meta() == null
                            ? emptyList()
                            : msg.meta().agentToolActivities();
                    agentToolActivities.stream()
                            .map(this::toAgentToolActivity)
                            .forEach(this::addPersistedAgentToolBubble);

                    if (StringUtils.isBlank(msg.content()) && msg.parts().stream().noneMatch(GeneratedImagePart.class::isInstance)) {
                        continue;
                    }
                }

                addBubble(createMessageView(msg.role()), msg, msg.role(), messageIndex);
            }
        } finally {
            batchMessageRefresh = false;
        }

        attachVisibleStreamingSession(visibleStreamingSession());
        finishHistoryLoadRefresh();
    }

    public String getSelectedModel() {
        if (selectedProviderName == null || selectedModelId == null) {
            return null;
        }

        return ModelSelectionCodec.format(selectedProviderName, selectedModelId);
    }

    public void setSelectedModel(String modelKey) {
        ModelSelectionCodec.parse(modelKey).ifPresent(selection -> {
            String safeModelId = safeModelId(selection.provider(), selection.model());
            if (StringUtils.isBlank(safeModelId)) {
                clearSelectedModel();
                return;
            }
            selectModel(selection.provider(), safeModelId);
        });
    }

    private String safeModelId(String providerName, String modelId) {
        ProviderRegistry.ProviderDef providerDef = providerMap.get(providerName);
        if (providerDef == null) {
            boolean knownProvider = ProviderRegistry.allProviders().stream()
                    .map(ProviderRegistry.ProviderDef::name)
                    .anyMatch(providerName::equals);
            return knownProvider ? modelId : null;
        }
        if (!modelCacheService.isInvalidated(providerName)) {
            return modelId;
        }
        List<String> seedModels = sanitizeModelIds(providerName, providerDef.seedModels());
        if (seedModels.contains(modelId)) {
            return modelId;
        }
        return seedModels.isEmpty() ? null : seedModels.getFirst();
    }

    public InputBar getInputBar() {
        return inputBar;
    }

    public ModelSelectorButton getModelSelectorButton() {
        return modelSelectorBtn;
    }

    public JComponent getRenderTogglePanel() {
        return renderTogglePanel;
    }

    public void hideModelPopup() {
        if (modelPopup != null) {
            modelPopup.hidePopup();
        }
    }

    public void showModelPopupCentered() {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before changing models.");
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        if (owner == null) {
            return;
        }

        ModelSelectorPopup popup = ensureModelPopup(owner);
        if (popup.isVisible()) {
            popup.hidePopup();
            return;
        }

        popup.showCentered(selectedProviderName, selectedModelId);
    }

    public RenderMode getRenderMode() {
        return renderMode;
    }

    public void setRenderMode(RenderMode mode, boolean rerenderMessages) {
        if (mode == null || renderMode == mode) {
            updateRenderModeToggleSelection();
            return;
        }

        renderMode = mode;
        updateRenderModeToggleSelection();

        if (rerenderMessages) {
            collectBubbles().forEach(bubble -> bubble.setRenderMode(mode));
            thinkingBubbles.forEach(bubble -> bubble.setRenderMode(mode));
            messagesPanel.revalidate();
            messagesPanel.repaint();
            refreshWebTranscript(false);
        }

        if (renderModeChangedListener != null) {
            renderModeChangedListener.accept(mode);
        }
    }

    public void setOnRenderModeChanged(Consumer<RenderMode> listener) {
        this.renderModeChangedListener = listener;
    }

    private void updateRenderModeToggleSelection() {
        if (renderMode == RenderMode.MARKDOWN) {
            markdownToggle.setSelected(true);
        } else {
            previewToggle.setSelected(true);
        }
    }

    public void setOnSelectedModelChanged(Runnable listener) {
        this.selectedModelChangedListener = listener;
    }

    public void setOnModelFavoritesChanged(Runnable listener) {
        this.modelFavoritesChangedListener = listener;
    }

    public void setOnModelCatalogChanged(Runnable listener) {
        this.modelCatalogChangedListener = listener;
    }

    public void setOnMessageSubmitted(Runnable listener) {
        this.messageSubmittedListener = listener;
    }

    public void setOnClearChatRequested(Runnable listener) {
        this.clearChatRequestedListener = listener;
    }


    public void setOnUserMessageSubmitted(UserMessagePersistenceListener listener) {
        this.userMessageSubmittedListener = listener;
    }

    public void setOnAssistantMessageCompleted(AssistantMessagePersistenceListener listener) {
        this.assistantMessageCompletedListener = listener;
    }

    public void setOnHistoryTruncated(Consumer<HistoryTruncatedEvent> listener) {
        this.historyTruncatedListener = listener;
    }

    public void setOnConversationStreamingChanged(Consumer<ConversationStreamingEvent> listener) {
        this.conversationStreamingListener = listener;
    }

    public void setOnVisibleStreamingChanged(Consumer<Boolean> listener) {
        this.visibleStreamingChangedListener = listener;
    }

    public void setConversationIdSupplier(Supplier<UUID> supplier) {
        this.conversationIdSupplier = supplier;
    }

    void setSendPreparerForTests(SendPreparer sendPreparer) {
        this.sendPreparer = sendPreparer == null ? this::prepareUserMessage : sendPreparer;
    }

    void setAgentOrchestratorForTests(AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator == null ? AgentOrchestrator.createDefault() : agentOrchestrator;
    }

    public void setAgentSystemPromptAppend(String agentSystemPromptAppend) {
        this.agentSystemPromptAppend = StringUtils.defaultString(agentSystemPromptAppend);
    }

    public void setActiveConversationId(UUID conversationId) {
        boolean conversationChanged = !Objects.equals(this.activeConversationId, conversationId);
        this.activeConversationId = conversationId;
        if (conversationChanged) {
            clearVisibleStreamReferences();
        }

        applyVisibleConversationInputState();
    }

    public void setConversationLoading(boolean conversationLoading) {
        this.conversationLoading = conversationLoading;
        applyVisibleConversationInputState();
        updateClearChatButtonVisibility();
    }

    private void applyVisibleConversationInputState() {
        StreamingSession visibleSession = visibleStreamingSession();
        SendJob visiblePreparingJob = visiblePreparingJob();

        if (visibleSession != null) {
            activeStreamSessionId = visibleSession.sessionId;
            setVisibleStreaming(true);
            if (!speechToTextService.active()) {
                inputBar.setEnabled(false);
            }
        } else if (visiblePreparingJob != null) {
            activeStreamSessionId = -1L;
            setVisibleStreaming(true);
            if (!speechToTextService.active()) {
                inputBar.setEnabled(false);
            }
        } else {
            activeStreamSessionId = -1L;
            setVisibleStreaming(false);
            if (!speechToTextService.active()) {
                inputBar.setEnabled(!conversationLoading);
            }
        }
        refreshComposerAvailability();
        updateGenerationIndicator();
    }

    private void clearVisibleStreamReferences() {
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        currentAssistantBubble = null;
    }

    private boolean isVisibleSession(StreamingSession session) {
        return session != null
                && isVisibleConversation(session.conversationId)
                && activeStreamSessionId == session.sessionId;
    }

    private void attachVisibleStreamingSession(StreamingSession session) {
        if (session == null || !session.isLive() || !isVisibleSession(session)) {
            return;
        }

        boolean attachedContent = false;
        String assistantThinking;
        synchronized (session.thinking) {
            assistantThinking = normalizeThinkingText(session.thinking.toString());
        }
        if (hasVisibleThinkingContent(assistantThinking)) {
            if (currentAssistantActivityBubble == null) {
                currentAssistantActivityBubble = new ActivityBubble(THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING);
                currentAssistantActivityBubble.setStreaming(true);
                addActivityBubble(currentAssistantActivityBubble, null);
            }
            currentAssistantActivityBubble.setVisible(true);
            currentAssistantActivityBubble.setText(assistantThinking);
            attachedContent = true;
        }

        String assistantWebSearch;
        synchronized (session.webSearchActivity) {
            assistantWebSearch = normalizeWebSearchActivity(session.webSearchActivity.toString());
        }
        if (StringUtils.isNotBlank(assistantWebSearch)) {
            showWebSearchActivity(session, assistantWebSearch);
            attachedContent = true;
        }

        synchronized (session.agentToolActivities) {
            for (AgentToolActivity activity : session.agentToolActivities) {
                String formattedActivity = formatAgentToolActivity(activity);
                if (StringUtils.isBlank(formattedActivity)) {
                    continue;
                }
                ActivityBubble toolBubble = currentAssistantAgentToolBubbles.computeIfAbsent(
                        agentToolBubbleKey(activity),
                        ignored -> createAgentToolBubble(activity)
                );
                toolBubble.setVisible(true);
                toolBubble.setTitle(formattedActivity);
                attachedContent = true;
            }
        }

        String assistantText;
        synchronized (session.response) {
            assistantText = session.response.toString();
        }
        List<ContentPart> assistantParts = assistantResponseParts(session, assistantText);
        if (StringUtils.isNotBlank(assistantText) || assistantParts.stream().anyMatch(part -> !(part instanceof TextPart))) {
            if (currentAssistantBubble == null) {
                currentAssistantBubble = createMessageView(Role.ASSISTANT);
                addBubble(currentAssistantBubble, new Message(Role.ASSISTANT, assistantParts, Instant.now()), Role.ASSISTANT, history.size());
            } else {
                currentAssistantBubble.setContentParts(assistantParts);
            }
            attachedContent = true;
        }

        if (attachedContent) {
            refreshWebTranscript(false);
        }
    }

    private void recoverPendingCompletedAssistantMessage(UUID conversationId, List<Message> messages) {
        if (conversationId == null) {
            return;
        }
        Message completedAssistantMessage = pendingCompletedAssistantRecoveries.remove(conversationId);
        if (completedAssistantMessage == null || completedAssistantMessage.role() != Role.ASSISTANT) {
            return;
        }
        if (messages.stream().anyMatch(message -> isSameAssistantMessage(message, completedAssistantMessage))) {
            return;
        }
        messages.add(completedAssistantMessage);
    }

    private void clearPendingAssistantRecovery(UUID conversationId) {
        if (conversationId != null) {
            pendingCompletedAssistantRecoveries.remove(conversationId);
        }
    }

    private boolean isSameAssistantMessage(Message candidate, Message expected) {
        if (candidate == null || expected == null || candidate.role() != Role.ASSISTANT) {
            return false;
        }
        return Strings.CS.equals(candidate.content(), expected.content())
                && Objects.equals(candidate.parts(), expected.parts())
                && Objects.equals(candidate.meta(), expected.meta());
    }

    private void notifyModelFavoritesChanged() {
        if (modelFavoritesChangedListener != null) {
            modelFavoritesChangedListener.run();
        }
    }

    private void notifyModelCatalogChanged() {
        if (modelCatalogChangedListener != null) {
            modelCatalogChangedListener.run();
        }
    }

    private void notifyMessageSubmitted() {
        if (messageSubmittedListener != null) {
            messageSubmittedListener.run();
        }
    }

    public void requestClearChat() {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before clearing the chat.");
            return;
        }
        if (!canClearChat()) {
            return;
        }
        if (clearChatRequestedListener != null) {
            clearChatRequestedListener.run();
        }
    }

    public boolean canClearChat() {
        return !history.isEmpty() && !isVisibleConversationBusy() && inputBar.isEnabled() && !speechToTextService.active();
    }

    private void updateClearChatButtonVisibility() {
        inputBar.setClearChatVisible(canClearChat());
    }

    private void notifyConversationStreamingChanged(UUID conversationId, boolean streaming) {
        if (conversationStreamingListener != null && conversationId != null) {
            conversationStreamingListener.accept(new ConversationStreamingEvent(conversationId, streaming));
        }
    }

    private void setVisibleStreaming(boolean streaming) {
        if (this.streaming == streaming) {
            return;
        }
        this.streaming = streaming;
        if (visibleStreamingChangedListener != null) {
            visibleStreamingChangedListener.accept(streaming);
        }
    }

    private UUID resolveConversationId() {
        if (conversationIdSupplier != null) {
            UUID suppliedConversationId = conversationIdSupplier.get();
            if (suppliedConversationId != null) {
                return suppliedConversationId;
            }
        }
        return activeConversationId;
    }

    private boolean isVisibleConversation(UUID conversationId) {
        return Objects.equals(activeConversationId, conversationId);
    }

    private void handleAssistantToken(StreamingSession session, SendJob sendJob, String token) {
        if (!session.isLive()) {
            return;
        }

        ThinkTagSplit split = session.thinkTagParser.accept(token);
        appendAssistantVisibleToken(session, split.visibleText());
        handleAssistantThinkingToken(session, sendJob, split.thinkingText(), true);
    }

    private void flushThinkTagParser(StreamingSession session, SendJob sendJob) {
        if (!session.isLive()) {
            return;
        }

        ThinkTagSplit split = session.thinkTagParser.flush();
        appendAssistantVisibleToken(session, split.visibleText());
        handleAssistantThinkingToken(session, sendJob, split.thinkingText(), true);
    }

    private void appendAssistantVisibleToken(StreamingSession session, String token) {
        if (StringUtils.isEmpty(token)) {
            return;
        }

        appendAssistantResponse(session, token);
        SwingUtilities.invokeLater(() -> {
            if (!session.isLive()) {
                return;
            }
            if (!isVisibleSession(session)) {
                return;
            }
            if (currentAssistantBubble == null) {
                currentAssistantBubble = createMessageView(Role.ASSISTANT);
                addAssistantBubble(currentAssistantBubble, null);
            }
            boolean wasBlank = StringUtils.isBlank(speakableText(currentAssistantBubble));
            currentAssistantBubble.appendText(token);
            if (wasBlank) {
                refreshBubbleActionBars();
            }
            refreshWebTranscript(true);
            scrollToBottom();
        });
    }

    private void handleAssistantPart(StreamingSession session, ContentPart part) {
        if (!session.isLive() || part == null || part instanceof TextPart) {
            return;
        }

        session.responseParts.add(part);
        SwingUtilities.invokeLater(() -> {
            if (!session.isLive() || !isVisibleSession(session)) {
                return;
            }
            if (currentAssistantBubble == null) {
                currentAssistantBubble = createMessageView(Role.ASSISTANT);
                addAssistantBubble(currentAssistantBubble, null);
            }
            boolean wasBlank = StringUtils.isBlank(speakableText(currentAssistantBubble));
            currentAssistantBubble.appendPart(part);
            if (wasBlank) {
                refreshBubbleActionBars();
            }
            refreshWebTranscript(true);
            scrollToBottom();
        });
    }

    private void handleAssistantCitation(StreamingSession session, CitationRef citation) {
        if (!session.isLive() || citation == null) {
            return;
        }
        synchronized (session.responseCitations) {
            if (session.responseCitations.stream().anyMatch(existing -> existing.number() == citation.number())) {
                return;
            }
            session.responseCitations.add(citation);
        }
        List<CitationRef> citations = snapshotCitations(session);
        SwingUtilities.invokeLater(() -> {
            if (!session.isLive() || !isVisibleSession(session) || currentAssistantBubble == null) {
                return;
            }
            currentAssistantBubble.component().putClientProperty(
                    MESSAGE_META_PROPERTY,
                    new MessageMeta(emptyList(), emptyList(), false, "", "", "", emptyList(), citations)
            );
            refreshWebTranscript(true);
        });
    }

    private void handleAssistantThinkingToken(StreamingSession session, SendJob sendJob, String thinkingToken) {
        handleAssistantThinkingToken(session, sendJob, thinkingToken, false);
    }

    private void handleAssistantThinkingToken(
            StreamingSession session,
            SendJob sendJob,
            String thinkingToken,
            boolean forceRender
    ) {
        if (!session.isLive() || (!forceRender && !sendJob.reasoningLevel.enabled())) {
            return;
        }

        String normalizedThinkingToken = normalizeThinkingText(thinkingToken);
        if (normalizedThinkingToken.isEmpty()) {
            return;
        }

        appendAssistantThinking(session, normalizedThinkingToken);
        SwingUtilities.invokeLater(() -> {
            if (!session.isLive() || !isVisibleSession(session)) {
                return;
            }

            if (currentAssistantActivityBubble == null) {
                currentAssistantActivityBubble = new ActivityBubble(THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING);
                currentAssistantActivityBubble.setStreaming(true);
                addActivityBubble(currentAssistantActivityBubble, null);
            }

            currentAssistantActivityBubble.setVisible(true);
            currentAssistantActivityBubble.appendText(normalizedThinkingToken);
            refreshWebTranscript(true);
            scrollToBottom();
        });
    }

    private void appendAssistantResponse(StreamingSession session, String text) {
        if (session == null || StringUtils.isEmpty(text)) {
            return;
        }

        synchronized (session.response) {
            session.response.append(text);
        }
        synchronized (session.responseParts) {
            if (!session.responseParts.isEmpty() && session.responseParts.getLast() instanceof TextPart textPart) {
                session.responseParts.set(session.responseParts.size() - 1, new TextPart("%s%s".formatted(textPart.text(), text)));
                return;
            }
            session.responseParts.add(new TextPart(text));
        }
    }

    private void appendAssistantThinking(StreamingSession session, String text) {
        if (session == null || !session.isLive()) {
            return;
        }

        String normalized = normalizeThinkingText(text);
        if (normalized.isEmpty()) {
            return;
        }

        synchronized (session.thinking) {
            session.thinking.append(normalized);
        }
    }

    private List<ContentPart> assistantResponseParts(StreamingSession session, String assistantText) {
        List<ContentPart> parts = new ArrayList<>();
        synchronized (session.responseParts) {
            parts.addAll(session.responseParts);
        }
        if (parts.isEmpty() && StringUtils.isNotEmpty(assistantText)) {
            parts.add(new TextPart(assistantText));
        }
        if (!parts.isEmpty() && !Strings.CS.equals(textProjection(parts), assistantText)) {
            parts = replaceTextProjection(parts, assistantText);
        }
        if (parts.isEmpty()) {
            parts.add(new TextPart(""));
        }
        return List.copyOf(parts);
    }

    private String textProjection(List<ContentPart> parts) {
        return parts.stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .collect(joining());
    }

    private List<ContentPart> replaceTextProjection(List<ContentPart> parts, String assistantText) {
        List<ContentPart> replaced = new ArrayList<>();
        boolean textInserted = false;
        for (ContentPart part : parts) {
            if (part instanceof TextPart) {
                if (!textInserted) {
                    replaced.add(new TextPart(StringUtils.defaultString(assistantText)));
                    textInserted = true;
                }
                continue;
            }
            replaced.add(part);
        }
        if (!textInserted) {
            replaced.addFirst(new TextPart(StringUtils.defaultString(assistantText)));
        }
        return replaced;
    }

    private void persistAssistantResponse(StreamingSession session, SendJob sendJob, boolean allowBlankContent) {
        String assistantText;
        synchronized (session.response) {
            assistantText = session.response.toString();
        }

        String assistantThinking;
        synchronized (session.thinking) {
            assistantThinking = normalizeThinkingText(session.thinking.toString());
        }

        String assistantWebSearch;
        synchronized (session.webSearchActivity) {
            assistantWebSearch = session.webSearchActivity.toString();
        }
        List<AgentToolActivityMeta> agentToolActivities = snapshotAgentToolActivities(session);
        List<CitationRef> citations = snapshotCitations(session);
        assistantText = appendCitationSourcesIfNeeded(assistantText, citations);
        assistantWebSearch = normalizeWebSearchActivity(mergeAssistantWebSearchWithAnswerSources(sendJob, assistantText, assistantWebSearch, citations));
        if (isVisibleConversation(session.conversationId) && StringUtils.isNotBlank(assistantWebSearch)) {
            showWebSearchActivity(session, assistantWebSearch);
        }

        List<ContentPart> assistantParts = assistantResponseParts(session, assistantText);
        boolean hasContent = StringUtils.isNotBlank(assistantText)
                || assistantParts.stream().anyMatch(part -> !(part instanceof TextPart))
                || hasVisibleThinkingContent(assistantThinking)
                || StringUtils.isNotBlank(assistantWebSearch)
                || hasVisibleAgentToolActivity(session);
        if (!allowBlankContent && !hasContent) {
            return;
        }

        if (!session.persisted.compareAndSet(false, true)) {
            return;
        }

        Message assistantMessage = new Message(
                Role.ASSISTANT,
                assistantParts,
                Instant.now(),
                new MessageMeta(
                        emptyList(),
                        emptyList(),
                        false,
                        "",
                        assistantThinking,
                        assistantWebSearch,
                        agentToolActivities,
                        citations
                )
        );
        UUID conversationId = session.conversationId;
        if (isVisibleConversation(conversationId)) {
            history.add(assistantMessage);
            int assistantMessageIndex = history.size() - 1;
            if (currentAssistantBubble == null) {
                addBubble(createMessageView(Role.ASSISTANT), assistantMessage, Role.ASSISTANT, assistantMessageIndex);
            } else {
                currentAssistantBubble.setContentParts(assistantMessage.parts());
                currentAssistantBubble.component().putClientProperty(MESSAGE_META_PROPERTY, assistantMessage.meta());
                setMessageIndex(currentAssistantBubble, assistantMessageIndex);
                refreshWebTranscript(false);
            }
            updateClearChatButtonVisibility();
            refreshWebTranscript(false, true);
        }

        boolean persistedByListener = persistAssistantMessageEvent(conversationId, assistantMessage);
        if (conversationId != null && !isVisibleConversation(conversationId) && hasContent) {
            pendingCompletedAssistantRecoveries.put(conversationId, assistantMessage);
        }

        if (!persistedByListener && (isVisibleConversation(conversationId) || conversationId == null)) {
            notifyMessageSubmitted();
        }
    }

    private String mergeAssistantWebSearchWithAnswerSources(
            SendJob sendJob,
            String assistantText,
            String existingActivity,
            List<CitationRef> citations
    ) {
        if (sendJob == null || !sendJob.webSearchEnabled) {
            return existingActivity;
        }

        String sourceActivity = citationSourceLines(citations);
        if (StringUtils.isBlank(sourceActivity)) {
            sourceActivity = extractWebSearchSourcesFromAssistantText(assistantText);
        }
        if (StringUtils.isBlank(sourceActivity)) {
            return existingActivity;
        }

        return "%s\n\n**Sources**\n%s".formatted(
                StringUtils.defaultString(existingActivity).trim(),
                sourceActivity
        ).trim();
    }

    private String appendCitationSourcesIfNeeded(String assistantText, List<CitationRef> citations) {
        String text = StringUtils.defaultString(assistantText);
        if (citations == null || citations.isEmpty()
                || SOURCE_REFERENCE_LINE_PATTERN.matcher(text).find()
                || hasSourceSectionWithUrls(text)) {
            return text;
        }

        String sources = citationSourceLines(citations);
        if (StringUtils.isBlank(sources)) {
            return text;
        }

        String answer = text.stripTrailing();
        return StringUtils.isBlank(answer)
                ? "Sources:\n%s".formatted(sources)
                : "%s\n\nSources:\n%s".formatted(answer, sources);
    }

    private boolean hasSourceSectionWithUrls(String text) {
        boolean inSources = false;
        for (String line : text.split("\\R")) {
            String normalizedLine = normalizeHeadingLine(line);
            if (Strings.CI.equals(normalizedLine, "sources")) {
                inSources = true;
                continue;
            }
            if (inSources && isMarkdownHeadingLine(line)) {
                inSources = false;
            }
            if (inSources && SOURCE_URL_PATTERN.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isMarkdownHeadingLine(String line) {
        String trimmed = StringUtils.trimToEmpty(line);
        return trimmed.startsWith("#") || trimmed.matches("\\*\\*.+\\*\\*:?");
    }

    private String normalizeHeadingLine(String line) {
        String heading = StringUtils.trimToEmpty(line).replaceFirst("^#+\\s*", "");
        heading = Strings.CS.removeEnd(heading, ":").trim();
        heading = Strings.CS.removeEnd(Strings.CS.removeStart(heading, "**"), "**").trim();
        heading = Strings.CS.removeEnd(heading, ":").trim();
        return heading;
    }

    private String citationSourceLines(List<CitationRef> citations) {
        if (citations == null || citations.isEmpty()) {
            return "";
        }

        return citations.stream()
                .filter(citation -> citation != null && citation.number() > 0)
                .filter(citation -> citation.kind() == CitationKind.WEB)
                .filter(citation -> isHttpUrl(citation.url()))
                .collect(toMap(
                        CitationRef::number,
                        this::citationSourceLine,
                        (existing, replacement) -> existing,
                        TreeMap::new
                ))
                .values()
                .stream()
                .collect(joining("\n"));
    }

    private String citationSourceLine(CitationRef citation) {
        return "[%d] [%s](%s)".formatted(
                citation.number(),
                escapeMarkdownLinkLabel(citationSourceLabel(citation)),
                markdownLinkDestination(citation.url())
        );
    }

    private String markdownLinkDestination(String url) {
        return "<%s>".formatted(StringUtils.defaultString(url).replace(">", "%3E"));
    }

    private String citationSourceLabel(CitationRef citation) {
        String title = StringUtils.trimToEmpty(citation.displayTitle()).replaceAll("\\s+", " ");
        if (StringUtils.isNotBlank(title) && !Strings.CS.equals(title, citation.url())) {
            return title;
        }
        return sourceDomain(citation.url());
    }

    private String sourceDomain(String url) {
        try {
            String host = URI.create(url).getHost();
            return StringUtils.defaultIfBlank(Strings.CS.removeStart(host, "www."), url);
        } catch (Exception e) {
            return url;
        }
    }

    private boolean isHttpUrl(String url) {
        if (StringUtils.isBlank(url) || url.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return Strings.CI.equalsAny(uri.getScheme(), "http", "https") && StringUtils.isNotBlank(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private String normalizeWebSearchActivity(String activity) {
        return WebSearchActivityNormalizer.normalize(activity);
    }

    private String extractWebSearchSourcesFromAssistantText(String assistantText) {
        if (StringUtils.isBlank(assistantText)) {
            return "";
        }

        List<String> sourceItems = Arrays.stream(assistantText.split("\\R"))
                .map(SOURCE_URL_PATTERN::matcher)
                .filter(Matcher::find)
                .map(this::matchedSourceItem)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .limit(10)
                .toList();
        if (sourceItems.isEmpty()) {
            return "";
        }

        StringBuilder sources = new StringBuilder();
        sourceItems.forEach(item -> sources.append("- ").append(item).append("\n"));
        return sources.toString().trim();
    }

    private String matchedSourceItem(Matcher matcher) {
        String match = StringUtils.trimToEmpty(matcher.group());
        return Strings.CI.startsWith(match, "http://") || Strings.CI.startsWith(match, "https://")
                ? markdownLinkDestination(matchedSourceUrl(matcher))
                : match;
    }

    private String matchedSourceUrl(Matcher matcher) {
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String value = matcher.group(i);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private void removeCurrentWebSearchBubbleIfBlank() {
        if (currentAssistantWebSearchBubble == null || StringUtils.isNotBlank(currentAssistantWebSearchBubble.getFullText())) {
            return;
        }

        removeMessageComponentFromPanel(currentAssistantWebSearchBubble);
    }

    private void removeCurrentActivityBubbleIfBlank() {
        if (currentAssistantActivityBubble == null || hasVisibleThinkingContent(currentAssistantActivityBubble.getFullText())) {
            return;
        }

        removeMessageComponentFromPanel(currentAssistantActivityBubble);
    }

    private void removeCurrentAgentToolBubblesIfBlank() {
        // Compact tool bubbles render their state in the title, not in the expandable body.
    }

    private void clearCurrentAgentToolBubbleState() {
        currentAssistantAgentToolBubbles.clear();
    }

    private boolean hasVisibleAgentToolActivity(StreamingSession session) {
        if (session == null) {
            return false;
        }

        synchronized (session.agentToolActivities) {
            return !session.agentToolActivities.isEmpty();
        }
    }

    private List<CitationRef> snapshotCitations(StreamingSession session) {
        if (session == null) {
            return emptyList();
        }

        synchronized (session.responseCitations) {
            return session.responseCitations.isEmpty() ? emptyList() : List.copyOf(session.responseCitations);
        }
    }

    private List<AgentToolActivityMeta> snapshotAgentToolActivities(StreamingSession session) {
        if (session == null) {
            return emptyList();
        }

        List<AgentToolActivity> activities;
        synchronized (session.agentToolActivities) {
            activities = List.copyOf(session.agentToolActivities);
        }
        if (activities.isEmpty()) {
            return emptyList();
        }

        Map<String, AgentToolActivity> latestByInvocation = new LinkedHashMap<>();
        activities.forEach(activity -> latestByInvocation.put(agentToolBubbleKey(activity), activity));
        return latestByInvocation.values().stream()
                .map(this::toAgentToolActivityMeta)
                .toList();
    }

    private AgentToolActivityMeta toAgentToolActivityMeta(AgentToolActivity activity) {
        return new AgentToolActivityMeta(
                activity.invocationId(),
                activity.toolName(),
                activity.status().name(),
                activity.argumentsSummary(),
                activity.message()
        );
    }

    private AgentToolActivity toAgentToolActivity(AgentToolActivityMeta activity) {
        return new AgentToolActivity(
                activity.invocationId(),
                activity.toolName(),
                parseAgentToolActivityStatus(activity.status()),
                activity.argumentsSummary(),
                activity.message()
        );
    }

    private AgentToolActivity.Status parseAgentToolActivityStatus(String status) {
        try {
            return AgentToolActivity.Status.valueOf(StringUtils.upperCase(StringUtils.defaultIfBlank(status, "STARTED")));
        } catch (Exception e) {
            return AgentToolActivity.Status.STARTED;
        }
    }

    private boolean hasVisibleThinkingContent(String text) {
        return StringUtils.isNotBlank(normalizeThinkingText(text));
    }

    private List<Message> normalizeLoadedHistory(List<Message> messages) {
        if (ObjectUtils.isEmpty(messages)) {
            return emptyList();
        }

        List<Message> normalized = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            Message message = messages.get(index);
            if (message.role() != Role.ASSISTANT) {
                normalized.add(message);
                index++;
                continue;
            }

            int cursor = index;
            List<Message> assistantRun = new ArrayList<>();
            while (cursor < messages.size() && messages.get(cursor).role() == Role.ASSISTANT) {
                assistantRun.add(messages.get(cursor));
                cursor++;
            }

            normalized.add(mergeAssistantRun(assistantRun));
            index = cursor;
        }

        return normalized;
    }

    private Message mergeAssistantRun(List<Message> assistantRun) {
        if (ObjectUtils.isEmpty(assistantRun)) {
            return Message.assistant("");
        }

        if (assistantRun.size() == 1) {
            return assistantRun.getFirst();
        }

        Message primary = assistantRun.stream()
                .filter(candidate -> StringUtils.isNotBlank(candidate.content()))
                .reduce((first, second) -> second)
                .orElse(assistantRun.getLast());

        String mergedThinking = assistantRun.stream()
                .map(candidate -> normalizeThinkingText(candidate.meta() == null
                        ? ""
                        : StringUtils.defaultString(candidate.meta().assistantThinking())))
                .filter(this::hasVisibleThinkingContent)
                .collect(joining("\n\n"));

        String mergedWebSearch = normalizeWebSearchActivity(assistantRun.stream()
                .map(candidate -> candidate.meta() == null
                        ? ""
                        : StringUtils.defaultString(candidate.meta().assistantWebSearch()))
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n\n")));

        List<AgentToolActivityMeta> mergedAgentToolActivities = assistantRun.stream()
                .filter(candidate -> candidate.meta() != null)
                .flatMap(candidate -> candidate.meta().agentToolActivities().stream())
                .toList();

        MessageMeta meta = primary.meta() == null ? MessageMeta.empty() : primary.meta();
        List<CitationRef> mergedCitations = meta.citations();
        MessageMeta mergedMeta = new MessageMeta(
                meta.activeSkills(),
                meta.fallbackNotices(),
                meta.cancelled(),
                meta.error(),
                mergedThinking,
                mergedWebSearch,
                mergedAgentToolActivities,
                mergedCitations
        );

        return new Message(primary.role(), primary.parts(), primary.timestamp(), mergedMeta);
    }

    private String normalizeThinkingText(String text) {
        if (text == null) {
            return "";
        }

        String withoutAnsi = ANSI_ESCAPE_PATTERN.matcher(text).replaceAll("");
        String normalizedLineEndings = withoutAnsi
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String withoutInvisible = normalizedLineEndings.replace('\u00A0', ' ');
        String withoutFormatting = UNICODE_FORMAT_PATTERN.matcher(withoutInvisible).replaceAll("");

        return NON_PRINTABLE_PATTERN.matcher(withoutFormatting).replaceAll("");
    }

    private void removeMessageComponentFromPanel(JComponent component) {
        if (component == null) {
            return;
        }

        Component current = component;
        while (current != null && current.getParent() != messagesPanel) {
            current = current.getParent();
        }

        if (current == null) {
            return;
        }

        if (component instanceof ActivityBubble thinkingBubble) {
            thinkingBubbles.remove(thinkingBubble);
            thinkingBubble.dispose();
        }

        if (component instanceof JComponent jComponent) {
            ChatMessageView view = chatMessageView(jComponent);
            if (view != null) {
                view.dispose();
                assistantBubbles.remove(view);
                if (view == currentAssistantBubble) {
                    currentAssistantBubble = null;
                }
            }
        }

        messagesPanel.remove(current);
        addBottomFiller();
        messagesPanel.revalidate();
        messagesPanel.repaint();
        refreshWebTranscript(true);
    }


    public void refreshProviders() {
        long refreshId = providerRefreshCounter.incrementAndGet();
        long scopeVersion = modelCacheService.nextScopeVersion();
        providerScopeVersion = scopeVersion;
        Thread.startVirtualThread(() -> {
            try {
                if (providerRefreshCounter.get() != refreshId) {
                    return;
                }

                List<ProviderRegistry.ProviderDef> providers = ProviderRegistry.availableProviders();
                if (providerRefreshCounter.get() != refreshId) {
                    return;
                }
                prepareProviderModels(providers, scopeVersion);
                SwingUtilities.invokeLater(() -> {
                    if (providerRefreshCounter.get() != refreshId) {
                        return;
                    }

                    boolean applied = applyProviderModels(providers, scopeVersion);
                    if (applied && modelPopup != null) {
                        modelPopup.invalidateModelList();
                        SwingUtilities.invokeLater(this::preloadModelPopup);
                    }
                });
            } catch (Exception e) {
                modelCacheService.cancelScopeVersion(scopeVersion);
                log.warn("Failed to refresh providers: {}", ExceptionUtils.getMessage(e));
            }
        });
    }

    public void setAutoScrollEnabled(boolean autoScrollEnabled) {
        this.autoScrollEnabled = autoScrollEnabled;
        if (!autoScrollEnabled) {
            autoScrollQueued = false;
        }
        updateGenerationIndicator();
        refreshJumpOverlay();
        refreshWebTranscript(false);
    }

    public boolean isAutoScrollEnabled() {
        return autoScrollEnabled;
    }

    public void cancelStreaming() {
        cancelStreaming(false);
    }

    public void cancelStreamingAndMarkCancelled() {
        cancelStreaming(true);
    }

    private void cancelStreaming(boolean markAsCancelled) {
        SendJob preparingJob = visiblePreparingJob();
        if (preparingJob != null) {
            preparingJob.cancelled.set(true);
            Thread worker = preparingJob.worker;
            if (worker != null) {
                worker.interrupt();
            }
            finishSendJob(preparingJob);
        }

        StreamingSession session = visibleStreamingSession();
        if (session != null) {
            session.cancelled.set(true);

            if (markAsCancelled) {
                String cancelledMarker = "\n\n[Cancelled]";
                appendAssistantResponse(session, cancelledMarker);
                if (currentAssistantBubble != null && isVisibleConversation(session.conversationId)) {
                    currentAssistantBubble.appendText(cancelledMarker);
                }
                persistAssistantResponse(session, findSendJobByStreamSession(session.sessionId), false);
                removeCurrentWebSearchBubbleIfBlank();
                removeCurrentActivityBubbleIfBlank();
                removeCurrentAgentToolBubblesIfBlank();
            }

            cancelSessionActiveRequest(session, true);
            Thread worker = session.worker;
            if (worker != null) {
                worker.interrupt();
            }
            session.finished = true;
            activeSessions.remove(session.sessionId);
            if (!hasLiveStreamingSession(session.conversationId)) {
                notifyConversationStreamingChanged(session.conversationId, false);
            }
            finishSendJobByStreamSession(session.sessionId);
        }

        autoScrollQueued = false;
        activeStreamSessionId = -1L;
        setVisibleStreaming(false);
        removeCurrentWebSearchBubbleIfBlank();
        removeCurrentActivityBubbleIfBlank();
        removeCurrentAgentToolBubblesIfBlank();
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        currentAssistantBubble = null;
        updateGenerationIndicator();
        SwingUtilities.invokeLater(() -> {
            inputBar.setEnabled(true);
            inputBar.requestInputFocus();
        });
    }

    private StreamingSession beginStreamingSession(UUID conversationId, ProviderService provider) {
        long streamSessionId = streamSessionCounter.incrementAndGet();
        StreamingSession session = new StreamingSession(streamSessionId, conversationId, provider);
        activeSessions.put(streamSessionId, session);
        notifyConversationStreamingChanged(conversationId, true);
        if (isVisibleConversation(conversationId)) {
            activeStreamSessionId = streamSessionId;
            setVisibleStreaming(true);
            inputBar.setEnabled(false);
        }
        updateGenerationIndicator();
        return session;
    }

    private void finishStreamingSession(StreamingSession session) {
        if (session == null) {
            return;
        }

        session.finished = true;
        activeSessions.remove(session.sessionId);
        if (!hasLiveStreamingSession(session.conversationId)) {
            notifyConversationStreamingChanged(session.conversationId, false);
        }

        if (activeStreamSessionId == session.sessionId) {
            autoScrollQueued = false;
            activeStreamSessionId = -1L;
            if (visiblePreparingJob() == null && visibleStreamingSession() == null) {
                setVisibleStreaming(false);
                inputBar.setEnabled(true);
                inputBar.requestInputFocus();
            }
            updateGenerationIndicator();
        }
    }

    private void finishSendJob(SendJob sendJob) {
        if (sendJob == null) {
            return;
        }

        sendJob.finished = true;
        activeSendJobs.remove(sendJob.jobId);

        if (isVisibleConversation(sendJob.conversationId)
                && visiblePreparingJob() == null
                && visibleStreamingSession() == null
        ) {
            setVisibleStreaming(false);
            inputBar.setEnabled(true);
        }

        updateGenerationIndicator();
    }

    private void finishSendJobByStreamSession(long streamSessionId) {
        activeSendJobs.values().stream()
                .filter(job -> Objects.equals(job.streamSessionId, streamSessionId))
                .findFirst()
                .ifPresent(this::finishSendJob);
    }

    private SendJob findSendJobByStreamSession(long streamSessionId) {
        return activeSendJobs.values().stream()
                .filter(job -> Objects.equals(job.streamSessionId, streamSessionId))
                .findFirst()
                .orElse(null);
    }

    private boolean isPreparing(SendJob sendJob) {
        return sendJob != null
                && activeSendJobs.containsKey(sendJob.jobId)
                && sendJob.phase == SendPhase.PREPARING
                && sendJob.isLive();
    }

    private boolean isVisibleConversationBusy() {
        return conversationLoading || visiblePreparingJob() != null || visibleStreamingSession() != null;
    }

    private void cancelSessionActiveRequest(StreamingSession session, boolean allowLegacyProviderFallback) {
        boolean cancelledSessionRequest = session.cancelActiveRequest();
        if (cancelledSessionRequest || !allowLegacyProviderFallback || session.hasRegisteredActiveRequest()) {
            return;
        }
        if (session.provider != null && !hasOtherLiveSessionUsingProvider(session)) {
            session.provider.cancelActiveRequest();
        }
    }

    private boolean hasOtherLiveSessionUsingProvider(StreamingSession targetSession) {
        return activeSessions.values().stream()
                .anyMatch(session -> session != targetSession
                        && session.isLive()
                        && session.provider == targetSession.provider);
    }

    private boolean hasLiveStreamingSession(UUID conversationId) {
        return activeSessions.values().stream()
                .anyMatch(session -> session.isLive() && Objects.equals(session.conversationId, conversationId));
    }

    private SendJob visiblePreparingJob() {
        UUID visibleId = activeConversationId;
        return activeSendJobs.values().stream()
                .filter(sendJob -> sendJob.phase == SendPhase.PREPARING
                        && sendJob.isLive()
                        && Objects.equals(sendJob.conversationId, visibleId)
                )
                .findFirst()
                .orElse(null);
    }

    private StreamingSession visibleStreamingSession() {
        UUID visibleId = activeConversationId;
        for (StreamingSession session : activeSessions.values()) {
            if (session.isLive() && Objects.equals(session.conversationId, visibleId)) {
                return session;
            }
        }
        return null;
    }

    private void updateGenerationIndicator() {
        SendJob preparingJob = visiblePreparingJob();
        boolean showingPreparing = preparingJob != null;
        boolean showingStreaming = !showingPreparing && visibleStreamingSession() != null;
        boolean indicatorVisible = showingPreparing || showingStreaming;

        setVisibleStreaming(indicatorVisible);
        inputBar.setCancelGenerationVisible(indicatorVisible);
        updateClearChatButtonVisibility();
        jumpToLatestOverlay.setStreaming(indicatorVisible);
        refreshJumpOverlay();
        refreshWebTranscript(false);
    }

    private List<String> sanitizeModelIds(String providerName, List<String> modelIds) {
        return modelCacheService.modelsWithLocalOverlay(providerName, modelIds);
    }

    private boolean persistAssistantMessageEvent(UUID conversationId, Message message) {
        if (assistantMessageCompletedListener == null || conversationId == null || message == null) {
            return false;
        }

        AssistantMessageEvent event = new AssistantMessageEvent(conversationId, message);
        try {
            return assistantMessageCompletedListener.persist(event);
        } catch (Exception e) {
            log.warn("Assistant message persistence listener failed: {}", ExceptionUtils.getMessage(e));
            return false;
        }
    }

    public record UserMessageEvent(
            UUID conversationId,
            Message message,
            String providerName,
            String modelId,
            ReasoningLevel reasoningLevel,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            boolean webSearchEnabled,
            String webSearchOptionId,
            boolean visibleConversation
    ) {
        @Override
        public String toString() {
            return "UserMessageEvent[conversationId=%s, message=<masked>, providerName=%s, modelId=%s, reasoningLevel=%s, agentModeEnabled=%s, agentProjectRoot=<masked>, webSearchEnabled=%s, webSearchOptionId=%s, visibleConversation=%s]"
                    .formatted(
                            conversationId,
                            providerName,
                            modelId,
                            reasoningLevel,
                            agentModeEnabled,
                            webSearchEnabled,
                            webSearchOptionId,
                            visibleConversation
                    );
        }
    }

    public record AssistantMessageEvent(UUID conversationId, Message message) {
        @Override
        public String toString() {
            return "AssistantMessageEvent[conversationId=%s, message=<masked>]".formatted(conversationId);
        }
    }

    public record HistoryTruncatedEvent(UUID conversationId, int keepMessageCount) {
    }

    public record ConversationStreamingEvent(UUID conversationId, boolean streaming) {
    }

    @FunctionalInterface
    public interface UserMessagePersistenceListener {
        UUID persist(UserMessageEvent event) throws Exception;
    }

    @FunctionalInterface
    public interface AssistantMessagePersistenceListener {
        boolean persist(AssistantMessageEvent event);
    }

    @FunctionalInterface
    interface SendPreparer {
        Message prepare(
                ComposerState composerState,
                ProviderSelectionSnapshot providerSnapshot,
                BooleanSupplier isCancelled
        ) throws Exception;
    }

}
