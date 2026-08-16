package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.FlatClientProperties;
import com.github.drafael.chat4j.chat.agent.AgentOrchestrator;
import com.github.drafael.chat4j.chat.agent.AgentProviderAdapterFactory;
import com.github.drafael.chat4j.chat.agent.AgentRunCallbacks;
import com.github.drafael.chat4j.chat.agent.AgentRunRequest;
import com.github.drafael.chat4j.chat.agent.AgentToolActivity;
import com.github.drafael.chat4j.chat.agent.LocalToolRuntime;
import com.github.drafael.chat4j.chat.agent.McpApprovalHandler;
import com.github.drafael.chat4j.chat.composer.AttachmentStager;
import com.github.drafael.chat4j.chat.composer.ComposerAttachment;
import com.github.drafael.chat4j.chat.composer.ComposerPanel;
import com.github.drafael.chat4j.chat.composer.EditComposerPanel;
import com.github.drafael.chat4j.chat.composer.FileAttachmentChip;
import com.github.drafael.chat4j.chat.composer.ImageAttachmentPreview;
import com.github.drafael.chat4j.chat.composer.InputBar;
import com.github.drafael.chat4j.chat.content.ExternalLinkSupport;
import com.github.drafael.chat4j.chat.conversation.ConversationAttachment;
import com.github.drafael.chat4j.chat.conversation.ConversationEntry;
import com.github.drafael.chat4j.chat.conversation.webview.jcef.JcefBrowserView;
import com.github.drafael.chat4j.chat.conversation.webview.system.SystemWebView;
import com.github.drafael.chat4j.chat.diagram.DiagramHtmlExporter;
import com.github.drafael.chat4j.chat.export.pdf.ConversationPdfExporter;
import com.github.drafael.chat4j.chat.export.pdf.JcefConversationPdfExporter;
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
import com.github.drafael.chat4j.mcp.McpRunProvider;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.conversation.ConversationHistoryEntry;
import com.github.drafael.chat4j.persistence.conversation.ConversationPersistenceIndeterminateException;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
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
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.DeepSeekNativeWebSearchSupport;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import com.github.drafael.chat4j.provider.support.NativeWebSearchOutcome;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderModelsResolver;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.github.drafael.chat4j.provider.support.TogetherModelSupport;
import com.github.drafael.chat4j.provider.support.WebSearchSourceUrlNormalizer;
import com.github.drafael.chat4j.stt.SpeechToTextService;
import com.github.drafael.chat4j.tts.TextToSpeechService;
import com.github.drafael.chat4j.util.Fonts;
import com.github.drafael.chat4j.util.PopupMenuSupport;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

import static com.github.drafael.chat4j.chat.conversation.webview.shared.TranscriptReadAloudToken.create;
import static com.github.drafael.chat4j.chat.render.ReadAloudTextExtractor.extract;
import static com.github.drafael.chat4j.util.ModalDialogSupport.showMessageDialog;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableSet;

@Slf4j
public class ChatPanel extends JPanel {
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_CHAT = "chat";
    private static final String CODEX_PROVIDER_NAME = "OpenAI Codex";
    private static final String COPILOT_PROVIDER_NAME = "GitHub Copilot";
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
    private static final String BUBBLE_ACTION_BAR_PROPERTY = "chat4j.bubbleActionBar";
    private static final String WEBVIEW_POINTER_DOWN_ACTION = "webview-pointer-down";
    private static final Integer COMPOSER_FADE_LAYER = 50;
    private static final boolean THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING = true;
    private static final boolean THINKING_COLLAPSED_BY_DEFAULT_WHEN_LOADING_HISTORY = true;
    private static final boolean WEB_SEARCH_COLLAPSED_BY_DEFAULT = true;
    private static final boolean AGENT_TOOLS_COLLAPSED_BY_DEFAULT = true;
    private static final String TOGETHER_AGENT_ATTACHMENT_NOTICE =
            "Attachment contents were not sent to Together Agent Mode; only metadata labels were provided.";
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
    private final ProviderModelsResolver providerModelsResolver;
    private final ModelFavoritesService modelFavoritesService;
    private final ProviderRegistry providerRegistry;
    private final AttachmentStager attachmentStager;
    private final ChatMessageViewFactory messageViewFactory;
    private final WebViewEngine webViewEngine;
    private final SystemWebView systemWebView;
    private final JcefBrowserView jcefBrowserView;
    private final TextToSpeechService textToSpeechService;
    private final SpeechToTextService speechToTextService;
    private final CodexAuthResolver codexAuthResolver;
    private final CopilotAuthResolver copilotAuthResolver;
    private final CredentialResolver credentialResolver;
    private final AgentOrchestrator configuredAgentOrchestrator;
    private volatile AgentOrchestrator agentOrchestrator;
    private volatile String agentSystemPromptAppend = "";
    private final List<ChatMessageView> assistantBubbles = new ArrayList<>();
    private final Map<ChatMessageView, CompletableFuture<ReadAloudAvailability>> readAloudAvailability = new IdentityHashMap<>();
    private boolean readAloudWebRefreshPending;
    private final List<ActivityBubble> thinkingBubbles = new ArrayList<>();
    private final JToggleButton previewToggle = new JToggleButton();
    private final JToggleButton markdownToggle = new JToggleButton();
    private final JPanel renderTogglePanel;
    private RenderMode renderMode = RenderMode.PREVIEW;
    private Consumer<RenderMode> renderModeChangedListener;
    private Consumer<String> modelSelectionRequestedListener;
    private Runnable selectedModelChangedListener;
    private Runnable modelFavoritesChangedListener;
    private Runnable modelCatalogChangedListener;
    private Runnable clearChatRequestedListener;
    private Runnable exportPdfRequestedListener;
    private DurableUserMessagePersistenceListener durableUserMessageSubmittedListener;
    private BiConsumer<UUID, UUID> durableUserMessageFailureDeliveredListener;
    private DurableAssistantMessagePersistenceListener durableAssistantMessageCompletedListener;
    private DurableHistoryMutationListener durableHistoryMutationListener;
    private Consumer<HistoryMutationEvent> durableHistoryMutationFailureDeliveredListener;
    private Consumer<ConversationStreamingEvent> conversationStreamingListener;
    private Consumer<Boolean> visibleStreamingChangedListener;
    private Consumer<WebSearchSettingsEvent> webSearchSettingsChangedListener;
    private Consumer<AgentSettingsEvent> agentSettingsChangedListener;
    private Supplier<UUID> conversationIdSupplier;
    private ModelSelectorPopup modelPopup;
    private EditingUserMessage editingUserMessage;
    private ComposerState speechToTextComposerSnapshot = ComposerState.empty();

    private final List<Message> history = new ArrayList<>();
    private final Map<Integer, ConversationHistoryEntry> userHistoryEntries = new LinkedHashMap<>();
    private int nextMessageOrdinal = 1;
    private long historyRevision;
    private Map<String, ProviderRegistry.ProviderDef> providerMap = emptyMap();
    private String selectedProviderName;
    private String selectedModelId;
    private boolean conversationLoading;
    private boolean conversationMutationPending;
    private boolean batchMessageRefresh;
    private volatile boolean removed;
    private volatile boolean shutdownInProgress;
    private boolean pdfExportRunning;
    private ActivityBubble currentAssistantWebSearchBubble;
    private ActivityBubble currentAssistantActivityBubble;
    private final Map<String, ActivityBubble> currentAssistantAgentToolBubbles = new LinkedHashMap<>();
    private ChatMessageView currentAssistantBubble;
    private final AtomicLong sendJobCounter = new AtomicLong();
    private final AtomicLong streamSessionCounter = new AtomicLong();
    private final Object terminalPersistenceLock = new Object();
    private final AtomicLong providerSelectionCounter = new AtomicLong();
    private final Map<String, Integer> credentialChangesPending = new ConcurrentHashMap<>();
    private final Map<String, Long> credentialChangeVersions = new ConcurrentHashMap<>();
    private final AtomicLong providerRefreshCounter = new AtomicLong();
    private final AtomicReference<Thread> capabilityRefreshThread = new AtomicReference<>();
    private final AtomicLong readAloudUiGeneration = new AtomicLong();
    private final AtomicLong speechToTextUiGeneration = new AtomicLong();
    private final AtomicLong openActionUiGeneration = new AtomicLong();
    private long providerScopeVersion;
    private long installedProviderScope = -1L;
    private NativeWebSearchOutcome nativeWebSearchOutcome = NativeWebSearchOutcome.UNSUPPORTED;
    private boolean requestedWebSearch;
    private StagedRuntimeLoad stagedRuntimeLoad;
    private Runnable pendingWebSearchOptOut;
    private final Map<Long, SendJob> activeSendJobs = new ConcurrentHashMap<>();
    private final Map<Long, StreamingSession> activeSessions = new ConcurrentHashMap<>();
    private final Set<Thread> shutdownPreparationWorkers = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> attachmentDiscardTasks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<PendingAssistantRecovery>> pendingCompletedAssistantRecoveries =
            new ConcurrentHashMap<>();
    private final Map<UUID, FailedUserSend> failedUserSends = new HashMap<>();
    private FailedUserSend visibleFailedRecovery;
    private final Set<UUID> blockedConversationIds = new HashSet<>();
    private final Set<UUID> discardedConversationIds = new HashSet<>();
    private PendingIndeterminateHistoryMutation pendingIndeterminateHistoryMutation;
    private PendingHistoryFailureDelivery pendingHistoryFailureDelivery;
    private volatile long activeStreamSessionId = -1L;
    private volatile boolean streaming = false;
    private volatile boolean autoScrollEnabled = true;
    private volatile UUID activeConversationId;
    private volatile UUID persistedConversationId;
    private List<PromptQuickAction> promptQuickActions = emptyList();
    private volatile SendPreparer sendPreparer = this::prepareUserMessage;
    private boolean autoScrollQueued = false;
    private int messageRow = 0;

    private static final class FailedUserSend {
        private final SendJob sendJob;
        private boolean recoveryAcknowledged;

        private FailedUserSend(SendJob sendJob) {
            this.sendJob = sendJob;
        }

        private SendJob sendJob() {
            return sendJob;
        }
    }

    private static final class PendingAssistantRecovery {
        private final ConversationHistoryEntry entry;
        private final boolean persistenceFailed;

        private PendingAssistantRecovery(ConversationHistoryEntry entry, boolean persistenceFailed) {
            this.entry = entry;
            this.persistenceFailed = persistenceFailed;
        }

        private ConversationHistoryEntry entry() {
            return entry;
        }

        private boolean persistenceFailed() {
            return persistenceFailed;
        }
    }

    private record PendingIndeterminateHistoryMutation(
            HistoryMutationEvent event,
            Runnable onCommitted,
            Runnable onConfirmedFailure
    ) {
    }

    private record PendingHistoryFailureDelivery(HistoryMutationEvent event, String message) {
    }

    private record StagedRuntimeLoad(
            UUID conversationId,
            long loadRequestId,
            boolean webSearchEnabled,
            Path agentProjectRoot,
            boolean agentModeEnabled,
            boolean agentCorrectionRequired
    ) {
    }

    private record PreparedAssistantResponse(
            ConversationHistoryEntry entry,
            String webSearchActivity
    ) {
    }

    private record ReadAloudAvailability(String sourceText, boolean speakable) {
        @Override
        public String toString() {
            return "ReadAloudAvailability[sourceText=<masked>, speakable=%s]".formatted(speakable);
        }
    }

    private record EditingUserMessage(
            int messageIndex,
            UUID conversationId,
            ConversationHistoryEntry historyEntry,
            ComposerState savedComposerState
    ) {
        @Override
        public String toString() {
            return "EditingUserMessage[messageIndex=%d, conversationId=%s, messageId=%s, ordinal=%d]".formatted(
                    messageIndex,
                    conversationId,
                    historyEntry.messageId(),
                    historyEntry.ordinal()
            );
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

    public ChatPanel(
            @NonNull ProviderModelCacheService modelCacheService,
            @NonNull ModelFavoritesService modelFavoritesService,
            @NonNull ChatMessageViewFactory messageViewFactory,
            @NonNull WebViewEngine webViewEngine,
            @NonNull TextToSpeechService textToSpeechService,
            @NonNull SpeechToTextService speechToTextService,
            @NonNull ProviderRegistry providerRegistry,
            @NonNull CopilotAuthResolver copilotAuthResolver,
            @NonNull CodexAuthResolver codexAuthResolver,
            @NonNull CredentialResolver credentialResolver,
            @NonNull StoragePaths storagePaths,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull McpRunProvider mcpRunProvider,
            @NonNull McpApprovalHandler mcpApprovalHandler
    ) {
        this.modelCacheService = modelCacheService;
        this.providerModelsResolver = new ProviderModelsResolver(modelCacheService);
        this.modelFavoritesService = modelFavoritesService;
        this.providerRegistry = providerRegistry;
        this.copilotAuthResolver = copilotAuthResolver;
        this.codexAuthResolver = codexAuthResolver;
        this.credentialResolver = credentialResolver;
        this.attachmentStager = new AttachmentStager(storagePaths);
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
        this.configuredAgentOrchestrator = new AgentOrchestrator(
                new AgentProviderAdapterFactory(attachmentSupport),
                new LocalToolRuntime(),
                mcpRunProvider,
                mcpApprovalHandler
        );
        this.agentOrchestrator = configuredAgentOrchestrator;
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
        inputBar.addWebSearchEnabledListener(this::handleWebSearchIntent);
        inputBar.addAgentModeListener(this::handleAgentModeIntent);
        inputBar.addAgentProjectRootListener(ignored -> {
            if (stagedRuntimeLoad == null) {
                emitAgentSettingsEvent();
            }
        });
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
        removed = false;
        super.addNotify();
        refreshBubbleActionBars();
        scheduleReadAloudWebRefresh();
        restoreVisibleFailedDraftIfComposerEmpty();
        boolean historyFailureDelivered = deliverPendingHistoryFailure();
        FailedUserSend failed = failedUserSendForCurrentView();
        if (failed != null) {
            inputBar.showValidationMessage("The message was not saved. Send again to retry.");
        } else if (!historyFailureDelivered && hasFailedAssistantRecovery(activeConversationId)) {
            inputBar.showValidationMessage("The assistant response could not be saved and will be retried.");
        }
        refreshProviders();
        SwingUtilities.invokeLater(this::preloadModelPopup);
    }

    @Override
    public void removeNotify() {
        removed = true;
        stopReadAloudPlayback();
        providerRefreshCounter.incrementAndGet();
        providerSelectionCounter.incrementAndGet();
        speechToTextUiGeneration.incrementAndGet();
        openActionUiGeneration.incrementAndGet();
        cancelCapabilityRefresh();
        installedProviderScope = -1L;
        nativeWebSearchOutcome = NativeWebSearchOutcome.PENDING;
        inputBar.setWebSearchPresentation(false, false, false);
        inputBar.setProviderReady(false);
        modelCacheService.cancelScopeVersion(providerScopeVersion);
        if (modelPopup != null) {
            modelPopup.dispose();
            modelPopup = null;
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

    private void prepareProviderModels(List<ProviderRegistry.ProviderDef> providers, long scopeVersion) {
        providers.forEach(provider -> modelCacheService.synchronizeScope(
                provider.name(),
                provider.baseUrl(),
                scopeVersion
        ));
    }

    private boolean applyProviderModels(List<ProviderRegistry.ProviderDef> providers, long scopeVersion) {
        Map<String, ProviderRegistry.ProviderDef> previousProviderMap = providerMap;
        if (!updateProviderMap(providers, scopeVersion)) {
            return false;
        }
        if (providerModelsChanged(previousProviderMap, providers)) {
            notifyModelCatalogChanged();
        }

        ProviderRegistry.ProviderDef selectedProvider = providerMap.get(selectedProviderName);
        boolean selectedModelUsable = selectedProvider != null && isSelectedModelUsable(selectedProvider);
        if (selectedModelUsable && selectedModelId != null) {
            selectModel(selectedProviderName, selectedModelId);
            return true;
        }

        if (selectedProviderName != null && !selectedModelUsable) {
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
        installedProviderScope = scopeVersion;
        if (providerModelsChanged(previousProviderMap, providers)) {
            notifyModelCatalogChanged();
        }
        ProviderRegistry.ProviderDef selectedProvider = providerMap.get(selectedProviderName);
        if (selectedProviderName != null
                && (selectedProvider == null || !isSelectedModelUsable(selectedProvider))) {
            clearSelectedModel();
        }
        return true;
    }

    private boolean providerModelsChanged(
            Map<String, ProviderRegistry.ProviderDef> previousProviderMap,
            List<ProviderRegistry.ProviderDef> providers
    ) {
        return previousProviderMap.size() != providers.size()
                || providers.stream().anyMatch(provider -> {
                    ProviderRegistry.ProviderDef existing = previousProviderMap.get(provider.name());
                    return existing == null || !Objects.equals(existing.baseUrl(), provider.baseUrl());
                })
                || providers.stream()
                        .map(ProviderRegistry.ProviderDef::name)
                        .anyMatch(modelCacheService::isInvalidated);
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
        modelSelectorBtn.setSelection("", "");
        inputBar.setThinkingAvailable(false);
        inputBar.setWebSearchPresentation(false, false, false);
        inputBar.setAgentModeAvailable(false);
        refreshComposerAvailability();
        if (selectionChanged && selectedModelChangedListener != null) {
            selectedModelChangedListener.run();
        }
    }

    private boolean isSelectedModelUsable(ProviderRegistry.ProviderDef providerDef) {
        Optional<List<String>> usableModels = modelCacheService.findUsableModels(
                providerDef.name(),
                providerDef.baseUrl()
        );
        if (!TogetherModelSupport.isTogether(providerDef.name())) {
            return usableModels.isPresent();
        }
        return usableModels.filter(models -> models.contains(selectedModelId)).isPresent();
    }

    private List<String> initialProviderModels(ProviderRegistry.ProviderDef providerDef) {
        List<String> models = modelCacheService.findUsableModels(providerDef.name(), providerDef.baseUrl())
                .orElse(providerDef.seedModels());
        return sanitizeModelIds(providerDef.name(), models);
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
                providerRegistry,
                this::requestModelSelection,
                this::updateProviderModelsFromPopup,
                this::notifyModelFavoritesChanged,
                this::notifyModelCatalogChanged,
                credentialResolver
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

    private void requestModelSelection(String providerName, String modelId) {
        String modelKey = ModelSelectionCodec.format(providerName, modelId);
        if (modelSelectionRequestedListener == null) {
            setSelectedModel(modelKey);
            return;
        }
        modelSelectionRequestedListener.accept(modelKey);
    }

    private void selectModel(String providerName, String modelId) {
        long selectionId = providerSelectionCounter.incrementAndGet();
        selectedProviderName = providerName;
        selectedModelId = modelId;
        modelSelectorBtn.setSelection(providerName, modelId);

        refreshComposerAvailability();

        updateCapabilityAvailability(selectionId);

        if (selectedModelChangedListener != null) {
            selectedModelChangedListener.run();
        }
    }

    private void onSend() {
        if (shutdownInProgress) {
            return;
        }
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
        FailedUserSend failedUserSend = failedUserSendForCurrentView();
        if (failedUserSend != null && retryFailedUserSend(failedUserSend, composerState)) {
            return;
        }
        if (failedUserSend != null) {
            if (visibleFailedRecovery != failedUserSend) {
                inputBar.showValidationMessage(
                        "This conversation has an unsaved message. Clear the current draft and reopen the conversation to recover it."
                );
                return;
            }
            discardFailedUserSend(failedUserSend);
        }

        if (!preflightSend()) {
            return;
        }
        SendRuntimeSnapshot runtime = captureSendRuntime();
        if (runtime == null) {
            inputBar.showValidationMessage("Select a model/provider before sending.");
            return;
        }

        boolean agentModeEnabled = inputBar.isAgentModeEnabled();
        Path agentProjectRoot = inputBar.getAgentProjectRoot();

        UUID conversationId = resolveConversationId();
        long sendJobId = sendJobCounter.incrementAndGet();
        SendJob sendJob = new SendJob(
                sendJobId,
                conversationId,
                runtime,
                new ArrayList<>(history),
                inputBar.getEffectiveReasoningLevel(),
                requestedWebSearch,
                agentModeEnabled,
                agentProjectRoot,
                agentSystemPromptAppend,
                conversationId == null
        );
        sendJob.userMessageOrdinal = nextMessageOrdinal;
        sendJob.assistantMessageOrdinal = sendJob.userMessageOrdinal + 1;
        sendJob.composerState = composerState;
        if (sendJob.createsConversation) {
            activeConversationId = sendJob.conversationId;
        }
        activeSendJobs.put(sendJobId, sendJob);
        beginPreparing(sendJob);

        sendJob.worker = Thread.startVirtualThread(() -> {
            try {
                admitProvider(sendJob);
                ProviderSelectionSnapshot admittedSnapshot = providerSelectionSnapshot(sendJob);
                Message preparedMessage = sendPreparer.prepare(
                        composerState,
                        admittedSnapshot,
                        sendJob.cancelled::get
                );
                Message userMessage = finalizePreparedUserMessage(sendJob, new Message(
                        preparedMessage.role(),
                        preparedMessage.parts(),
                        sendJob.userMessageTimestamp,
                        preparedMessage.meta()
                ));
                if (!sendJob.isLive()) {
                    discardStagedAttachments(userMessage);
                    return;
                }
                sendJob.preparedUserMessage = userMessage;
                SwingUtilities.invokeLater(() -> {
                    try {
                        commitPreparedSend(sendJob, userMessage);
                    } catch (Exception | LinkageError e) {
                        handlePreparationFailure(sendJob, e);
                    }
                });
            } catch (Exception | LinkageError e) {
                SwingUtilities.invokeLater(() -> handlePreparationFailure(sendJob, e));
            }
        });
    }

    private FailedUserSend failedUserSendForCurrentView() {
        if (activeConversationId != null) {
            return failedUserSends.get(activeConversationId);
        }
        return failedUserSends.values().stream()
                .filter(failed -> failed.sendJob().createsConversation)
                .findFirst()
                .orElse(null);
    }

    private boolean retryFailedUserSend(FailedUserSend failed, ComposerState composerState) {
        if (!Objects.equals(failed.sendJob().composerState, composerState)) {
            return false;
        }

        SendJob original = failed.sendJob();
        boolean originCurrent = original.createsConversation
                ? activeConversationId == null || Objects.equals(activeConversationId, original.conversationId)
                : Objects.equals(activeConversationId, original.conversationId);
        if (!originCurrent || original.preparedUserMessage == null) {
            return false;
        }
        if (!preflightSend()) {
            return true;
        }
        SendRuntimeSnapshot runtime = captureSendRuntime();
        if (runtime == null) {
            inputBar.showValidationMessage("Select a model/provider before retrying.");
            return true;
        }
        var retry = new SendJob(
                sendJobCounter.incrementAndGet(),
                original.conversationId,
                runtime,
                original.historySnapshot,
                inputBar.getEffectiveReasoningLevel(),
                requestedWebSearch,
                inputBar.isAgentModeEnabled(),
                inputBar.getAgentProjectRoot(),
                agentSystemPromptAppend,
                original.createsConversation
        );
        retry.userMessageId = original.userMessageId;
        retry.userMessageTimestamp = original.userMessageTimestamp;
        retry.userMessageOrdinal = original.userMessageOrdinal;
        retry.assistantMessageOrdinal = original.assistantMessageOrdinal;
        retry.composerState = original.composerState;
        retry.preparedUserMessage = original.preparedUserMessage;
        retry.persistenceAlreadyCanonical = original.persistenceAlreadyCanonical;
        retry.providerContinuationCancelled = false;
        if (retry.createsConversation) {
            activeConversationId = retry.conversationId;
        }
        activeSendJobs.put(retry.jobId, retry);
        beginPreparing(retry);
        retry.worker = Thread.startVirtualThread(() -> {
            try {
                admitProvider(retry);
                Message retryMessage = finalizePreparedUserMessage(retry, original.preparedUserMessage);
                retry.preparedUserMessage = retryMessage;
                SwingUtilities.invokeLater(() -> commitPreparedSend(retry, retryMessage));
            } catch (Exception | LinkageError e) {
                SwingUtilities.invokeLater(() -> handlePreparationFailure(retry, e));
            }
        });
        return true;
    }

    private void beginPreparing(SendJob sendJob) {
        sendJob.phase = SendPhase.PREPARING;
        if (isVisibleConversation(sendJob.conversationId)) {
            inputBar.setEnabled(false);
        }
        updateGenerationIndicator();
    }

    private void commitPreparedSend(SendJob sendJob, Message userMessage) {
        sendJob.preparedUserMessage = userMessage;
        if (!isPreparing(sendJob)) {
            discardStagedAttachments(userMessage);
            finishSendJob(sendJob);
            return;
        }

        boolean visibleConversation = isVisibleConversation(sendJob.conversationId);
        if (durableUserMessageSubmittedListener == null) {
            handleDurableUserMessageFailure(
                    sendJob,
                    new IllegalStateException("Durable user persistence is not configured")
            );
            return;
        }

        UserMessageEvent event = userMessageEvent(sendJob, userMessage, visibleConversation);
        sendJob.durableUserMessageSubmissionStarted = true;
        var settlement = new CompletableFuture<Void>();
        sendJob.durableUserMessageSettlement = settlement;
        CompletionStage<UUID> persistence;
        try {
            persistence = durableUserMessageSubmittedListener.persist(event);
        } catch (Exception e) {
            settlement.complete(null);
            handleDurableUserMessageFailure(sendJob, e);
            return;
        }
        if (persistence == null) {
            settlement.complete(null);
            handleDurableUserMessageFailure(sendJob, new IllegalStateException("User persistence returned no completion stage"));
            return;
        }
        persistence.whenComplete((conversationId, error) -> SwingUtilities.invokeLater(() -> {
            try {
                if (shutdownInProgress) {
                    if (error != null && !(unwrapCompletion(error) instanceof ConversationPersistenceIndeterminateException)) {
                        discardStagedAttachments(userMessage);
                    }
                    abandonSendJob(sendJob);
                    return;
                }
                if (error != null) {
                    handleDurableUserMessageFailure(sendJob, unwrapCompletion(error));
                    return;
                }
                if (!isPreparing(sendJob)) {
                    finishSendJob(sendJob);
                    return;
                }
                completePreparedSend(sendJob, userMessage, conversationId, isVisibleConversation(sendJob.conversationId));
            } finally {
                settlement.complete(null);
            }
        }));
    }

    private void completePreparedSend(
            SendJob sendJob,
            Message userMessage,
            UUID persistedConversationId,
            boolean visibleConversation
    ) {
        failedUserSends.remove(sendJob.conversationId);
        sendJob.conversationId = persistedConversationId == null ? sendJob.conversationId : persistedConversationId;
        if (visibleConversation) {
            inputBar.clear();
            history.add(userMessage);
            refreshModelSelectorConversationState();
            int userMessageIndex = history.size() - 1;
            userHistoryEntries.put(
                    userMessageIndex,
                    new ConversationHistoryEntry(sendJob.userMessageId, sendJob.userMessageOrdinal, userMessage)
            );
            nextMessageOrdinal = sendJob.userMessageOrdinal + 1;
            addUserBubble(userMessage, userMessageIndex);
            updateClearChatButtonVisibility();
        }

        if (credentialsChangedSinceAdmission(sendJob)) {
            sendJob.providerContinuationCancelled = true;
            if (visibleConversation) {
                inputBar.showValidationMessage(
                        "Provider credentials changed while the message was being saved. Regenerate after the update finishes."
                );
                inputBar.requestInputFocus();
            }
        }
        if (sendJob.providerContinuationCancelled) {
            finishSendJob(sendJob);
            return;
        }

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
                addAssistantBubble(currentAssistantBubble);
            }
        }
        startAssistantStream(sendJob, streamHistory);
    }

    private void handleDurableUserMessageFailure(SendJob sendJob, Throwable error) {
        boolean indeterminate = error instanceof ConversationPersistenceIndeterminateException;
        String safeMessage = ProviderExceptionMapper.sanitizeMessage(error, sendJob.apiKey);
        boolean visibleConversation = isVisibleConversation(sendJob.conversationId);
        var failed = new FailedUserSend(sendJob);
        failedUserSends.put(sendJob.conversationId, failed);
        finishSendJob(sendJob);
        if (!indeterminate) {
            releaseFailedProvisionalConversation(sendJob);
        }
        if (visibleConversation && !removed && !shutdownInProgress) {
            inputBar.showValidationMessage(indeterminate
                    ? "Checking whether the message was saved."
                    : StringUtils.defaultIfBlank(safeMessage, "Failed to save message"));
            inputBar.requestInputFocus();
            if (!indeterminate) {
                visibleFailedRecovery = failed;
                acknowledgeFailedUserSend(failed);
            }
        }
    }

    private UserMessageEvent userMessageEvent(SendJob sendJob, Message userMessage, boolean visibleConversation) {
        return new UserMessageEvent(
                sendJob.conversationId,
                sendJob.userMessageId,
                sendJob.userMessageOrdinal,
                sendJob.createsConversation,
                userMessage,
                sendJob.runtime.providerName(),
                sendJob.runtime.modelId(),
                sendJob.reasoningLevel,
                sendJob.agentModeEnabled,
                sendJob.agentProjectRoot,
                sendJob.requestedWebSearch,
                visibleConversation
        );
    }

    private Throwable unwrapCompletion(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    private void finishSuccessfulStream(
            StreamingSession session,
            SendJob sendJob,
            PreparedAssistantResponse preparedResponse
    ) {
        if (!session.isLive()) {
            return;
        }
        try {
            boolean persisted = preparedResponse != null || session.persisted.get();
            applyPreparedAssistantResponse(session, preparedResponse);
            if (isVisibleConversation(session.conversationId)) {
                if (!persisted && currentAssistantBubble != null) {
                    removeMessageComponentFromPanel(currentAssistantBubble.component());
                }
                if (currentAssistantActivityBubble != null) {
                    currentAssistantActivityBubble.setStreaming(false);
                }
                removeCurrentWebSearchBubbleIfBlank();
                removeCurrentActivityBubbleIfBlank();
                currentAssistantWebSearchBubble = null;
                currentAssistantActivityBubble = null;
                clearCurrentAgentToolBubbleState();
                currentAssistantBubble = null;
            }
        } finally {
            try {
                finishStreamingSession(session);
            } finally {
                finishSendJob(sendJob);
            }
        }
    }

    private void handlePreparationFailure(SendJob sendJob, Throwable error) {
        String safeMessage = ProviderExceptionMapper.sanitizeMessage(error, sendJob.apiKey);
        if (!activeSendJobs.containsKey(sendJob.jobId)) {
            sendJob.clearCredentialReferences();
            return;
        }

        boolean visibleConversation = isVisibleConversation(sendJob.conversationId);
        Long streamSessionId = sendJob.streamSessionId;
        if (streamSessionId != null) {
            discardStreamingSession(activeSessions.get(streamSessionId));
        }
        finishSendJob(sendJob);
        if (!sendJob.persistenceAlreadyCanonical) {
            releaseFailedProvisionalConversation(sendJob);
        }

        if (!visibleConversation) {
            return;
        }

        if (error instanceof SendCancelledException || sendJob.cancelled.get()) {
            inputBar.requestInputFocus();
            return;
        }

        inputBar.showValidationMessage(StringUtils.defaultIfBlank(safeMessage, "Failed to prepare message"));
        inputBar.requestInputFocus();
    }

    private void startAssistantStream(
            UUID conversationId,
            long expectedHistoryRevision,
            int assistantMessageOrdinal,
            HistoryMutationEvent mutationEvent,
            Runnable commitRegeneration
    ) {
        if (!preflightSend()) {
            return;
        }
        SendRuntimeSnapshot runtime = captureSendRuntime();
        if (runtime == null) {
            inputBar.showValidationMessage("Select a model/provider before regenerating.");
            return;
        }
        SendJob sendJob = new SendJob(
                sendJobCounter.incrementAndGet(),
                conversationId,
                runtime,
                new ArrayList<>(history),
                inputBar.getEffectiveReasoningLevel(),
                requestedWebSearch,
                inputBar.isAgentModeEnabled(),
                inputBar.getAgentProjectRoot(),
                agentSystemPromptAppend
        );
        sendJob.assistantMessageOrdinal = assistantMessageOrdinal;
        activeSendJobs.put(sendJob.jobId, sendJob);
        beginPreparing(sendJob);
        sendJob.worker = Thread.startVirtualThread(() -> {
            try {
                admitProvider(sendJob);
                SwingUtilities.invokeLater(() -> {
                    if (shutdownInProgress
                            || !isPreparing(sendJob)
                            || !isVisibleConversation(sendJob.conversationId)
                            || historyRevision != expectedHistoryRevision
                    ) {
                        sendJob.cancelled.set(true);
                        finishSendJob(sendJob);
                        return;
                    }
                    persistRegenerationMutation(sendJob, expectedHistoryRevision, mutationEvent, commitRegeneration);
                });
            } catch (Exception | LinkageError e) {
                SwingUtilities.invokeLater(() -> handlePreparationFailure(sendJob, e));
            }
        });
    }

    private void persistRegenerationMutation(
            SendJob sendJob,
            long expectedHistoryRevision,
            HistoryMutationEvent mutationEvent,
            Runnable commitRegeneration
    ) {
        conversationMutationPending = true;
        refreshComposerAvailability();
        CompletionStage<Void> persistence;
        if (durableHistoryMutationListener == null || mutationEvent.conversationId() == null) {
            conversationMutationPending = false;
            refreshComposerAvailability();
            finishRegenerationMutationFailure(
                    sendJob,
                    mutationEvent,
                    new IllegalStateException("Durable history persistence is not configured")
            );
            return;
        }
        sendJob.durableHistoryMutationSubmissionStarted = true;
        try {
            persistence = durableHistoryMutationListener.persist(mutationEvent);
        } catch (Exception e) {
            conversationMutationPending = false;
            refreshComposerAvailability();
            finishRegenerationMutationFailure(sendJob, mutationEvent, e);
            return;
        }
        if (persistence == null) {
            conversationMutationPending = false;
            refreshComposerAvailability();
            finishRegenerationMutationFailure(
                    sendJob,
                    mutationEvent,
                    new IllegalStateException("History persistence returned no completion stage")
            );
            return;
        }
        persistence.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            conversationMutationPending = false;
            if (shutdownInProgress) {
                abandonSendJob(sendJob);
                return;
            }
            refreshComposerAvailability();
            if (error != null) {
                Throwable failure = unwrapCompletion(error);
                if (failure instanceof ConversationPersistenceIndeterminateException) {
                    deferIndeterminateHistoryMutation(
                            mutationEvent,
                            () -> continueRegenerationAfterHistoryCommit(
                                    sendJob,
                                    expectedHistoryRevision,
                                    commitRegeneration
                            ),
                            () -> finishRegenerationMutationFailure(sendJob, mutationEvent, failure)
                    );
                    return;
                }
                finishRegenerationMutationFailure(sendJob, mutationEvent, failure);
                return;
            }
            continueRegenerationAfterHistoryCommit(sendJob, expectedHistoryRevision, commitRegeneration);
        }));
    }

    private void finishRegenerationMutationFailure(
            SendJob sendJob,
            HistoryMutationEvent mutationEvent,
            Throwable failure
    ) {
        handlePreparationFailure(sendJob, failure);
        if (removed) {
            pendingHistoryFailureDelivery = new PendingHistoryFailureDelivery(
                    mutationEvent,
                    StringUtils.defaultIfBlank(
                            ExceptionUtils.getMessage(failure),
                            "Failed to save conversation change"
                    )
            );
            return;
        }
        markHistoryMutationFailureDelivered(mutationEvent);
    }

    private void continueRegenerationAfterHistoryCommit(
            SendJob sendJob,
            long expectedHistoryRevision,
            Runnable commitRegeneration
    ) {
        if (!isPreparing(sendJob)
                || !isVisibleConversation(sendJob.conversationId)
                || historyRevision != expectedHistoryRevision
        ) {
            sendJob.cancelled.set(true);
            finishSendJob(sendJob);
            return;
        }
        try {
            nextMessageOrdinal = sendJob.assistantMessageOrdinal;
            commitRegeneration.run();
            if (credentialsChangedSinceAdmission(sendJob)) {
                sendJob.providerContinuationCancelled = true;
                inputBar.showValidationMessage(
                        "Provider credentials changed while the conversation change was being saved. Regenerate after the update finishes."
                );
                inputBar.requestInputFocus();
            }
            if (sendJob.providerContinuationCancelled) {
                finishSendJob(sendJob);
                return;
            }
            startAssistantStream(sendJob, new ArrayList<>(history));
        } catch (Exception | LinkageError e) {
            handlePreparationFailure(sendJob, e);
        }
    }

    private void startAssistantStream(SendJob sendJob, List<Message> requestHistory) {
        sendJob.phase = SendPhase.STREAMING;
        StreamingSession session = beginStreamingSession(sendJob.conversationId, sendJob.provider);
        sendJob.streamSessionId = session.sessionId;
        refreshWebTranscript(true);

        AgentRunCallbacks callbacks = new AgentRunCallbacks(
                token -> handleAssistantToken(session, sendJob, token),
                thinkingToken -> handleAssistantThinkingToken(session, sendJob, thinkingToken),
                part -> handleAssistantPart(session, part),
                citation -> handleAssistantCitation(session, citation),
                activity -> handleAgentToolActivity(session, activity),
                () -> {
                    PreparedAssistantResponse preparedResponse;
                    synchronized (terminalPersistenceLock) {
                        if (!canAcceptStreamingCallback(session)) {
                            return;
                        }
                        flushThinkTagParser(session, sendJob);
                        if (!session.beginTerminalCallback()) {
                            return;
                        }
                        finalizeConsultedSourceActivity(session);
                        preparedResponse = prepareAssistantResponse(session, sendJob);
                    }
                    SwingUtilities.invokeLater(() -> finishSuccessfulStream(session, sendJob, preparedResponse));
                },
                error -> {
                    String errorText;
                    PreparedAssistantResponse preparedResponse;
                    synchronized (terminalPersistenceLock) {
                        if (!canAcceptStreamingCallback(session)) {
                            return;
                        }
                        flushThinkTagParser(session, sendJob);
                        if (!session.beginTerminalCallback()) {
                            return;
                        }
                        Exception safeError = ProviderExceptionMapper.map(error, sendJob.apiKey);
                        String details = StringUtils.defaultIfBlank(ExceptionUtils.getMessage(safeError), "Unknown error");
                        log.warn("Assistant stream failed for provider={} model={} conversationId={}: {}",
                                sendJob.runtime.providerName(),
                                sendJob.runtime.modelId(),
                                session.conversationId,
                                details);
                        errorText = "\n\n[Error: %s]".formatted(details);
                        appendAssistantResponse(session, errorText);
                        preparedResponse = prepareAssistantResponse(session, sendJob);
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (!session.isLive()) {
                            return;
                        }
                        try {
                            if (currentAssistantBubble != null && isVisibleConversation(session.conversationId)) {
                                currentAssistantBubble.appendText(errorText);
                            }
                            applyPreparedAssistantResponse(session, preparedResponse);
                            if (isVisibleConversation(session.conversationId)) {
                                if (currentAssistantActivityBubble != null) {
                                    currentAssistantActivityBubble.setStreaming(false);
                                }
                                removeCurrentWebSearchBubbleIfBlank();
                                removeCurrentActivityBubbleIfBlank();
                                currentAssistantWebSearchBubble = null;
                                currentAssistantActivityBubble = null;
                                clearCurrentAgentToolBubbleState();
                                currentAssistantBubble = null;
                            }
                        } finally {
                            try {
                                finishStreamingSession(session);
                            } finally {
                                finishSendJob(sendJob);
                            }
                        }
                    });
                }
        );

        session.worker = Thread.startVirtualThread(() -> {
            try {
                ensureCredentialsCurrentForTransport(sendJob);
                if (sendJob.agentModeEnabled) {
                    AgentRunRequest request = new AgentRunRequest(
                            requestHistory,
                            sendJob.reasoningLevel,
                            sendJob.agentProjectRoot,
                            emptyList(),
                            session.cancelled::get
                    );
                    agentOrchestrator.streamCompletion(
                            sendJob.runtime.providerName(),
                            sendJob.runtime.modelId(),
                            sendJob.runtime.baseUrl(),
                            sendJob.apiKey,
                            sendJob.agentSystemPromptAppend,
                            sessionScopedProvider(session),
                            request,
                            callbacks
                    );
                    return;
                }

                List<Message> effectiveHistory = prepareNativeWebSearchActivity(sendJob, session, requestHistory, session.cancelled::get);
                session.provider.streamCompletion(
                        effectiveHistory,
                        sendJob.reasoningLevel,
                        new WebSearchRequestOptions(nativeWebSearchEnabled(sendJob), sendJob.providerAdmitted),
                        callbacks.onToken(),
                        callbacks.onThinkingToken(),
                        callbacks.onPart(),
                        callbacks.onCitation(),
                        query -> handleAssistantWebSearchQuery(session, query),
                        source -> handleAssistantWebSearchSource(session, source),
                        callbacks.onComplete(),
                        callbacks.onError(),
                        session.cancelled::get,
                        session::registerActiveRequest,
                        session::clearActiveRequest
                );
            } catch (Exception | LinkageError e) {
                callbacks.onError().accept(asStreamException(e));
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
                        source -> {
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
                    Consumer<WebSearchSource> onWebSearchSource,
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
                        onWebSearchSource,
                        onComplete,
                        onError,
                        isCancelled,
                        session::registerActiveRequest,
                        session::clearActiveRequest
                );
            }


            @Override
            public void cancelActiveRequest() {
                session.cancelActiveRequest();
            }
        };
    }

    private List<Message> prepareNativeWebSearchActivity(
            SendJob sendJob,
            StreamingSession session,
            List<Message> requestHistory,
            BooleanSupplier isCancelled
    ) {
        if (!sendJob.webSearchEnabled) {
            return requestHistory;
        }
        ensureNotCancelled(isCancelled);
        if (usesConsultedSourceActivity(sendJob)) {
            initializeConsultedSourceActivity(session);
        }
        return requestHistory;
    }

    private boolean usesConsultedSourceActivity(SendJob sendJob) {
        return Strings.CS.equals(sendJob.runtime.providerName(), CODEX_PROVIDER_NAME)
                || DeepSeekNativeWebSearchSupport.supports(
                        sendJob.runtime.providerName(),
                        sendJob.runtime.modelId(),
                        sendJob.runtime.baseUrl()
                );
    }

    private boolean nativeWebSearchEnabled(SendJob sendJob) {
        return sendJob.webSearchEnabled && sendJob.runtime.webSearchOutcome().supported();
    }

    private void initializeConsultedSourceActivity(StreamingSession session) {
        synchronized (terminalPersistenceLock) {
            synchronized (session.webSearchSourceLock) {
                if (!canAcceptStreamingCallback(session)) {
                    return;
                }
                session.consultedSourceMode = true;
                session.webSearchQueries.clear();
                session.webSearchSources.clear();
                replaceWebSearchActivity(session, "");
            }
        }
    }

    private void handleAssistantWebSearchQuery(StreamingSession session, String query) {
        String normalizedQuery = StringUtils.normalizeSpace(query);
        if (StringUtils.isBlank(normalizedQuery)) {
            return;
        }
        String snapshot;
        synchronized (terminalPersistenceLock) {
            synchronized (session.webSearchSourceLock) {
                if (!canAcceptStreamingCallback(session) || !session.consultedSourceMode) {
                    return;
                }
                session.webSearchQueries.add(normalizedQuery);
                snapshot = renderConsultedSourceActivity(session, false);
                replaceWebSearchActivity(session, snapshot);
            }
        }
        SwingUtilities.invokeLater(() -> showWebSearchActivity(session, snapshot));
    }

    private void handleAssistantWebSearchSource(StreamingSession session, WebSearchSource source) {
        WebSearchSourceUrlNormalizer.normalize(source == null ? null : source.url()).ifPresent(normalized -> {
            String snapshot;
            synchronized (terminalPersistenceLock) {
                synchronized (session.webSearchSourceLock) {
                    if (!canAcceptStreamingCallback(session) || !session.consultedSourceMode) {
                        return;
                    }
                    String title = StringUtils.defaultIfBlank(StringUtils.normalizeSpace(source.title()), normalized.host());
                    session.webSearchSources.putIfAbsent(
                            normalized.key(),
                            new WebSearchSource(title, normalized.displayUrl())
                    );
                    snapshot = renderConsultedSourceActivity(session, false);
                    replaceWebSearchActivity(session, snapshot);
                }
            }
            SwingUtilities.invokeLater(() -> {
                if (session.isLive() && !session.terminalCallbackStarted.get() && isVisibleSession(session)) {
                    showWebSearchActivity(session, snapshot);
                }
            });
        });
    }

    private void finalizeConsultedSourceActivity(StreamingSession session) {
        synchronized (session.webSearchSourceLock) {
            if (!session.consultedSourceMode) {
                return;
            }
            boolean observedSearch = !session.webSearchQueries.isEmpty() || !session.webSearchSources.isEmpty();
            replaceWebSearchActivity(session, observedSearch ? renderConsultedSourceActivity(session, true) : "");
        }
    }

    private String renderConsultedSourceActivity(StreamingSession session, boolean completed) {
        StringBuilder activity = new StringBuilder();
        if (!session.webSearchQueries.isEmpty()) {
            activity.append("**Searched**\n");
            session.webSearchQueries.forEach(query -> activity.append("- ").append(query).append("\n"));
        }
        if (!session.webSearchSources.isEmpty() || completed) {
            if (!activity.isEmpty()) {
                activity.append("\n");
            }
            activity.append("**Sources consulted**\n");
            if (session.webSearchSources.isEmpty()) {
                activity.append("- No source URLs returned.\n");
            } else {
                session.webSearchSources.values().forEach(source -> activity
                        .append("- [")
                        .append(escapeMarkdownLinkLabel(source.title()))
                        .append("](<")
                        .append(source.url())
                        .append(">)\n"));
            }
        }
        return normalizeWebSearchActivity(activity.toString());
    }

    private void replaceWebSearchActivity(StreamingSession session, String activity) {
        synchronized (session.webSearchActivity) {
            session.webSearchActivity.setLength(0);
            session.webSearchActivity.append(StringUtils.defaultString(activity));
        }
    }

    private String escapeMarkdownLinkLabel(String value) {
        return StringUtils.defaultString(value).replace("[", "\\[").replace("]", "\\]");
    }

    private void showWebSearchActivity(StreamingSession session, String webSearchActivity) {
        showWebSearchActivity(session, webSearchActivity, false);
    }

    private void showWebSearchActivity(StreamingSession session, String webSearchActivity, boolean terminalSnapshot) {
        String normalizedActivity = normalizeWebSearchActivity(webSearchActivity);
        boolean currentSession = terminalSnapshot
                ? session != null && isVisibleConversation(session.conversationId)
                : session != null && session.isLive() && isVisibleSession(session);
        if (!currentSession || StringUtils.isBlank(normalizedActivity)) {
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
        String formattedActivity;
        synchronized (terminalPersistenceLock) {
            if (!canAcceptStreamingCallback(session) || activity == null) {
                return;
            }
            formattedActivity = formatAgentToolActivity(activity);
            if (StringUtils.isBlank(formattedActivity)) {
                return;
            }
            session.agentToolActivities.add(activity);
        }

        SwingUtilities.invokeLater(() -> {
            if (!session.isLive() || !isVisibleSession(session)) {
                return;
            }

            if (currentAssistantBubble != null && !hasAssistantPayload(currentAssistantBubble)) {
                removeMessageComponentFromPanel(currentAssistantBubble.component());
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
        List<AttachmentRef> stagedAttachments = new ArrayList<>();

        if (!composerState.activeSkills().isEmpty()) {
            String skillDirective = "Activated skills: %s".formatted(String.join(", ", composerState.activeSkills()));
            parts.add(new TextPart(skillDirective));
        }

        String text = composerState.text().trim();
        if (!text.isEmpty()) {
            parts.add(new TextPart(text));
        }

        try {
            for (ComposerAttachment attachment : composerState.attachments()) {
                ensureNotCancelled(isCancelled);
                ContentPart part = toAttachmentPart(attachment, isCancelled);
                parts.add(part);
                stagedAttachments.add(attachmentRef(part));
            }

            ensureNotCancelled(isCancelled);
            List<String> fallbackNotices = buildFallbackNotices(
                    composerState.attachments(),
                    providerSnapshot,
                    isCancelled
            );
            MessageMeta meta = new MessageMeta(composerState.activeSkills(), fallbackNotices, false, "");
            return new Message(Role.USER, parts, Instant.now(), meta);
        } catch (IOException | RuntimeException | Error e) {
            stagedAttachments.forEach(attachmentStager::discard);
            throw e;
        }
    }

    private ContentPart toAttachmentPart(ComposerAttachment attachment, BooleanSupplier isCancelled) throws IOException {
        ensureNotCancelled(isCancelled);
        AttachmentRef attachmentRef = attachmentStager.stage(attachment);
        return attachment.image()
                ? new ImagePart(attachmentRef, null, null)
                : new FilePart(attachmentRef);
    }

    private void discardStagedAttachments(Message message) {
        if (message == null) {
            return;
        }
        discardAttachmentRefs(message.parts().stream()
                .map(this::attachmentRef)
                .filter(Objects::nonNull)
                .toList());
    }

    private void discardStreamingResponseAttachments(StreamingSession session) {
        if (session == null || session.persisted.get()) {
            return;
        }
        List<AttachmentRef> attachments;
        synchronized (session.responseParts) {
            attachments = session.responseParts.stream()
                    .map(this::attachmentRef)
                    .filter(Objects::nonNull)
                    .toList();
            session.responseParts.clear();
        }
        discardAttachmentRefs(attachments);
    }

    private void discardAttachmentRefs(Collection<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        List<AttachmentRef> snapshot = List.copyOf(attachments);
        CompletableFuture<Void> createdTask;
        try {
            createdTask = CompletableFuture.runAsync(
                    () -> snapshot.forEach(attachmentStager::discard),
                    command -> Thread.ofVirtual().name("chat4j-attachment-discard").start(command)
            );
        } catch (Throwable t) {
            createdTask = CompletableFuture.failedFuture(t);
        }
        CompletableFuture<Void> task = createdTask;
        attachmentDiscardTasks.add(task);
        task.whenComplete((ignored, error) -> attachmentDiscardTasks.remove(task));
    }

    private List<String> buildFallbackNotices(
            List<ComposerAttachment> attachments,
            ProviderSelectionSnapshot providerSnapshot,
            BooleanSupplier isCancelled
    ) {
        if (ObjectUtils.isEmpty(attachments)) {
            return emptyList();
        }
        boolean hasImage = attachments.stream().anyMatch(ComposerAttachment::image);
        boolean hasFile = attachments.stream().anyMatch(attachment -> !attachment.image());
        return buildFallbackNotices(hasImage, hasFile, providerSnapshot, isCancelled);
    }

    private List<String> buildFallbackNotices(
            boolean hasImage,
            boolean hasFile,
            ProviderSelectionSnapshot providerSnapshot,
            BooleanSupplier isCancelled
    ) {
        ensureNotCancelled(isCancelled);
        List<String> notices = new ArrayList<>();
        boolean supportsImageInput = ProviderCapabilityResolver.supportsImageInput(
                providerSnapshot.capabilities(),
                providerSnapshot.providerName(),
                providerSnapshot.modelId(),
                providerSnapshot.baseUrl(),
                providerSnapshot.apiKey()
        );
        ensureNotCancelled(isCancelled);
        boolean supportsFileInput = ProviderCapabilityResolver.supportsFileInput(providerSnapshot.capabilities());
        if (hasImage && !supportsImageInput) {
            notices.add(buildImageFallbackNotice(providerSnapshot));
        }
        if (hasFile && !supportsFileInput) {
            notices.add(buildFileFallbackNotice(providerSnapshot, supportsImageInput));
        }
        return notices;
    }

    private Message finalizePreparedUserMessage(SendJob sendJob, Message message) {
        MessageMeta meta = message.meta();
        boolean togetherAgent = sendJob.agentModeEnabled
                && TogetherModelSupport.isTogether(sendJob.runtime.providerName());
        boolean attachmentPresent = containsAttachment(message.parts())
                || sendJob.historySnapshot.stream().anyMatch(historyMessage -> containsAttachment(historyMessage.parts()));
        List<String> notices = meta.fallbackNotices();
        if (togetherAgent && attachmentPresent) {
            notices = List.of(TOGETHER_AGENT_ATTACHMENT_NOTICE);
        } else if (!togetherAgent && notices.contains(TOGETHER_AGENT_ATTACHMENT_NOTICE)) {
            boolean hasImage = message.parts().stream().anyMatch(this::isImageAttachment);
            boolean hasFile = message.parts().stream().anyMatch(FilePart.class::isInstance);
            notices = hasImage || hasFile
                    ? buildFallbackNotices(
                            hasImage,
                            hasFile,
                            providerSelectionSnapshot(sendJob),
                            sendJob.cancelled::get
                    )
                    : emptyList();
        }
        if (notices.equals(meta.fallbackNotices())) {
            return message;
        }
        MessageMeta finalizedMeta = new MessageMeta(
                meta.activeSkills(),
                notices,
                meta.cancelled(),
                meta.error(),
                meta.assistantThinking(),
                meta.assistantWebSearch(),
                meta.agentToolActivities(),
                meta.citations()
        );
        return new Message(message.role(), message.parts(), message.timestamp(), finalizedMeta);
    }

    private boolean containsAttachment(List<ContentPart> parts) {
        return parts.stream().anyMatch(part -> isImageAttachment(part) || part instanceof FilePart);
    }

    private boolean isImageAttachment(ContentPart part) {
        return part instanceof ImagePart || part instanceof GeneratedImagePart;
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

    private void updateCapabilityAvailability(long selectionId) {
        cancelCapabilityRefresh();
        ProviderRegistry.ProviderDef providerDef = selectedProviderDef();
        if (providerDef == null || StringUtils.isBlank(selectedModelId)) {
            nativeWebSearchOutcome = NativeWebSearchOutcome.UNSUPPORTED;
            inputBar.setThinkingAvailable(false);
            applyWebSearchPresentation();
            inputBar.setAgentModeAvailable(false);
            return;
        }

        String providerName = providerDef.name();
        String modelId = selectedModelId;
        ProviderCapabilities capabilities = providerDef.capabilities();
        boolean togetherProvider = TogetherModelSupport.isTogether(providerName);
        boolean initialSupportsThinking = togetherProvider
                ? TogetherModelSupport.supportsReasoning(providerDef.baseUrl(), modelId)
                : ProviderCapabilityResolver.supportsReasoning(capabilities, providerName, modelId);
        boolean initialSupportsTools = togetherProvider
                ? TogetherModelSupport.supportsTools(providerDef.baseUrl(), modelId)
                : ProviderCapabilityResolver.supportsToolInvocation(capabilities, providerName, modelId);
        inputBar.setThinkingAvailable(initialSupportsThinking);
        applyNativeWebSearchOutcome(resolveCachedNativeWebSearchOutcome(providerDef, modelId));
        inputBar.setAgentModeAvailable(!nativeWebSearchOutcome.required() && initialSupportsTools);

        if (StringUtils.isBlank(providerDef.baseUrl())
                || nativeWebSearchOutcome != NativeWebSearchOutcome.PENDING
                || Strings.CS.equals(providerName, COPILOT_PROVIDER_NAME)) {
            return;
        }

        String baseUrl = providerDef.baseUrl();
        Thread refreshThread = Thread.ofVirtual().name("chat4j-provider-capabilities").unstarted(() -> {
            try {
                if (!capabilityRefreshCurrent(selectionId, providerName, modelId)) {
                    return;
                }
                String apiKey = resolveProviderApiKey(providerDef);
                NativeWebSearchOutcome resolved = ProviderCapabilityResolver.nativeWebSearchOutcome(
                        providerName,
                        modelId,
                        baseUrl,
                        providerDef.defaultBaseUrl(),
                        apiKey
                );
                boolean supportsThinking = ProviderCapabilityResolver.supportsReasoning(
                        capabilities,
                        providerName,
                        modelId,
                        baseUrl,
                        apiKey
                );
                boolean supportsTools = ProviderCapabilityResolver.supportsToolInvocation(
                        capabilities,
                        providerName,
                        modelId,
                        baseUrl,
                        apiKey
                );
                SwingUtilities.invokeLater(() -> {
                    if (!capabilityRefreshCurrent(selectionId, providerName, modelId)) {
                        return;
                    }
                    applyNativeWebSearchOutcome(resolved);
                    inputBar.setThinkingAvailable(supportsThinking);
                    inputBar.setAgentModeAvailable(!resolved.required() && supportsTools);
                });
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.debug("Failed to refresh capabilities for {}::{}", providerName, modelId, e);
                }
            } finally {
                capabilityRefreshThread.compareAndSet(Thread.currentThread(), null);
            }
        });
        capabilityRefreshThread.set(refreshThread);
        refreshThread.start();
    }

    private void handleWebSearchIntent(boolean enabled) {
        if (stagedRuntimeLoad == null) {
            setRequestedWebSearch(enabled, true);
        }
    }

    private void handleAgentModeIntent(boolean enabled) {
        if (stagedRuntimeLoad == null) {
            if (enabled && requestedWebSearch) {
                setRequestedWebSearch(false, true);
            }
            emitAgentSettingsEvent();
        }
    }

    private void setRequestedWebSearch(boolean enabled, boolean notify) {
        boolean changed = requestedWebSearch != enabled;
        requestedWebSearch = enabled;
        pendingWebSearchOptOut = null;
        if (enabled && inputBar.isAgentModeRequested()) {
            boolean agentWasEffectivelyEnabled = inputBar.isAgentModeEnabled();
            inputBar.setAgentModeEnabled(false);
            if (!agentWasEffectivelyEnabled) {
                emitAgentSettingsEvent();
            }
        }
        applyWebSearchPresentation();
        if (notify && changed && stagedRuntimeLoad == null
                && webSearchSettingsChangedListener != null && persistedConversationId != null) {
            webSearchSettingsChangedListener.accept(new WebSearchSettingsEvent(persistedConversationId, enabled));
        }
    }

    void setRequestedWebSearch(boolean enabled) {
        setRequestedWebSearch(enabled, false);
    }

    private boolean preflightSend() {
        if (installedProviderScope < 0L) {
            inputBar.showValidationMessage("Provider configuration is still loading. Try again in a moment.");
            return false;
        }
        if (selectedProviderName != null && credentialChangesPending.containsKey(selectedProviderName)) {
            inputBar.showValidationMessage("Provider credentials are still updating. Try again in a moment.");
            return false;
        }
        if (requestedWebSearch && nativeWebSearchOutcome == NativeWebSearchOutcome.PENDING) {
            UUID conversationId = persistedConversationId;
            long selectionId = providerSelectionCounter.get();
            pendingWebSearchOptOut = () -> {
                if (Objects.equals(conversationId, persistedConversationId)
                        && providerSelectionCounter.get() == selectionId
                        && requestedWebSearch
                        && nativeWebSearchOutcome == NativeWebSearchOutcome.PENDING) {
                    handleWebSearchIntent(false);
                }
            };
            inputBar.showValidationMessage(
                    "Web Search support could not be confirmed.",
                    "Turn off Web Search",
                    this::turnOffPendingWebSearch
            );
            return false;
        }
        return true;
    }

    void turnOffPendingWebSearch() {
        if (pendingWebSearchOptOut != null) {
            pendingWebSearchOptOut.run();
        }
    }

    public void setOnWebSearchSettingsChanged(Consumer<WebSearchSettingsEvent> listener) {
        webSearchSettingsChangedListener = listener;
    }

    public void setOnAgentSettingsChanged(Consumer<AgentSettingsEvent> listener) {
        agentSettingsChangedListener = listener;
    }

    private void emitAgentSettingsEvent() {
        if (stagedRuntimeLoad == null && agentSettingsChangedListener != null && persistedConversationId != null) {
            agentSettingsChangedListener.accept(new AgentSettingsEvent(
                    persistedConversationId,
                    inputBar.isAgentModeRequested(),
                    inputBar.getAgentProjectRoot()
            ));
        }
    }

    private void applyWebSearchPresentation() {
        boolean effective = nativeWebSearchOutcome.required()
                || nativeWebSearchOutcome.optional() && requestedWebSearch;
        boolean availabilityChanged = inputBar.isWebSearchAvailable() != nativeWebSearchOutcome.supported();
        inputBar.setWebSearchPresentation(nativeWebSearchOutcome.supported(), effective, nativeWebSearchOutcome.required());
        if (availabilityChanged) {
            refreshEmptyStatePanel();
        }
    }

    private void applyNativeWebSearchOutcome(NativeWebSearchOutcome outcome) {
        if (stagedRuntimeLoad != null) {
            pendingWebSearchOptOut = null;
            nativeWebSearchOutcome = outcome;
            applyWebSearchPresentation();
            return;
        }
        pendingWebSearchOptOut = null;
        nativeWebSearchOutcome = outcome;
        boolean agentRequestMustBeCleared = inputBar.isAgentModeRequested()
                && (outcome.required() || outcome.optional() && requestedWebSearch);
        if (agentRequestMustBeCleared) {
            boolean agentWasEffectivelyEnabled = inputBar.isAgentModeEnabled();
            inputBar.setAgentModeEnabled(false);
            if (!agentWasEffectivelyEnabled) {
                emitAgentSettingsEvent();
            }
        }
        if (outcome == NativeWebSearchOutcome.UNSUPPORTED && requestedWebSearch) {
            setRequestedWebSearch(false, true);
        } else {
            applyWebSearchPresentation();
        }
    }

    private boolean capabilityRefreshCurrent(long selectionId, String providerName, String modelId) {
        return !removed
                && !shutdownInProgress
                && !Thread.currentThread().isInterrupted()
                && isSelectedModel(selectionId, providerName, modelId);
    }

    public void invalidateSelectedProviderRuntimeIfChanged(
            Map<String, ProviderRegistry.ProviderRuntimeConfig> runtimeConfigByProvider
    ) {
        runSynchronouslyOnEdt(() -> {
            ProviderRegistry.ProviderDef selected = selectedProviderDef();
            if (selected != null && !providerRegistry.matchesRuntimeConfig(
                    selected,
                    runtimeConfigByProvider.get(selected.name())
            )) {
                invalidateSelectedProviderRuntimeOnEdt();
            }
        });
    }

    public void invalidateSelectedProviderCapabilityEvidence(Collection<String> providerNames) {
        runSynchronouslyOnEdt(() -> {
            List<String> affectedProviderNames = providerNames == null
                    ? emptyList()
                    : providerNames.stream().filter(Objects::nonNull).distinct().toList();
            if (affectedProviderNames.isEmpty()) {
                return;
            }
            providerRefreshCounter.incrementAndGet();
            affectedProviderNames.forEach(providerName -> {
                credentialChangesPending.merge(providerName, 1, Integer::sum);
                credentialChangeVersions.merge(providerName, 1L, Long::sum);
            });
            if (modelPopup != null) {
                modelPopup.invalidateModelList();
            }
            if (!selectedProviderAffected(affectedProviderNames)) {
                return;
            }
            providerSelectionCounter.incrementAndGet();
            pendingWebSearchOptOut = null;
            inputBar.clearValidationMessage();
            cancelCapabilityRefresh();
            nativeWebSearchOutcome = NativeWebSearchOutcome.PENDING;
            applyWebSearchPresentation();
        });
    }

    public void settleSelectedProviderCredentialChange(Collection<String> providerNames) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> settleSelectedProviderCredentialChange(providerNames));
            return;
        }
        List<String> settledProviderNames = providerNames == null
                ? emptyList()
                : providerNames.stream().filter(Objects::nonNull).distinct().toList();
        if (settledProviderNames.isEmpty()) {
            return;
        }
        settledProviderNames.forEach(providerName -> credentialChangesPending.computeIfPresent(
                providerName,
                (ignored, pendingCount) -> pendingCount > 1 ? pendingCount - 1 : null
        ));
        if (!credentialChangesPending.isEmpty() || shutdownInProgress || removed) {
            return;
        }
        refreshProviders();
    }

    private boolean selectedProviderAffected(Collection<String> providerNames) {
        return providerNames != null && providerNames.contains(selectedProviderName);
    }

    private long credentialChangeVersion(String providerName) {
        return credentialChangeVersions.getOrDefault(providerName, 0L);
    }

    private boolean credentialsChangedSinceAdmission(SendJob sendJob) {
        String providerName = sendJob.runtime.providerName();
        return credentialChangesPending.containsKey(providerName)
                || credentialChangeVersion(providerName) != sendJob.admittedCredentialVersion;
    }

    private void ensureCredentialsCurrentForTransport(SendJob sendJob) {
        if (credentialsChangedSinceAdmission(sendJob)) {
            throw new IllegalStateException(
                    "Provider credentials changed after the conversation update was saved. Regenerate after the update finishes."
            );
        }
    }

    private void invalidateSelectedProviderRuntimeOnEdt() {
        invalidateSelectedProviderRuntimeOnEdt(true);
    }

    private void invalidateSelectedProviderRuntimeOnEdt(boolean clearValidation) {
        providerSelectionCounter.incrementAndGet();
        pendingWebSearchOptOut = null;
        if (clearValidation) {
            inputBar.clearValidationMessage();
        }
        cancelCapabilityRefresh();
        installedProviderScope = -1L;
        providerMap = emptyMap();
        nativeWebSearchOutcome = NativeWebSearchOutcome.PENDING;
        inputBar.setWebSearchPresentation(false, false, false);
        inputBar.setProviderReady(false);
        if (modelPopup != null) {
            modelPopup.invalidateModelList();
        }
    }

    private void runSynchronouslyOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update provider runtime state on the EDT", e);
        }
    }

    private void cancelCapabilityRefresh() {
        Thread refreshThread = capabilityRefreshThread.get();
        if (refreshThread != null) {
            refreshThread.interrupt();
        }
    }

    private boolean isSelectedModel(long selectionId, String providerName, String modelId) {
        return providerSelectionCounter.get() == selectionId
                && Strings.CS.equals(selectedProviderName, providerName)
                && Strings.CS.equals(selectedModelId, modelId);
    }

    private NativeWebSearchOutcome resolveCachedNativeWebSearchOutcome(
            ProviderRegistry.ProviderDef providerDef,
            String modelId
    ) {
        return ProviderCapabilityResolver.nativeWebSearchOutcomeFromCachedEndpoints(
                providerDef.name(),
                modelId,
                providerDef.baseUrl(),
                providerDef.defaultBaseUrl(),
                providerRegistry.cachedModelSupportedEndpoints(providerDef, modelId)
        );
    }

    private SendRuntimeSnapshot captureSendRuntime() {
        ProviderRegistry.ProviderDef providerDef = selectedProviderDef();
        if (providerDef == null || installedProviderScope < 0L || StringUtils.isBlank(selectedModelId)) {
            return null;
        }
        return new SendRuntimeSnapshot(providerDef, selectedModelId, nativeWebSearchOutcome);
    }

    private ProviderSelectionSnapshot providerSelectionSnapshot(SendJob sendJob) {
        return new ProviderSelectionSnapshot(
                sendJob.runtime.providerName(),
                sendJob.runtime.modelId(),
                sendJob.runtime.capabilities(),
                sendJob.runtime.baseUrl(),
                sendJob.apiKey
        );
    }

    private void admitProvider(SendJob sendJob) {
        ensureNotCancelled(sendJob.cancelled::get);
        if (sendJob.agentModeEnabled
                && (sendJob.agentProjectRoot == null || !Files.isDirectory(sendJob.agentProjectRoot))) {
            throw new IllegalArgumentException("Select a valid project folder to enable Agent Mode.");
        }
        String providerName = sendJob.runtime.providerName();
        validateTogetherModelAdmission(sendJob);
        long credentialVersion = credentialChangeVersion(providerName);
        if (credentialChangesPending.containsKey(providerName)) {
            throw new IllegalStateException("Provider credentials are still updating.");
        }
        if (sendJob.agentModeEnabled && (sendJob.webSearchEnabled || sendJob.runtime.webSearchOutcome().required())) {
            throw new IllegalArgumentException("Agent Mode and Web Search cannot be enabled together.");
        }
        if ((sendJob.requestedWebSearch || sendJob.webSearchEnabled)
                && !sendJob.runtime.webSearchOutcome().supported()) {
            throw new IllegalArgumentException("Native Web Search is no longer available for the selected provider configuration.");
        }
        ProviderRegistry.ProviderDef providerDefinition = sendJob.runtime.providerDefinition();
        boolean requiresCopilotRevalidation = !sendJob.providerAdmitted
                && sendJob.webSearchEnabled
                && Strings.CS.equals(providerDefinition.name(), COPILOT_PROVIDER_NAME);
        if (requiresCopilotRevalidation
                && !resolveCachedNativeWebSearchOutcome(providerDefinition, sendJob.runtime.modelId()).supported()) {
            throw new IllegalArgumentException("Native Web Search is no longer available for the selected provider configuration.");
        }
        try {
            sendJob.provider = providerDefinition.factory().create(sendJob.runtime.modelId());
            sendJob.apiKey = sendJob.provider.apiKey();
            ensureNotCancelled(sendJob.cancelled::get);
            if (credentialChangesPending.containsKey(providerName)
                    || credentialChangeVersion(providerName) != credentialVersion) {
                throw new IllegalStateException("Provider credentials changed while the request was being prepared.");
            }
            if (requiresCopilotRevalidation
                    && !resolveCachedNativeWebSearchOutcome(providerDefinition, sendJob.runtime.modelId()).supported()) {
                throw new IllegalArgumentException("Native Web Search is no longer available for the selected provider configuration.");
            }
            sendJob.providerAdmitted = true;
            sendJob.admittedCredentialVersion = credentialVersion;
        } catch (RuntimeException | Error e) {
            sendJob.clearCredentialReferences();
            throw e;
        }
    }

    private void validateTogetherModelAdmission(SendJob sendJob) {
        if (sendJob.providerAdmitted || !TogetherModelSupport.isTogether(sendJob.runtime.providerName())) {
            return;
        }
        String modelId = sendJob.runtime.modelId();
        if (!TogetherModelSupport.isServerlessChatModel(modelId)) {
            throw new IllegalArgumentException("The selected Together model is not in Chat4J's reviewed serverless catalog.");
        }
        boolean usable = modelCacheService.findUsableModels(
                        sendJob.runtime.providerName(),
                        sendJob.runtime.baseUrl()
                )
                .filter(models -> models.contains(modelId))
                .isPresent();
        if (!usable) {
            throw new IllegalArgumentException("The selected Together model is not available in the current model list.");
        }
    }

    private String resolveProviderApiKey(ProviderRegistry.ProviderDef providerDef) {
        if (providerDef == null) {
            return null;
        }

        String apiKey = credentialResolver.resolveApiKey(providerDef.envVar(), null);
        if (StringUtils.isNotBlank(apiKey)) {
            return apiKey;
        }

        if (Strings.CS.equals(providerDef.name(), "OpenAI Codex")) {
            return codexAuthResolver.resolveBearerTokenOrNull();
        }

        if (Strings.CS.equals(providerDef.name(), COPILOT_PROVIDER_NAME)) {
            return copilotAuthResolver.resolveBearerTokenOrNull();
        }

        return null;
    }

    private static Exception asStreamException(Throwable failure) {
        return failure instanceof Exception exception
                ? exception
                : new IllegalStateException(
                        "Assistant stream failed with %s.".formatted(failure.getClass().getSimpleName()),
                        failure
                );
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
        bubble.component().putClientProperty(BUBBLE_ACTION_BAR_PROPERTY, bar);
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
                && hasSpeakableReadAloudContent(bubble);
    }

    private boolean hasSpeakableReadAloudContent(ChatMessageView bubble) {
        CompletableFuture<ReadAloudAvailability> future = readAloudAvailability.get(bubble);
        ReadAloudAvailability availability = future == null ? null : future.getNow(null);
        return availability != null
                && availability.speakable()
                && Strings.CS.equals(availability.sourceText(), readAloudSourceText(bubble));
    }

    private void prepareReadAloudAvailability(ChatMessageView bubble) {
        String sourceText = readAloudSourceText(bubble);
        CompletableFuture<ReadAloudAvailability> future = extractReadAloudAvailability(sourceText);
        CompletableFuture<ReadAloudAvailability> previous = readAloudAvailability.put(bubble, future);
        if (previous != null) {
            previous.cancel(true);
        }
        future.thenRun(() -> {
            if (removed) {
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (removed
                        || readAloudAvailability.get(bubble) != future
                        || !assistantBubbles.contains(bubble)
                        || !Strings.CS.equals(sourceText, readAloudSourceText(bubble))
                ) {
                    return;
                }
                Object actionBar = bubble.component().getClientProperty(BUBBLE_ACTION_BAR_PROPERTY);
                if (actionBar instanceof JPanel bar) {
                    updateBubbleActionBar(bar, bubble, Role.ASSISTANT);
                }
                scheduleReadAloudWebRefresh();
            });
        });
    }

    private static CompletableFuture<ReadAloudAvailability> extractReadAloudAvailability(String sourceText) {
        CompletableFuture<ReadAloudAvailability> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("chat4j-read-aloud-availability").unstarted(() -> future.complete(
                new ReadAloudAvailability(sourceText, StringUtils.isNotBlank(extract(sourceText)))
        ));
        future.whenComplete((ignored, error) -> {
            if (future.isCancelled()) {
                worker.interrupt();
            }
        });
        worker.start();
        return future;
    }

    private void clearReadAloudAvailability() {
        readAloudAvailability.values().forEach(future -> future.cancel(true));
        readAloudAvailability.clear();
    }

    private void removeReadAloudAvailability(ChatMessageView bubble) {
        CompletableFuture<ReadAloudAvailability> future = readAloudAvailability.remove(bubble);
        if (future != null) {
            future.cancel(true);
        }
    }

    private void scheduleReadAloudWebRefresh() {
        if (readAloudWebRefreshPending || removed || !isBrowserConversationEnabled()) {
            return;
        }
        readAloudWebRefreshPending = true;
        CompletableFuture<?>[] pending = readAloudAvailability.values().toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(pending).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            readAloudWebRefreshPending = false;
            if (!removed && !shutdownInProgress) {
                refreshWebTranscript(false);
            }
        }));
    }

    private void readBubbleAloud(ChatMessageView bubble) {
        long uiGeneration = readAloudUiGeneration.incrementAndGet();
        if (speechToTextService.active()) {
            showReadAloudStatus(uiGeneration, "Finish or cancel transcription before using read aloud.");
            return;
        }
        textToSpeechService.readAloud(
                swingReadAloudKey(bubble),
                readAloudSourceText(bubble),
                message -> showReadAloudError(uiGeneration, message),
                message -> showReadAloudStatus(uiGeneration, message),
                () -> refreshReadAloudControls(uiGeneration)
        );
    }

    private String swingReadAloudKey(ChatMessageView bubble) {
        return "swing:%d".formatted(System.identityHashCode(bubble));
    }

    private String readAloudSourceText(ChatMessageView bubble) {
        return StringUtils.trimToEmpty(bubble.getFullText());
    }

    private void showReadAloudError(long uiGeneration, String message) {
        if (shutdownInProgress || removed || uiGeneration != readAloudUiGeneration.get()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!shutdownInProgress && !removed && uiGeneration == readAloudUiGeneration.get()) {
                showMessageDialog(this, message, JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    private void showReadAloudStatus(long uiGeneration, String message) {
        if (shutdownInProgress || removed || uiGeneration != readAloudUiGeneration.get()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (shutdownInProgress || removed || uiGeneration != readAloudUiGeneration.get()) {
                return;
            }
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
        readAloudUiGeneration.incrementAndGet();
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
        ConversationHistoryEntry historyEntry = userHistoryEntries.get(messageIndex);
        if (historyEntry == null) {
            inputBar.showValidationMessage("This message cannot be edited until its history identity is available.");
            return;
        }
        editingUserMessage = new EditingUserMessage(
                messageIndex,
                resolveConversationId(),
                historyEntry,
                inputBar.getComposerState()
        );
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
        if (conversationMutationPending) {
            return;
        }
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
        if (shutdownInProgress) {
            return;
        }
        EditingUserMessage state = editingUserMessage;
        if (state == null) {
            return;
        }

        Message replacement = editedReplacementMessage(state.messageIndex());
        if (replacement == null) {
            return;
        }

        var replacementEntry = new ConversationHistoryEntry(
                state.historyEntry().messageId(),
                state.historyEntry().ordinal(),
                replacement
        );
        submitHistoryMutation(
                new HistoryMutationEvent(state.conversationId(), HistoryMutationType.EDIT, replacementEntry),
                () -> {
                    if (editingUserMessage != state) {
                        return;
                    }
                    history.set(state.messageIndex(), replacement);
                    userHistoryEntries.put(state.messageIndex(), replacementEntry);
                    finishEditingAndRestoreComposer(state);
                    loadHistoryPreservingUserEntries();
                    inputBar.requestInputFocus();
                }
        );
    }

    private void saveEditedUserMessageAndRegenerate() {
        if (shutdownInProgress) {
            return;
        }
        EditingUserMessage state = editingUserMessage;
        if (state == null) {
            return;
        }
        if (selectedProviderDef() == null || StringUtils.isBlank(selectedModelId)) {
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

        UUID conversationId = state.conversationId();
        int keepCount = state.messageIndex() + 1;
        long expectedHistoryRevision = historyRevision;
        var replacementEntry = new ConversationHistoryEntry(
                state.historyEntry().messageId(),
                state.historyEntry().ordinal(),
                replacement
        );
        startAssistantStream(
                conversationId,
                expectedHistoryRevision,
                replacementEntry.ordinal() + 1,
                new HistoryMutationEvent(conversationId, HistoryMutationType.EDIT_AND_TRUNCATE, replacementEntry),
                () -> {
            if (editingUserMessage != state) {
                throw new SendCancelledException();
            }
            clearPendingAssistantRecovery(state.conversationId());
            history.set(state.messageIndex(), replacement);
            userHistoryEntries.put(state.messageIndex(), replacementEntry);
            if (history.size() > keepCount) {
                history.subList(keepCount, history.size()).clear();
            }

            finishEditingAndRestoreComposer(state);
            loadHistoryPreservingUserEntries();
            prepareRegenerationBubbles();
        });
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

    private void submitHistoryMutation(HistoryMutationEvent event, Runnable successAction) {
        if (shutdownInProgress) {
            return;
        }
        if (durableHistoryMutationListener == null || event.conversationId() == null) {
            finishHistoryMutationFailure(
                    event,
                    new IllegalStateException("Durable history persistence is not configured")
            );
            return;
        }
        conversationMutationPending = true;
        refreshComposerAvailability();
        CompletionStage<Void> persistence;
        try {
            persistence = durableHistoryMutationListener.persist(event);
        } catch (Exception e) {
            finishHistoryMutationFailure(event, e);
            return;
        }
        if (persistence == null) {
            finishHistoryMutationFailure(
                    event,
                    new IllegalStateException("History persistence returned no completion stage")
            );
            return;
        }
        persistence.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            conversationMutationPending = false;
            if (shutdownInProgress
                    || !Objects.equals(activeConversationId, event.conversationId())
            ) {
                return;
            }
            refreshComposerAvailability();
            if (error != null) {
                Throwable failure = unwrapCompletion(error);
                if (failure instanceof ConversationPersistenceIndeterminateException) {
                    deferIndeterminateHistoryMutation(
                            event,
                            successAction,
                            () -> finishHistoryMutationFailure(event, failure)
                    );
                    return;
                }
                finishHistoryMutationFailure(event, failure);
                return;
            }
            successAction.run();
        }));
    }

    private void deferIndeterminateHistoryMutation(
            HistoryMutationEvent event,
            Runnable onCommitted,
            Runnable onConfirmedFailure
    ) {
        pendingIndeterminateHistoryMutation = new PendingIndeterminateHistoryMutation(
                event,
                onCommitted,
                onConfirmedFailure
        );
        if (!removed && !shutdownInProgress && Objects.equals(activeConversationId, event.conversationId())) {
            inputBar.showValidationMessage("Checking whether the conversation change was saved.");
        }
    }

    public void resolveIndeterminateHistoryMutation(UUID conversationId, boolean committed) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> resolveIndeterminateHistoryMutation(conversationId, committed));
            return;
        }
        setConversationPersistenceBlocked(conversationId, false);
        PendingIndeterminateHistoryMutation pending = pendingIndeterminateHistoryMutation;
        if (pending == null || !Objects.equals(pending.event().conversationId(), conversationId)) {
            return;
        }
        pendingIndeterminateHistoryMutation = null;
        if (committed) {
            pending.onCommitted().run();
        } else {
            pending.onConfirmedFailure().run();
        }
    }

    private void markHistoryMutationFailureDelivered(HistoryMutationEvent event) {
        if (durableHistoryMutationFailureDeliveredListener != null) {
            durableHistoryMutationFailureDeliveredListener.accept(event);
        }
    }

    private void finishHistoryMutationFailure(HistoryMutationEvent event, Throwable error) {
        conversationMutationPending = false;
        if (shutdownInProgress || !Objects.equals(activeConversationId, event.conversationId())) {
            return;
        }
        String message = StringUtils.defaultIfBlank(
                ExceptionUtils.getMessage(error),
                "Failed to save conversation change"
        );
        if (removed) {
            pendingHistoryFailureDelivery = new PendingHistoryFailureDelivery(event, message);
            return;
        }
        deliverHistoryFailure(event, message);
    }

    private boolean deliverPendingHistoryFailure() {
        PendingHistoryFailureDelivery pending = pendingHistoryFailureDelivery;
        if (pending == null
                || shutdownInProgress
                || !Objects.equals(activeConversationId, pending.event().conversationId())
        ) {
            return false;
        }
        pendingHistoryFailureDelivery = null;
        deliverHistoryFailure(pending.event(), pending.message());
        return true;
    }

    private void deliverHistoryFailure(HistoryMutationEvent event, String message) {
        refreshComposerAvailability();
        inputBar.showValidationMessage(message);
        inputBar.requestInputFocus();
        markHistoryMutationFailureDelivered(event);
    }

    private void loadHistoryPreservingUserEntries() {
        Map<Integer, ConversationHistoryEntry> retainedEntries = new LinkedHashMap<>(userHistoryEntries);
        int retainedNextMessageOrdinal = nextMessageOrdinal;
        loadHistory(new ArrayList<>(history));
        nextMessageOrdinal = retainedNextMessageOrdinal;
        retainedEntries.forEach((index, entry) -> {
            if (index >= 0 && index < history.size() && history.get(index).role() == Role.USER) {
                userHistoryEntries.put(index, entry);
            }
        });
    }

    private boolean canRegenerateFrom(ChatMessageView bubble) {
        if (selectedProviderDef() == null || isVisibleConversationBusy()) {
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
        if (shutdownInProgress || selectedProviderDef() == null || isVisibleConversationBusy()) {
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
        if (shutdownInProgress) {
            return;
        }
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before regenerating.");
            return;
        }
        if (selectedProviderDef() == null || StringUtils.isBlank(selectedModelId)) {
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
        ConversationHistoryEntry retainedEntry = userHistoryEntries.get(keepCount - 1);
        if (retainedEntry == null) {
            inputBar.showValidationMessage("This response cannot be regenerated until its history identity is available.");
            return;
        }
        long expectedHistoryRevision = historyRevision;
        startAssistantStream(
                conversationId,
                expectedHistoryRevision,
                retainedEntry.ordinal() + 1,
                new HistoryMutationEvent(conversationId, HistoryMutationType.TRUNCATE, retainedEntry),
                () -> {
            clearPendingAssistantRecovery(conversationId);
            truncateHistoryAndBubbles(keepCount);
            prepareRegenerationBubbles();
        });
    }

    private void truncateHistoryAndBubbles(int keepCount) {
        if (history.size() > keepCount) {
            history.subList(keepCount, history.size()).clear();
        }

        userHistoryEntries.keySet().removeIf(index -> index >= keepCount);
        loadHistoryPreservingUserEntries();
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        currentAssistantBubble = null;
    }

    private void prepareRegenerationBubbles() {
        SendJob preparingJob = visiblePreparingJob();
        if (preparingJob != null
                && preparingJob.agentModeEnabled
                && TogetherModelSupport.isTogether(preparingJob.runtime.providerName())
                && history.stream().anyMatch(message -> containsAttachment(message.parts()))) {
            ActivityBubble attachmentNotice = new ActivityBubble("Attachment notice", false);
            addActivityBubble(attachmentNotice, TOGETHER_AGENT_ATTACHMENT_NOTICE);
        }
        if (preparingJob != null && preparingJob.webSearchEnabled) {
            currentAssistantActivityBubble = new ActivityBubble(THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING);
            currentAssistantActivityBubble.setStreaming(true);
            currentAssistantActivityBubble.setVisible(false);
            addActivityBubble(currentAssistantActivityBubble, null);

            currentAssistantWebSearchBubble = new ActivityBubble("Web Search", WEB_SEARCH_COLLAPSED_BY_DEFAULT);
            currentAssistantWebSearchBubble.setVisible(false);
            addActivityBubble(currentAssistantWebSearchBubble, null);
        }

        currentAssistantBubble = createMessageView(Role.ASSISTANT);
        addAssistantBubble(currentAssistantBubble);
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
        long uiGeneration = speechToTextUiGeneration.incrementAndGet();
        speechToTextService.cancel(speechToTextCallbacks(uiGeneration));
    }

    public void disposeViewResources() {
        clearReadAloudAvailability();
        disposeMessageViews();
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
    }

    private void startSpeechToTextRecording() {
        if (editingUserMessage != null || conversationLoading || isVisibleConversationBusy()) {
            inputBar.showValidationMessage("Speech to Text is not available right now.");
            return;
        }
        stopReadAloudPlayback();
        speechToTextComposerSnapshot = inputBar.getComposerState();
        inputBar.showPreparingSpeechToTextState();
        long uiGeneration = speechToTextUiGeneration.incrementAndGet();
        speechToTextService.startRecording(speechToTextCallbacks(uiGeneration));
        refreshBubbleActionBars();
        refreshWebTranscript(false, true);
    }

    private SpeechToTextService.Callbacks speechToTextCallbacks(long uiGeneration) {
        return new SpeechToTextService.Callbacks() {
            @Override
            public void stateChanged() {
                if (!speechToTextUiCurrent(uiGeneration)) {
                    return;
                }
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
                if (!speechToTextUiCurrent(uiGeneration)) {
                    return;
                }
                if (StringUtils.isNotBlank(message)) {
                    inputBar.showValidationMessage(message);
                }
            }

            @Override
            public void error(String message) {
                if (!speechToTextUiCurrent(uiGeneration)) {
                    return;
                }
                inputBar.setComposerState(speechToTextComposerSnapshot);
                inputBar.clearSpeechToTextState();
                inputBar.showValidationMessage(message);
                refreshComposerAvailability();
            }

            @Override
            public void transcript(String text) {
                if (!speechToTextUiCurrent(uiGeneration)) {
                    return;
                }
                inputBar.setComposerState(speechToTextComposerSnapshot);
                inputBar.appendTranscriptToRawSnapshot(speechToTextComposerSnapshot.text(), text);
                inputBar.clearSpeechToTextState();
                inputBar.requestInputFocus();
                refreshComposerAvailability();
            }

            @Override
            public void level(double rms, double peak) {
                if (!speechToTextUiCurrent(uiGeneration)) {
                    return;
                }
                inputBar.updateSpeechToTextLevel(rms, peak);
            }
        };
    }

    private boolean speechToTextUiCurrent(long uiGeneration) {
        return !shutdownInProgress && !removed && uiGeneration == speechToTextUiGeneration.get();
    }

    private void refreshComposerAvailability() {
        inputBar.setConversationBusy(isVisibleConversationBusy());
        updatePdfExportAvailability();
        inputBar.setProviderReady(selectedProviderDef() != null && StringUtils.isNotBlank(selectedModelId));
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

        StreamingSession typingSession = typingIndicatorSession();
        boolean showTypingIndicator = typingSession != null;
        int[] messageIndex = {0};
        List<ConversationEntry> entries = new ArrayList<>(Arrays.stream(messagesPanel.getComponents())
                .filter(component -> !"filler".equals(component.getName()))
                .map(component -> toConversationEntry(component, messageIndex, showTypingIndicator))
                .filter(Objects::nonNull)
                .toList());
        if (typingSession != null) {
            entries.add(ConversationEntry.typing(typingSession.sessionId));
        }
        boolean shouldScrollToBottom = autoScrollEnabled && scrollToBottom;
        boolean showJumpButton = streaming;
        boolean readAloudAvailable = !speechToTextService.active() && textToSpeechService.isReadAloudAvailable();
        Set<Integer> readAloudMessageIndexes = readAloudMessageIndexes();
        int activeReadAloudMessageIndex = activeWebReadAloudMessageIndex(entries);
        if (isSystemWebViewEnabled()) {
            systemWebView.setTranscript(
                    entries,
                    renderMode,
                    detectDarkMode(),
                    shouldScrollToBottom,
                    showJumpButton,
                    readAloudAvailable,
                    readAloudMessageIndexes,
                    activeReadAloudMessageIndex
            );
            if (forceReload) {
                systemWebView.reload(shouldScrollToBottom);
            }
            return;
        }
        jcefBrowserView.setTranscript(
                entries,
                renderMode,
                detectDarkMode(),
                shouldScrollToBottom,
                showJumpButton,
                readAloudAvailable,
                readAloudMessageIndexes,
                activeReadAloudMessageIndex
        );
        if (forceReload) {
            jcefBrowserView.reload(shouldScrollToBottom);
        }
    }

    private Set<Integer> readAloudMessageIndexes() {
        return assistantBubbles.stream()
                .filter(this::hasSpeakableReadAloudContent)
                .mapToInt(this::messageIndex)
                .filter(index -> index >= 0)
                .boxed()
                .collect(toUnmodifiableSet());
    }

    private int activeWebReadAloudMessageIndex(List<ConversationEntry> entries) {
        return entries.stream()
                .mapToInt(ConversationEntry::messageIndex)
                .filter(index -> index >= 0 && textToSpeechService.isReadAloudActive(webReadAloudKey(index)))
                .findFirst()
                .orElse(-1);
    }

    private StreamingSession typingIndicatorSession() {
        StreamingSession session = visibleStreamingSession();
        return session != null
                && isVisibleSession(session)
                && session.isLive()
                && !session.terminalCallbackStarted.get()
                && !session.visibleAssistantOutputRendered
                ? session
                : null;
    }

    private boolean hasVisibleAssistantContent(ChatMessageView messageView) {
        return messageView != null
                && (hasVisibleAssistantText(messageView.getFullText())
                || messageView.contentPartsSnapshot().stream().anyMatch(this::isVisibleAssistantPart));
    }

    private boolean hasAssistantPayload(ChatMessageView messageView) {
        return messageView != null
                && (hasVisibleAssistantText(messageView.getFullText())
                || messageView.contentPartsSnapshot().stream().anyMatch(part -> !(part instanceof TextPart)));
    }

    private boolean hasVisibleAssistantText(String text) {
        return StringUtils.isNotBlank(normalizeThinkingText(text));
    }

    private boolean isVisibleAssistantPart(ContentPart part) {
        return renderMode != RenderMode.MARKDOWN
                && part instanceof GeneratedImagePart generatedImagePart
                && StringUtils.isNotBlank(generatedImagePart.attachmentRef().storagePath());
    }

    private ConversationEntry toConversationEntry(
            Component component,
            int[] messageIndex,
            boolean omitProvisionalAssistant
    ) {
        ActivityBubble activityBubble = findActivityBubble(component);
        if (activityBubble != null) {
            if (!activityBubble.isVisible()) {
                return null;
            }
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
        int historyMessageIndex = findHistoryMessageIndex(component);
        if (omitProvisionalAssistant
                && (historyMessageIndex < 0 || historyMessageIndex >= history.size())
                && messageView.getRole() == Role.ASSISTANT
                && !hasVisibleAssistantContent(messageView)
        ) {
            return null;
        }
        int fallbackMessageIndex = messageIndex[0]++;
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
                ),
                nativeWebSearchOutcome.supported()
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

    private void addAssistantBubble(ChatMessageView bubble) {
        bubble.setRenderMode(renderMode);
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
        if (role == Role.ASSISTANT) {
            prepareReadAloudAvailability(bubble);
        }
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

        JMenuItem exportPdfItem = buildChatMenuItem(
                "Export to PDF…",
                "/icons/settings/book-open.svg",
                null,
                this::requestPdfExport
        );

        JMenuItem clearChatItem = buildChatMenuItem(
                "Clear Chat",
                "/icons/input/eraser.svg",
                null,
                this::requestClearChat
        );

        JPopupMenu popup = PopupMenuSupport.configureNativeSafePopup(new JPopupMenu());
        var readAloudSeparator = new JPopupMenu.Separator();
        popup.add(copyItem);
        popup.addSeparator();
        popup.add(selectMessageItem);
        popup.add(selectConversationItem);
        if (bubble.getRole() == Role.ASSISTANT) {
            popup.add(readAloudSeparator);
            popup.add(readAloudItem);
        }
        popup.addSeparator();
        popup.add(regenerateItem);
        popup.add(exportPdfItem);
        popup.add(clearChatItem);

        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                copyItem.setEnabled(bubble.hasContentSelection());
                selectMessageItem.setEnabled(!bubble.getFullText().isEmpty());
                selectConversationItem.setEnabled(hasAnyConversationText());
                readAloudSeparator.setVisible(updateReadAloudMenuItem(readAloudItem, bubble));
                regenerateItem.setEnabled(canRegenerateFrom(bubble));
                exportPdfItem.setEnabled(canExportPdf());
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

    private boolean updateReadAloudMenuItem(JMenuItem item, ChatMessageView bubble) {
        boolean active = textToSpeechService.isReadAloudActive(swingReadAloudKey(bubble));
        boolean available = canReadAloud(bubble, bubble.getRole());
        boolean visible = active || available;
        item.setText(active ? "Stop" : "Read aloud");
        item.setIcon(chatMenuIcon(active ? "/icons/chat/player-stop.svg" : "/icons/chat/volume-2.svg"));
        item.setVisible(visible);
        item.setEnabled(visible);
        return visible;
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
            if (removed || shutdownInProgress) {
                return;
            }
            if (Strings.CS.equals(action, WEBVIEW_POINTER_DOWN_ACTION)) {
                hideModelPopup();
                return;
            }
            if (Strings.CS.equalsAny(action, "copy-selected", "copy-text")) {
                copyTextToClipboard(text);
                return;
            }
            if (Strings.CS.equals(action, "export-pdf")) {
                requestPdfExport();
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
                return;
            }

            ChatMessageView bubble = webTranscriptBubble(messageIndex, bubbles);
            if (Strings.CS.equals(action, "read-aloud")) {
                if (bubble == null
                        || bubble.getRole() != Role.ASSISTANT
                        || messageIndex(bubble) != messageIndex
                ) {
                    return;
                }
                String sourceText = webTranscriptReadAloudText(messageIndex, bubble);
                if (StringUtils.isNotBlank(sourceText) && Strings.CS.equals(text, create(messageIndex, sourceText))) {
                    readWebTranscriptAloud(messageIndex, sourceText);
                }
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
                    .filter(bubble -> Strings.CS.equals(readAloudSourceText(bubble), message.content()))
                    .findFirst()
                    .orElse(null);
            if (matchingBubble != null) {
                return matchingBubble;
            }
        }
        return messageIndex >= 0 && messageIndex < bubbles.size() ? bubbles.get(messageIndex) : null;
    }

    private String webTranscriptReadAloudText(int messageIndex, ChatMessageView bubble) {
        return StringUtils.defaultIfBlank(storedAssistantMessageText(messageIndex), readAloudSourceText(bubble));
    }

    private String storedAssistantMessageText(int messageIndex) {
        return messageIndex >= 0 && messageIndex < history.size() && history.get(messageIndex).role() == Role.ASSISTANT
                ? history.get(messageIndex).content()
                : "";
    }

    private void readWebTranscriptAloud(int messageIndex, String text) {
        long uiGeneration = readAloudUiGeneration.incrementAndGet();
        if (speechToTextService.active()) {
            showReadAloudStatus(uiGeneration, "Finish or cancel transcription before using read aloud.");
            return;
        }
        textToSpeechService.readAloud(
                webReadAloudKey(messageIndex),
                text,
                message -> showReadAloudError(uiGeneration, message),
                message -> showReadAloudStatus(uiGeneration, message),
                () -> refreshReadAloudControls(uiGeneration, messageIndex)
        );
    }

    private String webReadAloudKey(int messageIndex) {
        return "web:%d".formatted(messageIndex);
    }

    private void refreshReadAloudControls(long uiGeneration) {
        refreshReadAloudControls(uiGeneration, -1);
    }

    private void refreshReadAloudControls(long uiGeneration, int webMessageIndex) {
        if (removed || uiGeneration != readAloudUiGeneration.get()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (removed || uiGeneration != readAloudUiGeneration.get()) {
                return;
            }
            refreshBubbleActionBars();
            if (webMessageIndex >= 0) {
                boolean active = textToSpeechService.isReadAloudActive(webReadAloudKey(webMessageIndex));
                if (isSystemWebViewEnabled()) {
                    systemWebView.updateReadAloudChrome(webMessageIndex, active);
                } else if (isJcefBrowserViewEnabled()) {
                    jcefBrowserView.updateReadAloudChrome(webMessageIndex, active);
                }
            }
            refreshWebTranscript(false);
        });
    }

    private void openDiagramHtml(String payload) {
        long expectedHistoryRevision = historyRevision;
        UUID expectedConversationId = activeConversationId;
        runOpenActionInBackground(
                expectedHistoryRevision,
                expectedConversationId,
                "chat4j-open-diagram",
                () -> openHtmlFile(DiagramHtmlExporter.exportMermaidHtml(payload)),
                this::diagramOpenError
        );
    }

    String diagramOpenError(Exception error) {
        String message = error.getMessage();
        return Strings.CS.equalsAny(
                message,
                "Diagram is too large.",
                "Unsupported diagram type.",
                "Diagram source is missing.",
                "Diagram source is too large.",
                "Opening diagrams is not supported on this system.",
                "Unable to open diagram."
        ) ? message : "Unable to open diagram.";
    }

    private void openHtmlFile(Path path) throws IOException {
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Opening diagrams is not supported on this system.");
        }

        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.OPEN)) {
            try {
                desktop.open(path.toFile());
                return;
            } catch (IOException e) {
                throw new IOException("Unable to open diagram.", e);
            }
        }
        if (desktop.isSupported(Desktop.Action.BROWSE)) {
            try {
                desktop.browse(path.toUri());
                return;
            } catch (IOException e) {
                throw new IOException("Unable to open diagram.", e);
            }
        }
        throw new IOException("Opening diagrams is not supported on this system.");
    }

    private void openConversationAttachment(String storagePath) {
        if (StringUtils.isBlank(storagePath) || !isKnownConversationAttachment(storagePath)) {
            showOpenActionError("Attachment file is not available on disk.");
            return;
        }

        long expectedHistoryRevision = historyRevision;
        UUID expectedConversationId = activeConversationId;
        runOpenActionInBackground(
                expectedHistoryRevision,
                expectedConversationId,
                "chat4j-open-attachment",
                () -> openAttachment(storagePath),
                e -> attachmentOpenError(storagePath, e)
        );
    }

    private void openAttachment(String storagePath) throws IOException {
        Path path = attachmentStager.managedPath(storagePath)
                .orElseThrow(() -> new IOException("Attachment file is not available on disk."));
        if (!Files.isRegularFile(path)) {
            throw new IOException("Attachment file is not available on disk.");
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            throw new IOException("Opening attachments is not supported on this system.");
        }
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException e) {
            throw new IOException("Unable to open attachment.", e);
        }
    }

    private String attachmentOpenError(String storagePath, Exception error) {
        if (Strings.CS.equalsAny(
                error.getMessage(),
                "Attachment file is not available on disk.",
                "Opening attachments is not supported on this system."
        )) {
            return error.getMessage();
        }
        try {
            return "Unable to open attachment: %s".formatted(Path.of(storagePath).getFileName());
        } catch (RuntimeException ignored) {
            return "Unable to open attachment.";
        }
    }

    void runOpenActionInBackground(
            long expectedHistoryRevision,
            UUID expectedConversationId,
            String threadName,
            OpenAction action,
            Function<Exception, String> errorMessage
    ) {
        long expectedOpenActionUiGeneration = openActionUiGeneration.get();
        Thread.ofVirtual().name(threadName).start(() -> {
            if (shutdownInProgress) {
                return;
            }
            try {
                action.run();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (isOpenActionUiCurrent(
                            expectedHistoryRevision,
                            expectedConversationId,
                            expectedOpenActionUiGeneration
                    )) {
                        showOpenActionError(errorMessage.apply(e));
                    }
                });
            }
        });
    }

    boolean isOpenActionUiCurrent(
            long expectedHistoryRevision,
            UUID expectedConversationId,
            long expectedOpenActionUiGeneration
    ) {
        return !removed
                && !shutdownInProgress
                && historyRevision == expectedHistoryRevision
                && Objects.equals(activeConversationId, expectedConversationId)
                && openActionUiGeneration.get() == expectedOpenActionUiGeneration;
    }

    private boolean isKnownConversationAttachment(String storagePath) {
        return history.stream()
                .flatMap(message -> message.parts().stream())
                .map(this::attachmentRef)
                .filter(Objects::nonNull)
                .map(AttachmentRef::storagePath)
                .anyMatch(path -> Strings.CS.equals(path, storagePath));
    }

    private void showOpenActionError(String message) {
        showMessageDialog(this, message, JOptionPane.WARNING_MESSAGE);
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

    public void reconcilePendingAssistantRecoveries(UUID conversationId, Set<UUID> pendingMessageIds) {
        if (conversationId == null) {
            return;
        }
        List<PendingAssistantRecovery> recoveries = pendingCompletedAssistantRecoveries.get(conversationId);
        if (recoveries == null) {
            return;
        }
        Set<PendingAssistantRecovery> settledRecoveries = recoveries.stream()
                .filter(recovery -> !pendingMessageIds.contains(recovery.entry().messageId()))
                .collect(toCollection(HashSet::new));
        removePendingAssistantRecoveries(conversationId, settledRecoveries);
    }

    public void applyCanonicalClear(UUID conversationId) {
        if (Objects.equals(activeConversationId, conversationId)) {
            clearChat();
            return;
        }
        clearPendingAssistantRecovery(conversationId);
        FailedUserSend failed = failedUserSends.remove(conversationId);
        if (failed != null) {
            discardFailedUserSend(failed);
        }
    }

    public void clearChatView() {
        resetConversationRuntimeState();
        clearChat(false);
    }

    public void resetConversationRuntimeState() {
        stagedRuntimeLoad = null;
        pendingWebSearchOptOut = null;
        requestedWebSearch = false;
        inputBar.setAgentModeEnabled(false);
        inputBar.setAgentProjectRoot(null);
        applyWebSearchPresentation();
    }

    public void cancelConversationsForDeletion(Collection<UUID> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> cancelConversationsForDeletion(conversationIds));
            return;
        }

        Set<UUID> ids = conversationIds.stream()
                .filter(Objects::nonNull)
                .collect(toCollection(HashSet::new));
        blockedConversationIds.addAll(ids);
        activeSessions.values().stream()
                .filter(session -> ids.contains(session.conversationId))
                .toList()
                .forEach(this::discardStreamingSession);
        activeSendJobs.values().stream()
                .filter(sendJob -> ids.contains(sendJob.conversationId))
                .toList()
                .forEach(this::discardSendJob);
        if (ids.contains(activeConversationId)) {
            discardVisibleStreamingComponents();
        }
        updateGenerationIndicator();
        refreshComposerAvailability();
    }

    private void discardVisibleStreamingComponents() {
        if (currentAssistantBubble != null) {
            removeMessageComponentFromPanel(currentAssistantBubble.component());
        }
        if (currentAssistantWebSearchBubble != null) {
            removeMessageComponentFromPanel(currentAssistantWebSearchBubble);
        }
        if (currentAssistantActivityBubble != null) {
            removeMessageComponentFromPanel(currentAssistantActivityBubble);
        }
        currentAssistantAgentToolBubbles.values().forEach(this::removeMessageComponentFromPanel);
        currentAssistantBubble = null;
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        refreshWebTranscript(false, true);
    }

    public void finishConversationDeletion(Collection<UUID> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> finishConversationDeletion(conversationIds));
            return;
        }
        blockedConversationIds.removeAll(conversationIds);
        refreshComposerAvailability();
    }

    public void resolveIndeterminateUserMessage(UUID conversationId, boolean committed) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> resolveIndeterminateUserMessage(conversationId, committed));
            return;
        }
        setConversationPersistenceBlocked(conversationId, false);
        FailedUserSend failed = failedUserSends.get(conversationId);
        if (!committed) {
            if (failed != null && isVisibleConversation(conversationId)) {
                visibleFailedRecovery = failed;
                inputBar.showValidationMessage("The message was not saved. Send again to retry.");
                inputBar.requestInputFocus();
                acknowledgeFailedUserSend(failed);
            }
            return;
        }
        if (failed == null || failed.sendJob().preparedUserMessage == null) {
            return;
        }

        SendJob continuation = SendJob.admittedContinuation(sendJobCounter.incrementAndGet(), failed.sendJob());
        continuation.persistenceAlreadyCanonical = true;
        activeSendJobs.put(continuation.jobId, continuation);
        beginPreparing(continuation);
        boolean credentialsChanged = credentialChangesPending.containsKey(continuation.runtime.providerName())
                || credentialChangeVersion(continuation.runtime.providerName()) != continuation.admittedCredentialVersion;
        if (continuation.providerContinuationCancelled || credentialsChanged) {
            continuation.providerContinuationCancelled = true;
            completePreparedSend(
                    continuation,
                    continuation.preparedUserMessage,
                    conversationId,
                    isVisibleConversation(conversationId)
            );
            if (credentialsChanged && isVisibleConversation(conversationId)) {
                inputBar.showValidationMessage(
                        "Provider credentials changed while the message was being saved. Regenerate the response after the update finishes."
                );
            }
            return;
        }
        continuation.worker = Thread.startVirtualThread(() -> {
            try {
                admitProvider(continuation);
                SwingUtilities.invokeLater(() -> {
                    if (shutdownInProgress || !isPreparing(continuation)) {
                        abandonSendJob(continuation);
                        return;
                    }
                    completePreparedSend(
                            continuation,
                            continuation.preparedUserMessage,
                            conversationId,
                            isVisibleConversation(conversationId)
                    );
                });
            } catch (Exception | LinkageError e) {
                SwingUtilities.invokeLater(() -> {
                    if (shutdownInProgress || !isPreparing(continuation)) {
                        abandonSendJob(continuation);
                        return;
                    }
                    String safeMessage = ProviderExceptionMapper.sanitizeMessage(e, continuation.apiKey);
                    continuation.providerContinuationCancelled = true;
                    completePreparedSend(
                            continuation,
                            continuation.preparedUserMessage,
                            conversationId,
                            isVisibleConversation(conversationId)
                    );
                    if (isVisibleConversation(conversationId)) {
                        inputBar.showValidationMessage(
                                "The message was saved, but the response could not start: %s"
                                        .formatted(StringUtils.defaultIfBlank(safeMessage, "Unknown error"))
                        );
                    }
                });
            }
        });
    }

    public void setConversationPersistenceBlocked(UUID conversationId, boolean blocked) {
        if (conversationId == null) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setConversationPersistenceBlocked(conversationId, blocked));
            return;
        }
        if (blocked) {
            blockedConversationIds.add(conversationId);
        } else {
            blockedConversationIds.remove(conversationId);
        }
        refreshComposerAvailability();
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

        blockedConversationIds.removeAll(ids);
        discardedConversationIds.addAll(ids);
        ids.forEach(this::clearPendingAssistantRecovery);
        failedUserSends.values().stream()
                .filter(failed -> ids.contains(failed.sendJob().conversationId))
                .toList()
                .forEach(this::discardFailedUserSend);
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

    public CompletableFuture<Void> cancelAllRequestsAsync() {
        List<SendJob> sendJobs = activeSendJobs.values().stream().toList();
        List<StreamingSession> sessions = activeSessions.values().stream().toList();
        List<Thread> workers = Stream.of(
                        sendJobs.stream().map(sendJob -> sendJob.worker),
                        sessions.stream().map(session -> session.worker),
                        shutdownPreparationWorkers.stream()
                )
                .flatMap(Function.identity())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        sessions.forEach(session -> session.cancelled.set(true));
        List<AutoCloseable> activeRequests = sessions.stream()
                .map(StreamingSession::detachActiveRequest)
                .filter(Objects::nonNull)
                .toList();
        cancelStreaming();
        sendJobs.forEach(this::discardSendJob);
        sessions.forEach(this::discardStreamingSession);
        updateGenerationIndicator();
        CompletableFuture<?>[] cleanupTasks = Stream.of(
                        activeRequests.stream().map(this::closeActiveRequestAsync),
                        workers.stream().map(this::awaitWorkerAsync),
                        sendJobs.stream().map(sendJob -> sendJob.durableUserMessageSettlement)
                )
                .flatMap(Function.identity())
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(cleanupTasks)
                .handle((ignored, cleanupFailure) -> cleanupFailure)
                .thenCompose(cleanupFailure -> awaitAttachmentDiscardTasks().handle((ignored, attachmentFailure) -> {
                    Throwable failure = cleanupFailure == null ? attachmentFailure : cleanupFailure;
                    if (cleanupFailure != null && attachmentFailure != null) {
                        unwrapCompletion(cleanupFailure).addSuppressed(unwrapCompletion(attachmentFailure));
                    }
                    if (failure != null) {
                        throw new CompletionException(unwrapCompletion(failure));
                    }
                    return null;
                }))
                .thenRun(() -> {
                    shutdownPreparationWorkers.removeAll(workers);
                    sessions.stream()
                            .map(StreamingSession::requestCloseFailure)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .ifPresent(failure -> {
                                throw new CompletionException("Provider request cancellation failed", failure);
                            });
                });
    }

    private CompletableFuture<Void> awaitAttachmentDiscardTasks() {
        attachmentDiscardTasks.removeIf(CompletableFuture::isDone);
        CompletableFuture<?>[] tasks = attachmentDiscardTasks.toArray(CompletableFuture[]::new);
        if (tasks.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(tasks).thenCompose(ignored -> awaitAttachmentDiscardTasks());
    }

    private CompletableFuture<Void> closeActiveRequestAsync(AutoCloseable request) {
        try {
            return CompletableFuture.runAsync(
                    () -> closeActiveRequest(request),
                    command -> Thread.ofVirtual().name("chat4j-active-request-close").start(command)
            );
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private CompletableFuture<Void> awaitWorkerAsync(Thread worker) {
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException("Interrupted while waiting for chat worker shutdown", e);
                }
            }, command -> Thread.ofVirtual().name("chat4j-worker-shutdown").start(command));
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private void closeActiveRequest(AutoCloseable request) {
        try {
            request.close();
        } catch (Exception e) {
            throw new CompletionException("Provider request cancellation failed", e);
        }
    }

    private void discardSendJob(SendJob sendJob) {
        if (sendJob == null) {
            return;
        }
        sendJob.cancelled.set(true);
        if (!sendJob.durableUserMessageSubmissionStarted
                && !sendJob.durableHistoryMutationSubmissionStarted
        ) {
            discardStagedAttachments(sendJob.preparedUserMessage);
        }
        sendJob.finished = true;
        activeSendJobs.remove(sendJob.jobId);
        Thread worker = sendJob.worker;
        if (worker != null) {
            worker.interrupt();
        }
        sendJob.clearCredentialReferences();
    }

    private void discardStreamingSession(StreamingSession session) {
        if (session == null) {
            return;
        }
        boolean conversationStopped;
        try {
            synchronized (terminalPersistenceLock) {
                session.cancelled.set(true);
                discardStreamingResponseAttachments(session);
                session.finished = true;
                activeSessions.remove(session.sessionId);
                conversationStopped = !hasLiveStreamingSession(session.conversationId);
            }
            cancelSessionActiveRequest(session, false);
            Thread worker = session.worker;
            if (worker != null) {
                worker.interrupt();
            }
            if (conversationStopped) {
                notifyConversationStreamingChanged(session.conversationId, false);
            }
        } finally {
            session.clearProvider();
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
        discardFailedUserSendForCurrentView();

        stopReadAloudPlayback();
        disposeMessageViews();
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        currentAssistantBubble = null;
        history.clear();
        refreshModelSelectorConversationState();
        userHistoryEntries.clear();
        nextMessageOrdinal = 1;
        historyRevision++;
        assistantBubbles.clear();
        clearReadAloudAvailability();
        thinkingBubbles.clear();
        messagesPanel.removeAll();
        messageRow = 0;
        messagesPanel.revalidate();
        messagesPanel.repaint();
        refreshWebTranscript(false, true);
        messagesCardLayout.show(messagesContainer, CARD_EMPTY);
        updateClearChatButtonVisibility();
    }

    private void discardFailedUserSendForCurrentView() {
        FailedUserSend failed = failedUserSendForCurrentView();
        if (failed == null) {
            return;
        }
        if (Objects.equals(inputBar.getComposerState(), failed.sendJob().composerState)) {
            inputBar.clear();
        }
        discardFailedUserSend(failed);
    }

    private void discardFailedUserSend(FailedUserSend failed) {
        failedUserSends.remove(failed.sendJob().conversationId, failed);
        if (visibleFailedRecovery == failed) {
            visibleFailedRecovery = null;
        }
        discardStagedAttachments(failed.sendJob().preparedUserMessage);
        if (failed.sendJob().createsConversation
                && Objects.equals(activeConversationId, failed.sendJob().conversationId)
        ) {
            activeConversationId = null;
        }
        acknowledgeFailedUserSend(failed);
    }

    private void acknowledgeFailedUserSend(FailedUserSend failed) {
        if (failed.recoveryAcknowledged || durableUserMessageFailureDeliveredListener == null) {
            return;
        }
        failed.recoveryAcknowledged = true;
        durableUserMessageFailureDeliveredListener.accept(
                failed.sendJob().conversationId,
                failed.sendJob().userMessageId
        );
    }

    private void clearChatForHistoryLoad() {
        stopReadAloudPlayback();
        disposeMessageViews();
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        currentAssistantBubble = null;
        history.clear();
        userHistoryEntries.clear();
        nextMessageOrdinal = 1;
        assistantBubbles.clear();
        clearReadAloudAvailability();
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
        refreshModelSelectorConversationState();
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

    List<Message> getHistory() {
        return new ArrayList<>(history);
    }

    boolean isStreaming() {
        return streaming;
    }

    public boolean hasFailedProvisionalUserSend() {
        FailedUserSend failed = failedUserSendForCurrentView();
        return failed != null && failed.sendJob().createsConversation;
    }

    public void discardFailedProvisionalUserSend() {
        FailedUserSend failed = failedUserSendForCurrentView();
        if (failed != null && failed.sendJob().createsConversation) {
            discardFailedUserSend(failed);
        }
    }

    public boolean hasPendingConversationMutation() {
        SendJob preparingJob = visiblePreparingJob();
        return conversationMutationPending
                || preparingJob != null && (preparingJob.durableUserMessageSubmissionStarted
                        || preparingJob.durableHistoryMutationSubmissionStarted);
    }

    public void abandonVisibleUnsubmittedPreparation() {
        SendJob preparingJob = visiblePreparingJob();
        if (preparingJob == null
                || preparingJob.durableUserMessageSubmissionStarted
                || preparingJob.durableHistoryMutationSubmissionStarted
                || preparingJob.persistenceAlreadyCanonical
        ) {
            return;
        }
        discardSendJob(preparingJob);
        releaseFailedProvisionalConversation(preparingJob);
        applyVisibleConversationInputState();
    }

    public void setConversationMutationPending(boolean pending) {
        conversationMutationPending = pending;
        refreshComposerAvailability();
        refreshBubbleActionBars();
    }

    public boolean isEditingUserMessage() {
        return editingUserMessage != null;
    }

    public boolean isDurableUserMessageContinuationCurrent(UUID conversationId) {
        return activeSendJobs.values().stream()
                .anyMatch(job -> Objects.equals(job.conversationId, conversationId) && isPreparing(job));
    }

    public void loadConversationHistoryEntries(
            UUID conversationId,
            List<ConversationRepository.MessageRecord> records
    ) {
        if (speechToTextService.active()) {
            throw new IllegalStateException("Cannot switch conversations while Speech to Text is active.");
        }
        setActiveConversationId(conversationId);
        List<PendingAssistantRecovery> recoveries = pendingCompletedAssistantRecoveries.get(conversationId);
        Map<UUID, PendingAssistantRecovery> recoveriesById = recoveries == null
                ? emptyMap()
                : recoveries.stream().collect(toMap(
                        recovery -> recovery.entry().messageId(),
                        Function.identity()
                ));
        Set<PendingAssistantRecovery> canonicalRecoveries = records.stream()
                .map(record -> {
                    PendingAssistantRecovery recovery = recoveriesById.get(record.id());
                    return recovery != null && recovery.entry().equals(record.historyEntry()) ? recovery : null;
                })
                .filter(Objects::nonNull)
                .collect(toCollection(HashSet::new));
        List<ConversationHistoryEntry> missingRecoveries = recoveries == null
                ? emptyList()
                : recoveries.stream()
                        .filter(recovery -> !canonicalRecoveries.contains(recovery))
                        .map(PendingAssistantRecovery::entry)
                        .sorted(Comparator.comparingInt(ConversationHistoryEntry::ordinal))
                        .toList();
        removePendingAssistantRecoveries(conversationId, canonicalRecoveries);
        List<ConversationRepository.MessageRecord> displayedRecords = records.stream()
                .filter(record -> {
                    PendingAssistantRecovery recovery = recoveriesById.get(record.id());
                    return recovery == null || recovery.entry().equals(record.historyEntry());
                })
                .toList();
        List<Message> messages = new ArrayList<>(displayedRecords.stream()
                .map(ConversationRepository.MessageRecord::message)
                .toList());
        missingRecoveries.stream()
                .map(ConversationHistoryEntry::message)
                .forEach(messages::add);
        loadHistory(messages);
        bindLoadedHistoryEntries(displayedRecords);
        int highestRecoveryOrdinal = missingRecoveries.stream()
                .mapToInt(ConversationHistoryEntry::ordinal)
                .max()
                .orElse(0);
        nextMessageOrdinal = Math.max(nextMessageOrdinal, highestRecoveryOrdinal + 1);
        if (!missingRecoveries.isEmpty()) {
            inputBar.showValidationMessage("An assistant response is visible but has not been saved yet.");
        }
    }

    private void bindLoadedHistoryEntries(List<ConversationRepository.MessageRecord> records) {
        userHistoryEntries.clear();
        nextMessageOrdinal = records.isEmpty() ? 1 : records.getLast().ordinal() + 1;
        int recordIndex = 0;
        int historyIndex = 0;
        while (recordIndex < records.size()) {
            ConversationRepository.MessageRecord record = records.get(recordIndex);
            if (record.message().role() != Role.ASSISTANT) {
                if (record.message().role() == Role.USER) {
                    userHistoryEntries.put(historyIndex, record.historyEntry());
                }
                recordIndex++;
                historyIndex++;
                continue;
            }
            while (recordIndex < records.size()
                    && records.get(recordIndex).message().role() == Role.ASSISTANT
            ) {
                recordIndex++;
            }
            historyIndex++;
        }
    }

    void loadHistory(List<Message> messages) {
        if (speechToTextService.active()) {
            inputBar.showValidationMessage("Finish or cancel transcription before loading history.");
            return;
        }
        clearChatForHistoryLoad();
        historyRevision++;

        batchMessageRefresh = true;
        try {
            List<Message> normalizedMessages = new ArrayList<>(normalizeLoadedHistory(messages));
            for (Message msg : normalizedMessages) {
                history.add(msg);
                int messageIndex = history.size() - 1;
                if (msg.role() == Role.USER) {
                    userHistoryEntries.put(
                            messageIndex,
                            new ConversationHistoryEntry(UUID.randomUUID(), messageIndex + 1, msg)
                    );
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

                    if (!hasVisibleAssistantMessageContent(msg)) {
                        continue;
                    }
                }

                addBubble(createMessageView(msg.role()), msg, msg.role(), messageIndex);
            }
        } finally {
            batchMessageRefresh = false;
        }

        nextMessageOrdinal = history.size() + 1;
        attachVisibleStreamingSession(visibleStreamingSession());
        finishHistoryLoadRefresh();
    }

    public String getSelectedModel() {
        if (selectedProviderName == null || selectedModelId == null) {
            return null;
        }

        return ModelSelectionCodec.format(selectedProviderName, selectedModelId);
    }

    public boolean hasConversationMessages() {
        return !history.isEmpty();
    }

    public String resolveUserSelectableModel(String modelKey) {
        return ModelSelectionCodec.parse(modelKey)
                .filter(selection -> {
                    ProviderRegistry.ProviderDef providerDef = providerMap.get(selection.provider());
                    return providerDef != null
                            && providerModelsResolver.resolve(providerDef).contains(selection.model());
                })
                .map(selection -> ModelSelectionCodec.format(selection.provider(), selection.model()))
                .orElse(null);
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
        String safeModelId = TogetherModelSupport.isTogether(providerName) ? StringUtils.trim(modelId) : modelId;
        if (TogetherModelSupport.isTogether(providerName)
                && !TogetherModelSupport.isServerlessChatModel(safeModelId)) {
            return null;
        }
        ProviderRegistry.ProviderDef providerDef = providerMap.get(providerName);
        if (providerDef == null) {
            boolean knownProvider = providerRegistry.allProviders().stream()
                    .map(ProviderRegistry.ProviderDef::name)
                    .anyMatch(providerName::equals);
            return knownProvider ? safeModelId : null;
        }
        if (!modelCacheService.isInvalidated(providerName)) {
            return safeModelId;
        }
        List<String> seedModels = sanitizeModelIds(providerName, providerDef.seedModels());
        if (seedModels.contains(safeModelId)) {
            return safeModelId;
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
        if (shutdownInProgress) {
            return;
        }
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
            StreamingSession session = visibleStreamingSession();
            if (session != null && hasVisibleAssistantContent(currentAssistantBubble)) {
                session.visibleAssistantOutputRendered = true;
            }
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

    public void setOnModelSelectionRequested(@NonNull Consumer<String> listener) {
        this.modelSelectionRequestedListener = listener;
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

    public void setOnClearChatRequested(Runnable listener) {
        this.clearChatRequestedListener = listener;
    }

    public void setOnExportPdfRequested(@NonNull Runnable listener) {
        this.exportPdfRequestedListener = listener;
    }

    public void setPdfExportRunning(boolean running) {
        this.pdfExportRunning = running;
        refreshComposerAvailability();
        updateClearChatButtonVisibility();
        refreshBubbleActionBars();
    }

    public boolean canExportPdf() {
        return activeConversationId != null
                && Objects.equals(activeConversationId, persistedConversationId)
                && !hasPendingAssistantRecovery(activeConversationId)
                && !isVisibleConversationBusy()
                && !removed
                && !shutdownInProgress;
    }

    public Optional<ConversationPdfExporter> activeEnhancedPdfExporter() {
        return canExportPdf() && jcefBrowserView != null && jcefBrowserView.canAttemptPdfExport()
                ? Optional.of(new JcefConversationPdfExporter(jcefBrowserView))
                : Optional.empty();
    }

    private void requestPdfExport() {
        if (!canExportPdf()) {
            inputBar.showValidationMessage("Wait for the current response or export to finish.");
            return;
        }
        if (exportPdfRequestedListener != null) {
            exportPdfRequestedListener.run();
        }
    }

    private void updatePdfExportAvailability() {
        boolean available = canExportPdf();
        if (systemWebView != null) {
            systemWebView.setPdfExportAvailable(available);
        }
        if (jcefBrowserView != null) {
            jcefBrowserView.setPdfExportAvailable(available);
        }
    }

    public void setOnDurableUserMessageSubmitted(DurableUserMessagePersistenceListener listener) {
        this.durableUserMessageSubmittedListener = listener;
    }

    public void setOnDurableUserMessageFailureDelivered(BiConsumer<UUID, UUID> listener) {
        this.durableUserMessageFailureDeliveredListener = listener;
    }

    public void setOnDurableAssistantMessageCompleted(DurableAssistantMessagePersistenceListener listener) {
        this.durableAssistantMessageCompletedListener = listener;
    }

    public void setOnDurableHistoryMutation(DurableHistoryMutationListener listener) {
        this.durableHistoryMutationListener = listener;
    }

    public void setOnDurableHistoryMutationFailureDelivered(Consumer<HistoryMutationEvent> listener) {
        this.durableHistoryMutationFailureDeliveredListener = listener;
    }

    public void beginShutdown() {
        synchronized (terminalPersistenceLock) {
            shutdownInProgress = true;
            stagedRuntimeLoad = null;
        }
        activeSendJobs.values().stream()
                .filter(sendJob -> !sendJob.durableUserMessageSubmissionStarted)
                .filter(sendJob -> !sendJob.durableHistoryMutationSubmissionStarted)
                .toList()
                .forEach(sendJob -> {
                    if (sendJob.worker != null) {
                        shutdownPreparationWorkers.add(sendJob.worker);
                    }
                    discardSendJob(sendJob);
                });
        activeSessions.values().forEach(session -> {
            session.cancelled.set(true);
            discardStreamingResponseAttachments(session);
        });
        conversationMutationPending = false;
        providerRefreshCounter.incrementAndGet();
        providerSelectionCounter.incrementAndGet();
        readAloudUiGeneration.incrementAndGet();
        speechToTextUiGeneration.incrementAndGet();
        openActionUiGeneration.incrementAndGet();
        cancelCapabilityRefresh();
        modelCacheService.cancelScopeVersion(providerScopeVersion);
        failedUserSends.values().stream()
                .filter(failed -> failed.recoveryAcknowledged)
                .toList()
                .forEach(this::discardFailedUserSend);
        refreshComposerAvailability();
        inputBar.beginShutdown();
        inputBar.setEnabled(false);
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
        this.agentOrchestrator = agentOrchestrator == null ? configuredAgentOrchestrator : agentOrchestrator;
    }

    public void setAgentSystemPromptAppend(String agentSystemPromptAppend) {
        this.agentSystemPromptAppend = StringUtils.defaultString(agentSystemPromptAppend);
    }

    public void setActiveConversationId(UUID conversationId) {
        persistedConversationId = conversationId;
        boolean conversationChanged = !Objects.equals(this.activeConversationId, conversationId);
        if (conversationChanged) {
            clearVisibleFailedDraft();
            visibleFailedRecovery = null;
        }
        this.activeConversationId = conversationId;
        if (conversationChanged) {
            clearVisibleStreamReferences();
            restoreVisibleFailedDraftIfComposerEmpty();
            deliverPendingHistoryFailure();
        }

        applyVisibleConversationInputState();
    }

    private void clearVisibleFailedDraft() {
        FailedUserSend failed = failedUserSendForCurrentView();
        if (failed != null && Objects.equals(inputBar.getComposerState(), failed.sendJob().composerState)) {
            inputBar.clear();
        }
    }

    private void restoreVisibleFailedDraftIfComposerEmpty() {
        FailedUserSend failed = failedUserSendForCurrentView();
        if (failed == null) {
            return;
        }
        ComposerState composerState = inputBar.getComposerState();
        if (!composerState.isEmpty() && !Objects.equals(composerState, failed.sendJob().composerState)) {
            return;
        }
        if (composerState.isEmpty()) {
            inputBar.setComposerState(failed.sendJob().composerState);
        }
        visibleFailedRecovery = failed;
        acknowledgeFailedUserSend(failed);
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
        boolean hasAssistantOutput = hasVisibleAssistantText(assistantText)
                || assistantParts.stream().anyMatch(part -> !(part instanceof TextPart));
        if (hasAssistantOutput) {
            if (currentAssistantBubble == null) {
                currentAssistantBubble = createMessageView(Role.ASSISTANT);
                addBubble(currentAssistantBubble, new Message(Role.ASSISTANT, assistantParts, Instant.now()), Role.ASSISTANT, history.size());
            } else {
                currentAssistantBubble.setContentParts(assistantParts);
            }
            if (hasVisibleAssistantText(assistantText) || assistantParts.stream().anyMatch(this::isVisibleAssistantPart)) {
                session.visibleAssistantOutputRendered = true;
            }
            attachedContent = true;
        }

        if (attachedContent) {
            refreshWebTranscript(false);
        }
    }

    private void queuePendingAssistantRecovery(UUID conversationId, ConversationHistoryEntry entry) {
        queuePendingAssistantRecovery(conversationId, entry, false);
    }

    private void queueFailedAssistantRecovery(UUID conversationId, ConversationHistoryEntry entry) {
        queuePendingAssistantRecovery(conversationId, entry, true);
    }

    private void queuePendingAssistantRecovery(
            UUID conversationId,
            ConversationHistoryEntry entry,
            boolean persistenceFailed
    ) {
        if (conversationId == null) {
            return;
        }
        pendingCompletedAssistantRecoveries.compute(conversationId, (ignored, existing) -> {
            List<PendingAssistantRecovery> entries = new ArrayList<>(existing == null ? emptyList() : existing);
            entries.removeIf(candidate -> candidate.entry().messageId().equals(entry.messageId()));
            entries.add(new PendingAssistantRecovery(entry, persistenceFailed));
            return List.copyOf(entries);
        });
        updatePdfExportAvailabilityFor(conversationId);
    }

    private void removePendingAssistantRecovery(UUID conversationId, UUID messageId) {
        if (conversationId == null) {
            return;
        }
        pendingCompletedAssistantRecoveries.computeIfPresent(conversationId, (ignored, entries) -> {
            List<PendingAssistantRecovery> retainedEntries = entries.stream()
                    .filter(recovery -> !recovery.entry().messageId().equals(messageId))
                    .toList();
            return retainedEntries.isEmpty() ? null : retainedEntries;
        });
        updatePdfExportAvailabilityFor(conversationId);
    }

    private void removePendingAssistantRecoveries(
            UUID conversationId,
            Set<PendingAssistantRecovery> settledRecoveries
    ) {
        if (conversationId == null || settledRecoveries.isEmpty()) {
            return;
        }
        pendingCompletedAssistantRecoveries.computeIfPresent(conversationId, (ignored, current) -> {
            List<PendingAssistantRecovery> remaining = current.stream()
                    .filter(recovery -> !settledRecoveries.contains(recovery))
                    .toList();
            return remaining.isEmpty() ? null : remaining;
        });
        updatePdfExportAvailabilityFor(conversationId);
    }

    private boolean hasPendingAssistantRecovery(UUID conversationId) {
        List<PendingAssistantRecovery> recoveries = pendingCompletedAssistantRecoveries.get(conversationId);
        return recoveries != null && !recoveries.isEmpty();
    }

    private boolean hasFailedAssistantRecovery(UUID conversationId) {
        if (conversationId == null) {
            return false;
        }
        List<PendingAssistantRecovery> recoveries = pendingCompletedAssistantRecoveries.get(conversationId);
        return recoveries != null && recoveries.stream().anyMatch(PendingAssistantRecovery::persistenceFailed);
    }

    private void clearPendingAssistantRecovery(UUID conversationId) {
        if (conversationId == null) {
            return;
        }
        List<PendingAssistantRecovery> discarded = pendingCompletedAssistantRecoveries.remove(conversationId);
        if (discarded != null) {
            discarded.stream()
                    .map(PendingAssistantRecovery::entry)
                    .map(ConversationHistoryEntry::message)
                    .forEach(this::discardStagedAttachments);
        }
        updatePdfExportAvailabilityFor(conversationId);
    }

    private void updatePdfExportAvailabilityFor(UUID conversationId) {
        if (Objects.equals(activeConversationId, conversationId)) {
            runOnEdt(this::updatePdfExportAvailability);
        }
    }

    private void notifyModelFavoritesChanged() {
        if (modelFavoritesChangedListener != null) {
            modelFavoritesChangedListener.run();
        }
    }

    private void notifyModelCatalogChanged() {
        if (installedProviderScope >= 0L
                && Strings.CS.equals(selectedProviderName, COPILOT_PROVIDER_NAME)
                && providerMap.containsKey(selectedProviderName)) {
            updateCapabilityAvailability(providerSelectionCounter.incrementAndGet());
        }
        if (modelCatalogChangedListener != null) {
            modelCatalogChangedListener.run();
        }
    }

    public void requestClearChat() {
        if (shutdownInProgress) {
            return;
        }
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
        return !shutdownInProgress
                && !history.isEmpty()
                && !isVisibleConversationBusy()
                && inputBar.isEnabled()
                && !speechToTextService.active();
    }

    private void refreshModelSelectorConversationState() {
        modelSelectorBtn.setConversationHasMessages(!history.isEmpty());
    }

    private void updateClearChatButtonVisibility() {
        inputBar.setClearChatVisible(canClearChat());
    }

    private void notifyConversationStreamingChanged(UUID conversationId, boolean streaming) {
        if (conversationStreamingListener == null || conversationId == null) {
            return;
        }
        try {
            conversationStreamingListener.accept(new ConversationStreamingEvent(conversationId, streaming));
        } catch (RuntimeException e) {
            log.warn("Conversation streaming listener failed: {}", ExceptionUtils.getMessage(e));
        }
    }

    private void setVisibleStreaming(boolean streaming) {
        if (this.streaming == streaming) {
            return;
        }
        this.streaming = streaming;
        updatePdfExportAvailability();
        if (visibleStreamingChangedListener == null) {
            return;
        }
        try {
            visibleStreamingChangedListener.accept(streaming);
        } catch (RuntimeException e) {
            log.warn("Visible streaming listener failed: {}", ExceptionUtils.getMessage(e));
        }
    }

    public void beginConversationRuntimeLoad(
            UUID conversationId,
            long loadRequestId,
            boolean webSearchEnabled,
            Path agentProjectRoot,
            boolean agentModeEnabled,
            boolean agentCorrectionRequired
    ) {
        pendingWebSearchOptOut = null;
        stagedRuntimeLoad = new StagedRuntimeLoad(
                conversationId,
                loadRequestId,
                webSearchEnabled,
                agentProjectRoot,
                agentModeEnabled,
                agentCorrectionRequired
        );
    }

    public void commitConversationRuntimeLoad(UUID conversationId, long loadRequestId) {
        StagedRuntimeLoad staged = stagedRuntimeLoad;
        if (staged == null
                || staged.loadRequestId() != loadRequestId
                || !Objects.equals(staged.conversationId(), conversationId)) {
            return;
        }
        persistedConversationId = conversationId;
        requestedWebSearch = staged.webSearchEnabled();
        inputBar.setAgentProjectRoot(staged.agentProjectRoot());
        inputBar.setAgentModeEnabled(staged.agentModeEnabled());
        stagedRuntimeLoad = null;
        if (staged.agentCorrectionRequired()) {
            emitAgentSettingsEvent();
        }

        boolean conflictingAgentRequest = inputBar.isAgentModeRequested()
                && (nativeWebSearchOutcome.required()
                || nativeWebSearchOutcome.optional() && requestedWebSearch);
        if (conflictingAgentRequest) {
            boolean agentWasEffectivelyEnabled = inputBar.isAgentModeEnabled();
            inputBar.setAgentModeEnabled(false);
            if (!agentWasEffectivelyEnabled) {
                emitAgentSettingsEvent();
            }
        }
        applyNativeWebSearchOutcome(nativeWebSearchOutcome);
    }

    public void cancelConversationRuntimeLoad(long loadRequestId) {
        if (stagedRuntimeLoad != null && stagedRuntimeLoad.loadRequestId() == loadRequestId) {
            stagedRuntimeLoad = null;
            applyNativeWebSearchOutcome(nativeWebSearchOutcome);
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

    private boolean canAcceptStreamingCallback(StreamingSession session) {
        return session != null
                && !shutdownInProgress
                && session.isLive()
                && !session.terminalCallbackStarted.get();
    }

    private void handleAssistantToken(StreamingSession session, SendJob sendJob, String token) {
        synchronized (terminalPersistenceLock) {
            if (!canAcceptStreamingCallback(session)) {
                return;
            }
            ThinkTagSplit split = session.thinkTagParser.accept(token);
            appendAssistantVisibleToken(session, split.visibleText());
            handleAssistantThinkingToken(session, sendJob, split.thinkingText(), true);
        }
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
                addAssistantBubble(currentAssistantBubble);
            }
            currentAssistantBubble.appendText(token);
            if (hasVisibleAssistantText(token)) {
                session.visibleAssistantOutputRendered = true;
            }
            refreshWebTranscript(true);
            scrollToBottom();
        });
    }

    private void handleAssistantPart(StreamingSession session, ContentPart part) {
        if (part == null || part instanceof TextPart) {
            return;
        }
        boolean rejected;
        synchronized (terminalPersistenceLock) {
            rejected = !canAcceptStreamingCallback(session);
            if (!rejected) {
                synchronized (session.responseParts) {
                    session.responseParts.add(part);
                }
            }
        }
        if (rejected) {
            AttachmentRef discardedAttachment = attachmentRef(part);
            if (discardedAttachment != null) {
                discardAttachmentRefs(List.of(discardedAttachment));
            }
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!session.isLive() || !isVisibleSession(session)) {
                return;
            }
            if (currentAssistantBubble == null) {
                currentAssistantBubble = createMessageView(Role.ASSISTANT);
                addAssistantBubble(currentAssistantBubble);
            }
            currentAssistantBubble.appendPart(part);
            if (isVisibleAssistantPart(part)) {
                session.visibleAssistantOutputRendered = true;
            }
            refreshWebTranscript(true);
            scrollToBottom();
        });
    }

    private void handleAssistantCitation(StreamingSession session, CitationRef citation) {
        synchronized (terminalPersistenceLock) {
            if (!canAcceptStreamingCallback(session) || citation == null) {
                return;
            }
            synchronized (session.responseCitations) {
                if (session.responseCitations.stream().anyMatch(existing -> existing.number() == citation.number())) {
                    return;
                }
                session.responseCitations.add(citation);
            }
        }
        List<CitationRef> citations = snapshotCitations(session);
        SwingUtilities.invokeLater(() -> {
            if (!session.isLive() || !isVisibleSession(session) || currentAssistantBubble == null) {
                return;
            }
            currentAssistantBubble.component().putClientProperty(
                    MESSAGE_META_PROPERTY,
                    new MessageMeta(
                            emptyList(),
                            emptyList(),
                            false,
                            "",
                            "",
                            currentSessionWebSearchActivity(session),
                            emptyList(),
                            citations
                    )
            );
            refreshWebTranscript(true);
        });
    }

    private String currentSessionWebSearchActivity(StreamingSession session) {
        synchronized (session.webSearchActivity) {
            return normalizeWebSearchActivity(session.webSearchActivity.toString());
        }
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
        String normalizedThinkingToken;
        synchronized (terminalPersistenceLock) {
            if (!canAcceptStreamingCallback(session) || (!forceRender && !sendJob.reasoningLevel.enabled())) {
                return;
            }
            normalizedThinkingToken = normalizeThinkingText(thinkingToken);
            if (normalizedThinkingToken.isEmpty()) {
                return;
            }
            appendAssistantThinking(session, normalizedThinkingToken);
        }
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

    private boolean persistAssistantResponse(StreamingSession session, SendJob sendJob) {
        if (session.persisted.get()) {
            return true;
        }
        PreparedAssistantResponse preparedResponse = prepareAssistantResponse(session, sendJob);
        if (preparedResponse == null) {
            return session.persisted.get();
        }
        applyPreparedAssistantResponse(session, preparedResponse);
        return true;
    }

    private PreparedAssistantResponse prepareAssistantResponse(StreamingSession session, SendJob sendJob) {
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
        boolean consultedSourceMode;
        synchronized (session.webSearchSourceLock) {
            consultedSourceMode = session.consultedSourceMode;
        }
        if (!consultedSourceMode) {
            assistantText = appendCitationSourcesIfNeeded(assistantText, citations);
            assistantWebSearch = mergeAssistantWebSearchWithAnswerSources(
                    sendJob,
                    assistantText,
                    assistantWebSearch,
                    citations
            );
        }
        assistantWebSearch = normalizeWebSearchActivity(assistantWebSearch);
        List<ContentPart> assistantParts = assistantResponseParts(session, assistantText);
        boolean hasContent = hasVisibleAssistantText(assistantText)
                || assistantParts.stream().anyMatch(part -> !(part instanceof TextPart))
                || hasVisibleThinkingContent(assistantThinking)
                || StringUtils.isNotBlank(assistantWebSearch)
                || hasVisibleAgentToolActivity(session);
        if (!hasContent || !session.persisted.compareAndSet(false, true)) {
            return null;
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
        int assistantOrdinal = sendJob == null ? Math.max(1, history.size()) : sendJob.assistantMessageOrdinal;
        var assistantEntry = new ConversationHistoryEntry(UUID.randomUUID(), assistantOrdinal, assistantMessage);
        if (session.conversationId != null) {
            queuePendingAssistantRecovery(session.conversationId, assistantEntry);
        }
        persistAssistantMessageEvent(session.conversationId, assistantEntry);
        return new PreparedAssistantResponse(assistantEntry, assistantWebSearch);
    }

    private void applyPreparedAssistantResponse(
            StreamingSession session,
            PreparedAssistantResponse preparedResponse
    ) {
        if (preparedResponse == null) {
            return;
        }
        UUID conversationId = session.conversationId;
        if (!isVisibleConversation(conversationId)) {
            return;
        }
        if (StringUtils.isNotBlank(preparedResponse.webSearchActivity())) {
            showWebSearchActivity(session, preparedResponse.webSearchActivity(), true);
        }
        Message assistantMessage = preparedResponse.entry().message();
        history.add(assistantMessage);
        refreshModelSelectorConversationState();
        int assistantMessageIndex = history.size() - 1;
        if (currentAssistantBubble == null) {
            if (hasVisibleAssistantMessageContent(assistantMessage)) {
                addBubble(createMessageView(Role.ASSISTANT), assistantMessage, Role.ASSISTANT, assistantMessageIndex);
            }
        } else if (hasVisibleAssistantMessageContent(assistantMessage)) {
            currentAssistantBubble.setContentParts(assistantMessage.parts());
            currentAssistantBubble.component().putClientProperty(MESSAGE_META_PROPERTY, assistantMessage.meta());
            setMessageIndex(currentAssistantBubble, assistantMessageIndex);
            prepareReadAloudAvailability(currentAssistantBubble);
            refreshWebTranscript(false);
        } else {
            removeMessageComponentFromPanel(currentAssistantBubble.component());
        }
        refreshBubbleActionBars();
        updateClearChatButtonVisibility();
        refreshWebTranscript(false, true);
        nextMessageOrdinal = preparedResponse.entry().ordinal() + 1;
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
                .filter(citation -> ExternalLinkSupport.isAllowedHttpLink(citation.url()))
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

    private boolean hasVisibleAssistantMessageContent(Message message) {
        return message != null
                && (hasVisibleAssistantText(message.content())
                || message.parts().stream().anyMatch(part -> !(part instanceof TextPart)));
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
                removeReadAloudAvailability(view);
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
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshProviders);
            return;
        }
        if (shutdownInProgress || removed) {
            return;
        }
        invalidateSelectedProviderRuntimeOnEdt(false);
        long refreshId = providerRefreshCounter.incrementAndGet();
        long scopeVersion = modelCacheService.nextScopeVersion();
        providerScopeVersion = scopeVersion;
        Thread.startVirtualThread(() -> {
            try {
                if (!providerRefreshCurrent(refreshId)) {
                    return;
                }

                List<ProviderRegistry.ProviderDef> providers = providerRegistry.availableProviders();
                if (!providerRefreshCurrent(refreshId)) {
                    return;
                }
                prepareProviderModels(providers, scopeVersion);
                SwingUtilities.invokeLater(() -> {
                    if (!providerRefreshCurrent(refreshId)) {
                        return;
                    }

                    boolean applied = applyProviderModels(providers, scopeVersion);
                    if (applied) {
                        installedProviderScope = scopeVersion;
                    }
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
            if (preparingJob.durableUserMessageSubmissionStarted
                    || preparingJob.durableHistoryMutationSubmissionStarted
            ) {
                preparingJob.providerContinuationCancelled = true;
                preparingJob.clearCredentialReferences();
            } else {
                preparingJob.cancelled.set(true);
                Thread worker = preparingJob.worker;
                if (worker != null) {
                    worker.interrupt();
                }
                finishSendJob(preparingJob);
                releaseFailedProvisionalConversation(preparingJob);
            }
        }

        StreamingSession session;
        synchronized (terminalPersistenceLock) {
            session = visibleStreamingSession();
            if (session != null && session.terminalCallbackStarted.get()) {
                return;
            }
            if (session != null) {
                if (markAsCancelled) {
                    flushThinkTagParser(session, findSendJobByStreamSession(session.sessionId));
                }
                session.cancelled.set(true);
                if (markAsCancelled) {
                    synchronized (session.webSearchSourceLock) {
                        if (session.consultedSourceMode) {
                            replaceWebSearchActivity(session, renderConsultedSourceActivity(session, false));
                        }
                    }
                } else {
                    discardStreamingResponseAttachments(session);
                }
            }
        }
        if (session != null) {
            try {
                if (markAsCancelled) {
                    String cancelledMarker = "\n\n[Cancelled]";
                    appendAssistantResponse(session, cancelledMarker);
                    if (currentAssistantBubble != null && isVisibleConversation(session.conversationId)) {
                        currentAssistantBubble.appendText(cancelledMarker);
                    }
                    persistAssistantResponse(session, findSendJobByStreamSession(session.sessionId));
                    removeCurrentWebSearchBubbleIfBlank();
                    removeCurrentActivityBubbleIfBlank();
                }
            } finally {
                try {
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
                } finally {
                    try {
                        finishSendJobByStreamSession(session.sessionId);
                    } finally {
                        session.clearProvider();
                    }
                }
            }
        }

        autoScrollQueued = false;
        activeStreamSessionId = -1L;
        setVisibleStreaming(false);
        removeCurrentWebSearchBubbleIfBlank();
        removeCurrentActivityBubbleIfBlank();
        currentAssistantWebSearchBubble = null;
        currentAssistantActivityBubble = null;
        clearCurrentAgentToolBubbleState();
        currentAssistantBubble = null;
        updateGenerationIndicator();
        SwingUtilities.invokeLater(() -> {
            if (!removed
                    && !shutdownInProgress
                    && visiblePreparingJob() == null
                    && visibleStreamingSession() == null
            ) {
                inputBar.setEnabled(true);
                inputBar.requestInputFocus();
            }
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

        try {
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
                    inputBar.setEnabled(!shutdownInProgress);
                    if (!shutdownInProgress) {
                        inputBar.requestInputFocus();
                    }
                }
                updateGenerationIndicator();
            }
        } finally {
            session.clearProvider();
            if (isVisibleConversation(session.conversationId)) {
                refreshWebTranscript(false);
            }
        }
    }

    private void finishSendJob(SendJob sendJob) {
        if (sendJob == null) {
            return;
        }

        sendJob.finished = true;
        activeSendJobs.remove(sendJob.jobId);
        sendJob.clearCredentialReferences();

        if (isVisibleConversation(sendJob.conversationId)
                && visiblePreparingJob() == null
                && visibleStreamingSession() == null
        ) {
            setVisibleStreaming(false);
            inputBar.setEnabled(!shutdownInProgress);
        }

        updateGenerationIndicator();
    }

    private void abandonSendJob(SendJob sendJob) {
        sendJob.cancelled.set(true);
        sendJob.finished = true;
        activeSendJobs.remove(sendJob.jobId);
        sendJob.clearCredentialReferences();
    }

    private void finishSendJobByStreamSession(long streamSessionId) {
        activeSendJobs.values().stream()
                .filter(job -> Objects.equals(job.streamSessionId, streamSessionId))
                .findFirst()
                .ifPresent(this::finishSendJob);
    }

    private void releaseFailedProvisionalConversation(SendJob sendJob) {
        if (sendJob.createsConversation && Objects.equals(activeConversationId, sendJob.conversationId)) {
            activeConversationId = null;
        }
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
        return pdfExportRunning
                || conversationLoading
                || conversationMutationPending
                || blockedConversationIds.contains(activeConversationId)
                || visiblePreparingJob() != null
                || visibleStreamingSession() != null;
    }

    private void cancelSessionActiveRequest(StreamingSession session, boolean allowLegacyProviderFallback) {
        boolean cancelledSessionRequest = session.cancelActiveRequest();
        if (cancelledSessionRequest || !allowLegacyProviderFallback || session.hasRegisteredActiveRequest()) {
            return;
        }
        if (session.provider != null && !hasOtherLiveSessionUsingProvider(session)) {
            try {
                session.provider.cancelActiveRequest();
            } catch (RuntimeException e) {
                log.warn("Provider request cancellation failed: {}", ExceptionUtils.getMessage(e));
            }
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

    private boolean providerRefreshCurrent(long refreshId) {
        return !removed && !shutdownInProgress && providerRefreshCounter.get() == refreshId;
    }

    private List<String> sanitizeModelIds(String providerName, List<String> modelIds) {
        return modelCacheService.modelsWithLocalOverlay(providerName, modelIds);
    }

    private void persistAssistantMessageEvent(UUID conversationId, ConversationHistoryEntry entry) {
        if (conversationId == null || entry == null) {
            return;
        }

        AssistantMessageEvent event = new AssistantMessageEvent(
                conversationId,
                entry.messageId(),
                entry.ordinal(),
                entry.message()
        );
        if (durableAssistantMessageCompletedListener == null) {
            log.warn("Durable assistant persistence is not configured");
            handleAssistantPersistenceFailure(conversationId, entry);
            return;
        }
        try {
            CompletionStage<Void> persistence = durableAssistantMessageCompletedListener.persist(event);
            if (persistence == null) {
                log.warn("Assistant message persistence returned no completion stage");
                handleAssistantPersistenceFailure(conversationId, entry);
                return;
            }
            persistence.whenComplete((ignored, error) -> {
                if (error == null) {
                    runOnEdt(() -> removePendingAssistantRecovery(conversationId, entry.messageId()));
                    return;
                }
                log.warn("Assistant message persistence failed: {}", ExceptionUtils.getMessage(unwrapCompletion(error)));
                runOnEdt(() -> handleAssistantPersistenceFailure(conversationId, entry));
            });
        } catch (Exception e) {
            log.warn("Assistant message persistence listener failed: {}", ExceptionUtils.getMessage(e));
            handleAssistantPersistenceFailure(conversationId, entry);
        }
    }

    private void handleAssistantPersistenceFailure(UUID conversationId, ConversationHistoryEntry entry) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> handleAssistantPersistenceFailure(conversationId, entry));
            return;
        }
        if (discardedConversationIds.contains(conversationId)) {
            discardStagedAttachments(entry.message());
            return;
        }
        queueFailedAssistantRecovery(conversationId, entry);
        if (!shutdownInProgress && !removed && isVisibleConversation(conversationId)) {
            inputBar.showValidationMessage("The assistant response could not be saved and will be retried.");
        }
    }

    public record UserMessageEvent(
            UUID conversationId,
            UUID messageId,
            int ordinal,
            boolean createsConversation,
            Message message,
            String providerName,
            String modelId,
            ReasoningLevel reasoningLevel,
            boolean agentModeEnabled,
            Path agentProjectRoot,
            boolean webSearchEnabled,
            boolean visibleConversation
    ) {
        @Override
        public String toString() {
            return "UserMessageEvent[conversationId=%s, messageId=%s, ordinal=%d, createsConversation=%s, message=<masked>, providerName=%s, modelId=%s, reasoningLevel=%s, agentModeEnabled=%s, agentProjectRoot=<masked>, webSearchEnabled=%s, visibleConversation=%s]"
                    .formatted(
                            conversationId,
                            messageId,
                            ordinal,
                            createsConversation,
                            providerName,
                            modelId,
                            reasoningLevel,
                            agentModeEnabled,
                            webSearchEnabled,
                            visibleConversation
                    );
        }
    }

    public record WebSearchSettingsEvent(UUID conversationId, boolean requestedEnabled) {
    }

    public record AgentSettingsEvent(UUID conversationId, boolean requestedEnabled, Path projectRoot) {
    }

    public record AssistantMessageEvent(UUID conversationId, UUID messageId, int ordinal, Message message) {
        @Override
        public String toString() {
            return "AssistantMessageEvent[conversationId=%s, message=<masked>]".formatted(conversationId);
        }
    }

    public enum HistoryMutationType {
        EDIT,
        EDIT_AND_TRUNCATE,
        TRUNCATE
    }

    public record HistoryMutationEvent(
            UUID conversationId,
            HistoryMutationType type,
            ConversationHistoryEntry retainedEntry
    ) {
        public HistoryMutationEvent {
            Objects.requireNonNull(type, "type can't be null");
            Objects.requireNonNull(retainedEntry, "retainedEntry can't be null");
        }
    }

    public record ConversationStreamingEvent(UUID conversationId, boolean streaming) {
    }

    @FunctionalInterface
    interface OpenAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface DurableUserMessagePersistenceListener {
        CompletionStage<UUID> persist(UserMessageEvent event) throws Exception;
    }

    @FunctionalInterface
    public interface DurableAssistantMessagePersistenceListener {
        CompletionStage<Void> persist(AssistantMessageEvent event) throws Exception;
    }

    @FunctionalInterface
    public interface DurableHistoryMutationListener {
        CompletionStage<Void> persist(HistoryMutationEvent event) throws Exception;
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
