package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.chat.agent.AgentOrchestrator;
import com.github.drafael.chat4j.chat.agent.AgentRunCallbacks;
import com.github.drafael.chat4j.chat.agent.AgentRunRequest;
import com.github.drafael.chat4j.chat.agent.AgentToolActivity;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AgentToolActivityMeta;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.CodexLocalModelCache;
import com.github.drafael.chat4j.provider.support.ModelSelectionCodec;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.util.Fonts;
import com.github.drafael.chat4j.web.BrowsedPage;
import com.github.drafael.chat4j.web.ModelWebQueryPlanner;
import com.github.drafael.chat4j.web.PerplexityWebSearchProvider;
import com.github.drafael.chat4j.web.WebSearchAvailability;
import com.github.drafael.chat4j.web.WebSearchAvailabilityResolver;
import com.github.drafael.chat4j.web.WebSearchContext;
import com.github.drafael.chat4j.web.WebSearchCoordinator;
import com.github.drafael.chat4j.web.WebSearchResponse;
import com.github.drafael.chat4j.web.WebSearchResult;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.DefaultEditorKit;
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
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.synchronizedList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.joining;

@Slf4j
public class ChatPanel extends JPanel {
    private static final String CARD_EMPTY = "empty";
    private static final String CARD_CHAT = "chat";
    private static final int CHAT_MENU_ICON_SIZE = 14;
    private static final int BUBBLE_ACTION_BUTTON_SIZE = 20;
    private static final int BUBBLE_ACTION_BAR_HEIGHT = 22;
    private static final int BUBBLE_ACTION_BAR_WIDTH = BUBBLE_ACTION_BUTTON_SIZE * 2 + 2;
    private static final int JUMP_OVERLAY_BOTTOM_GAP = 8;
    private static final int COMPOSER_FADE_HEIGHT = 48;
    private static final int CHAT_COLUMN_MAX_WIDTH = 920;
    private static final int CHAT_COLUMN_SIDE_MARGIN = 16;
    private static final String MESSAGE_ROLE_PROPERTY = "chat4j.messageRole";
    private static final Integer COMPOSER_FADE_LAYER = 50;
    private static final boolean THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING = true;
    private static final boolean THINKING_COLLAPSED_BY_DEFAULT_WHEN_LOADING_HISTORY = true;
    private static final boolean WEB_SEARCH_COLLAPSED_BY_DEFAULT = true;
    private static final boolean AGENT_TOOLS_COLLAPSED_BY_DEFAULT = true;
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Pattern NON_PRINTABLE_PATTERN = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern UNICODE_FORMAT_PATTERN = Pattern.compile("\\p{Cf}");
    private static final Pattern SOURCE_URL_PATTERN = Pattern.compile("(?:\\[[^]]+])?\\(?(https?://[^\\s)<>]+)\\)?|<?(https?://[^\\s)<>]+)>?");
    private static final Map<String, Icon> CHAT_MENU_ICON_CACHE = new ConcurrentHashMap<>();

    private final JPanel messagesPanel;
    private final JScrollPane scrollPane;
    private final CardLayout messagesCardLayout = new CardLayout();
    private final JPanel messagesContainer;
    private JPanel emptyStatePanel;
    private final InputBar inputBar;
    private final JLayeredPane bodyLayered;
    private final JPanel bodyContent;
    private final JumpToLatestButton jumpToLatestOverlay;
    private final ComposerFadeOverlay composerFadeOverlay;
    private boolean atBottom = true;
    private final ModelSelectorButton modelSelectorBtn;
    private final ProviderModelCacheService modelCacheService;
    private final ModelFavoritesService modelFavoritesService;
    private final AttachmentStager attachmentStager = new AttachmentStager();
    private final WebSearchAvailabilityResolver webSearchAvailabilityResolver = new WebSearchAvailabilityResolver();
    private final WebSearchCoordinator webSearchCoordinator = new WebSearchCoordinator(List.of(new PerplexityWebSearchProvider()));
    private final CodexAuthResolver codexAuthResolver = new CodexAuthResolver();
    private final CopilotAuthResolver copilotAuthResolver = new CopilotAuthResolver();
    private volatile AgentOrchestrator agentOrchestrator;
    private volatile String agentSystemPromptAppend = "";
    private final List<MessageBubble> assistantBubbles = new ArrayList<>();
    private final List<ActivityBubble> thinkingBubbles = new ArrayList<>();
    private final JToggleButton previewToggle = new JToggleButton(AssistantRenderMode.PREVIEW.displayName());
    private final JToggleButton markdownToggle = new JToggleButton(AssistantRenderMode.MARKDOWN.displayName());
    private AssistantRenderMode assistantRenderMode = AssistantRenderMode.PREVIEW;
    private Consumer<AssistantRenderMode> assistantRenderModeChangedListener;
    private Consumer<String> selectedModelChangedListener;
    private Runnable modelFavoritesChangedListener;
    private Runnable modelCatalogChangedListener;
    private Runnable messageSubmittedListener;
    private Runnable clearChatRequestedListener;
    private AssistantMessagePersistenceListener assistantMessageCompletedListener;
    private Consumer<HistoryTruncatedEvent> historyTruncatedListener;
    private Consumer<ConversationStreamingEvent> conversationStreamingListener;
    private Consumer<Boolean> visibleStreamingChangedListener;
    private Supplier<UUID> conversationIdSupplier;
    private ModelSelectorPopup modelPopup;

    private final List<Message> history = new ArrayList<>();
    private Map<String, ProviderRegistry.ProviderDef> providerMap = emptyMap();
    private String selectedProviderName;
    private String selectedModelId;
    private ProviderService currentProvider;
    private String currentProviderApiKey;
    private volatile boolean currentProviderResolving;
    private ActivityBubble currentAssistantWebSearchBubble;
    private ActivityBubble currentAssistantActivityBubble;
    private final Map<String, ActivityBubble> currentAssistantAgentToolBubbles = new LinkedHashMap<>();
    private MessageBubble currentAssistantBubble;
    private final AtomicLong sendJobCounter = new AtomicLong();
    private final AtomicLong streamSessionCounter = new AtomicLong();
    private final AtomicLong providerSelectionCounter = new AtomicLong();
    private final AtomicLong providerRefreshCounter = new AtomicLong();
    private final Map<Long, SendJob> activeSendJobs = new ConcurrentHashMap<>();
    private final Map<Long, StreamingSession> activeSessions = new ConcurrentHashMap<>();
    private volatile long activeStreamSessionId = -1L;
    private volatile boolean streaming = false;
    private volatile boolean autoScrollEnabled = true;
    private volatile UUID activeConversationId;
    private List<PromptQuickAction> promptQuickActions = emptyList();
    private volatile SendPreparer sendPreparer = this::prepareUserMessage;
    private boolean autoScrollQueued = false;
    private int messageRow = 0;

    public record PromptQuickAction(@NonNull String title, @NonNull Runnable action) {
        public PromptQuickAction {
            title = StringUtils.trimToEmpty(title);
            if (title.isBlank()) {
                throw new IllegalArgumentException("title must not be blank");
            }
        }
    }

    public ChatPanel() {
        this(ProviderModelCacheService.createDefault(), ModelFavoritesService.createInMemory());
        populateModels();
    }

    public ChatPanel(ProviderModelCacheService modelCacheService) {
        this(modelCacheService, ModelFavoritesService.createInMemory());
        populateModels();
    }

    public ChatPanel(ProviderModelCacheService modelCacheService, ModelFavoritesService modelFavoritesService) {
        this.modelCacheService = modelCacheService;
        this.modelFavoritesService = modelFavoritesService;
        this.agentOrchestrator = AgentOrchestrator.createDefault();
        setLayout(new BorderLayout());

        modelSelectorBtn = new ModelSelectorButton();
        modelSelectorBtn.addActionListener(e -> toggleModelPopup());

        JPanel chatHeader = new JPanel(new BorderLayout());
        chatHeader.setOpaque(false);
        chatHeader.setBorder(BorderFactory.createEmptyBorder(4, 12, 0, 12));
        chatHeader.add(createRenderTogglePanel(), BorderLayout.EAST);
        add(chatHeader, BorderLayout.NORTH);

        // Messages area — uses ScrollablePanel + GridBagLayout for proper width tracking
        messagesPanel = new ScrollablePanel();
        messagesPanel.setLayout(new GridBagLayout());
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
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
        messagesContainer.add(scrollPane, CARD_CHAT);
        messagesCardLayout.show(messagesContainer, CARD_EMPTY);

        // Input bar at bottom
        inputBar = new InputBar();
        inputBar.addSendListener(e -> onSend());
        inputBar.addClearChatListener(e -> requestClearChat());
        inputBar.addCancelGenerationListener(e -> cancelStreamingAndMarkCancelled());
        updateClearChatButtonVisibility();

        bodyContent = new JPanel(new BorderLayout());
        bodyContent.setOpaque(false);
        bodyContent.add(messagesContainer, BorderLayout.CENTER);
        bodyContent.add(new CenteredComposerPanel(inputBar), BorderLayout.SOUTH);

        jumpToLatestOverlay = new JumpToLatestButton();
        jumpToLatestOverlay.setVisible(false);
        jumpToLatestOverlay.addActionListener(e -> onJumpToLatestRequested());

        composerFadeOverlay = new ComposerFadeOverlay();

        bodyLayered = new JLayeredPane() {
            @Override
            public Dimension getPreferredSize() {
                return bodyContent.getPreferredSize();
            }

            @Override
            public void doLayout() {
                bodyContent.setBounds(0, 0, getWidth(), getHeight());
                layoutJumpOverlay();
            }
        };
        bodyLayered.add(bodyContent, JLayeredPane.DEFAULT_LAYER);
        bodyLayered.add(composerFadeOverlay, COMPOSER_FADE_LAYER);
        bodyLayered.add(jumpToLatestOverlay, JLayeredPane.PALETTE_LAYER);
        add(bodyLayered, BorderLayout.CENTER);

        inputBar.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutJumpOverlay();
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                layoutJumpOverlay();
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

        boolean shouldShow = !autoScrollEnabled && (streaming || !atBottom);
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
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(this::preloadModelPopup);
    }

    @Override
    public void removeNotify() {
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

        configureRenderToggleButton(previewToggle, "first", "Preview rendered markdown");
        configureRenderToggleButton(markdownToggle, "last", "Show raw markdown");

        previewToggle.addActionListener(e -> {
            if (previewToggle.isSelected()) {
                setAssistantRenderMode(AssistantRenderMode.PREVIEW, true);
            }
        });
        markdownToggle.addActionListener(e -> {
            if (markdownToggle.isSelected()) {
                setAssistantRenderMode(AssistantRenderMode.MARKDOWN, true);
            }
        });

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        panel.setOpaque(false);
        panel.add(previewToggle);
        panel.add(markdownToggle);
        updateRenderModeToggleSelection();
        return panel;
    }

    private void configureRenderToggleButton(JToggleButton button, String segmentPosition, String tooltip) {
        button.putClientProperty("JButton.buttonType", "segmented");
        button.putClientProperty("JButton.segmentPosition", segmentPosition);
        button.setFocusable(false);
        button.setToolTipText(tooltip);
        button.setMargin(new Insets(2, 8, 2, 8));
        Fonts.apply(button, Font.PLAIN, Fonts.SIZE_SMALL);
        button.setPreferredSize(new Dimension(82, 22));
        button.setMinimumSize(new Dimension(82, 22));
    }

    private void populateModels() {
        applyProviderModels(ProviderRegistry.availableProviders());
    }

    private void applyProviderModels(List<ProviderRegistry.ProviderDef> providers) {
        providerMap = providers.stream()
                .collect(toMap(
                    ProviderRegistry.ProviderDef::name,
                    Function.identity(),
                    (existing, replacement) -> existing,
                    LinkedHashMap::new
                ));

        modelCacheService.primeFromDisk(new ArrayList<>(providerMap.keySet()));

        if (selectedProviderName != null
                && selectedModelId != null
                && providerMap.containsKey(selectedProviderName)
        ) {
            selectModel(selectedProviderName, selectedModelId);
            return;
        }

        // Prefer cached models fetched in previous sessions.
        if (!providerMap.isEmpty()) {
            providerMap.values().stream()
                    .map(providerDef -> new ProviderModelSelection(
                        providerDef.name(),
                        sanitizeModelIds(providerDef.name(), modelCacheService.getModels(providerDef.name()))
                    )
                    )
                    .filter(selection -> !selection.models().isEmpty())
                    .findFirst()
                    .ifPresent(selection -> selectModel(selection.providerName(), selection.models().getFirst()));

            if (selectedProviderName != null && selectedModelId != null) {
                return;
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
            return;
        }

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
    }

    private void toggleModelPopup() {
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
        }

        updateThinkingToggleAvailability(selectionId);
        updateWebSearchAvailability(selectionId);
        updateAgentToggleAvailability(selectionId);

        if (selectedModelChangedListener != null) {
            selectedModelChangedListener.accept(getSelectedModel());
        }
    }

    private void resolveCurrentProviderAsync(
            long selectionId,
            ProviderRegistry.ProviderDef providerDef,
            String modelId
    ) {
        currentProviderResolving = true;
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
        log.warn("Failed to prepare provider {}::{}: {}", providerName, modelId, ExceptionUtils.getMessage(error));
    }

    private void onSend() {
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
        if (visibleConversation) {
            inputBar.clear();
            history.add(userMessage);
            addUserBubble(userMessage);
            updateClearChatButtonVisibility();
        }

        UUID persistedConversationId = sendJob.conversationId;
        persistUserMessage(sendJob.conversationId, userMessage, visibleConversation);
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
                currentAssistantBubble = new MessageBubble(Role.ASSISTANT);
                addBubble(currentAssistantBubble, null, Role.ASSISTANT);
            }
        }

        startAssistantStream(sendJob, streamHistory);
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

    private void persistUserMessage(UUID conversationId, Message userMessage, boolean visibleConversation) {
        persistAssistantMessageEvent(conversationId, userMessage);

        if (visibleConversation || conversationId == null) {
            notifyMessageSubmitted();
        }
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
                activity -> handleAgentToolActivity(session, activity),
                () -> {
                    if (!session.beginTerminalCallback()) {
                        return;
                    }
                    flushThinkTagParser(session, sendJob);
                    SwingUtilities.invokeLater(() -> {
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
                    });
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
                        session.provider,
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
                        callbacks.onComplete(),
                        callbacks.onError(),
                        session.cancelled::get
                );
            } catch (Exception e) {
                callbacks.onError().accept(e);
            }
        });
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
        if (!Strings.CS.equals(sendJob.providerName, "OpenAI")) {
            return true;
        }
        return requestHistory.stream()
                .flatMap(message -> message.parts().stream())
                .allMatch(part -> part instanceof TextPart);
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
        if (!session.isLive() || !isVisibleConversation(session.conversationId) || StringUtils.isBlank(normalizedActivity)) {
            return;
        }

        if (currentAssistantWebSearchBubble == null) {
            currentAssistantWebSearchBubble = new ActivityBubble("Web Search", WEB_SEARCH_COLLAPSED_BY_DEFAULT);
            addActivityBubble(currentAssistantWebSearchBubble, null);
        }

        currentAssistantWebSearchBubble.setVisible(true);
        currentAssistantWebSearchBubble.setText(normalizedActivity);
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
            if (!session.isLive() || !isVisibleConversation(session.conversationId)) {
                return;
            }

            currentAssistantBubble = null;
            ActivityBubble toolBubble = currentAssistantAgentToolBubbles.computeIfAbsent(
                    agentToolBubbleKey(activity),
                    ignored -> createAgentToolBubble(activity)
            );
            toolBubble.setVisible(true);
            toolBubble.setTitle(formattedActivity);
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
            return "%s (%s) supports rich input, but file upload mapping is not enabled yet in Chat4J; sending file references.".formatted(
                    providerLabel,
                    modelLabel
            );
        }

        return "%s (%s) uses text-only fallback for file attachments.".formatted(providerLabel, modelLabel);
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
                boolean resolvedSupportsNative = ProviderCapabilityResolver.supportsNativeWebSearch(
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

    private void addUserBubble(Message message) {
        MessageBubble bubble = new MessageBubble(Role.USER);
        bubble.setText(formatUserBubbleText(message));
        bubble.setMaxContentWidth(userBubbleMaxContentWidth());
        installBubbleContextMenu(bubble);
        addMessageComponent(Role.USER, bubble, createAttachmentChipsPanel(userAttachmentRefs(message)));
    }

    private void refreshUserBubbleMaxWidths() {
        int maxWidth = userBubbleMaxContentWidth();
        for (MessageBubble bubble : collectBubbles()) {
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

        if (primaryContent instanceof MessageBubble bubble) {
            JPanel hoverGroup = new JPanel(new BorderLayout());
            hoverGroup.setOpaque(false);
            hoverGroup.add(bubble, BorderLayout.CENTER);
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

    private JComponent createBubbleActionBar(MessageBubble bubble, Role role) {
        int alignment = role == Role.USER ? FlowLayout.RIGHT : FlowLayout.LEFT;
        JPanel bar = new JPanel(new FlowLayout(alignment, 2, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        Dimension size = new Dimension(BUBBLE_ACTION_BAR_WIDTH, BUBBLE_ACTION_BAR_HEIGHT);
        bar.setPreferredSize(size);
        bar.setMinimumSize(size);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, BUBBLE_ACTION_BAR_HEIGHT));
        bar.add(createCopyMessageButton(bubble));
        bar.add(createRegenerateButton(bubble));
        setBubbleActionButtonsVisible(bar, false);
        return bar;
    }

    private JButton createRegenerateButton(MessageBubble bubble) {
        Icon icon = chatMenuIcon("/icons/chat/refresh-cw.svg");
        String tooltip = bubble.getRole() == Role.USER
                ? "Regenerate response"
                : "Regenerate this response";
        return createBubbleActionButton(icon, tooltip, () -> regenerateFromBubble(bubble));
    }

    private JButton createCopyMessageButton(MessageBubble bubble) {
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

    private void copyBubbleTextToClipboard(MessageBubble bubble) {
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

    private boolean canRegenerateFrom(MessageBubble bubble) {
        if (currentProvider == null || isVisibleConversationBusy()) {
            return false;
        }
        List<MessageBubble> bubbles = collectBubbles();
        int bubbleIndex = bubbles.indexOf(bubble);
        if (bubbleIndex < 0 || bubbleIndex >= history.size()) {
            return false;
        }
        int keepCount = bubble.getRole() == Role.USER ? bubbleIndex + 1 : bubbleIndex;
        return keepCount > 0 && history.get(keepCount - 1).role() == Role.USER;
    }

    public boolean canRegenerateRecentResponse() {
        if (currentProvider == null || isVisibleConversationBusy()) {
            return false;
        }
        List<MessageBubble> bubbles = collectBubbles();
        return !bubbles.isEmpty() && canRegenerateFrom(bubbles.getLast());
    }

    public void regenerateRecentResponse() {
        List<MessageBubble> bubbles = collectBubbles();
        if (!bubbles.isEmpty()) {
            regenerateFromBubble(bubbles.getLast());
        }
    }

    private void regenerateFromBubble(MessageBubble bubble) {
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

        List<MessageBubble> bubbles = collectBubbles();
        int bubbleIndex = bubbles.indexOf(bubble);
        if (bubbleIndex < 0 || bubbleIndex >= history.size()) {
            return;
        }

        int keepCount = bubble.getRole() == Role.USER ? bubbleIndex + 1 : bubbleIndex;
        if (keepCount <= 0 || history.get(keepCount - 1).role() != Role.USER) {
            return;
        }

        truncateHistoryAndBubbles(keepCount);
        UUID conversationId = resolveConversationId();
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

        currentAssistantBubble = new MessageBubble(Role.ASSISTANT);
        addBubble(currentAssistantBubble, null, Role.ASSISTANT);
        startAssistantStream(conversationId, currentProvider);
    }

    private void truncateHistoryAndBubbles(int keepCount) {
        while (history.size() > keepCount) {
            history.remove(history.size() - 1);
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
                : BorderFactory.createEmptyBorder(8, sideInset, 8, sideInset + 40));
    }

    private int messageColumnSideInset() {
        int width = scrollPane != null && scrollPane.getViewport() != null ? scrollPane.getViewport().getWidth() : 0;
        if (width <= 0) {
            width = messagesPanel != null ? messagesPanel.getWidth() : 0;
        }
        if (width <= 0) {
            return CHAT_COLUMN_SIDE_MARGIN;
        }
        return Math.max(CHAT_COLUMN_SIDE_MARGIN, (width - CHAT_COLUMN_MAX_WIDTH) / 2);
    }

    private int chatColumnAvailableWidth() {
        int viewport = 0;
        if (scrollPane != null && scrollPane.getViewport() != null) {
            viewport = scrollPane.getViewport().getWidth();
        }
        if (viewport <= 0) {
            viewport = 800;
        }
        return Math.max(320, Math.min(CHAT_COLUMN_MAX_WIDTH, viewport - messageColumnSideInset() * 2));
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
        addBottomFiller();
        refreshMessageColumnInsets();
        messagesPanel.revalidate();
        messagesCardLayout.show(messagesContainer, CARD_CHAT);
        scrollToBottom();
    }

    private static final int EMPTY_STATE_ICON_SIZE = 72;
    private static final int EMPTY_STATE_CHIP_GAP = 8;
    private static final int EMPTY_STATE_PROMPT_CHIP_GAP = 6;

    private JPanel buildEmptyStatePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 24));

        BufferedImage source = loadLogoSource();
        if (source != null) {
            JLabel logo = new JLabel() {
                @Override
                public void updateUI() {
                    super.updateUI();
                    setIcon(new LogoIcon(tintLogo(source), EMPTY_STATE_ICON_SIZE));
                }
            };
            logo.setIcon(new LogoIcon(tintLogo(source), EMPTY_STATE_ICON_SIZE));
            logo.setAlignmentX(Component.CENTER_ALIGNMENT);
            content.add(logo);
            content.add(Box.createVerticalStrut(18));
        }

        JLabel title = new JLabel("How can I help?");
        Fonts.apply(title, Font.BOLD, Fonts.SIZE_DISPLAY);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(title);

        JLabel subtitle = new JLabel("Chat, search the web, or run an agent task against a project.");
        Fonts.apply(subtitle, Font.PLAIN, Fonts.SIZE_BODY);
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(Box.createVerticalStrut(8));
        content.add(subtitle);
        content.add(Box.createVerticalStrut(20));

        JPanel suggestions = new JPanel(new WrapLayout(FlowLayout.CENTER, EMPTY_STATE_CHIP_GAP, EMPTY_STATE_CHIP_GAP));
        suggestions.setOpaque(false);
        suggestions.add(createSuggestionChip("Review codebase", () -> inputBar.requestAgentModeEnabled(true)));
        suggestions.add(createSuggestionChip("Explain selected file", () -> inputBar.requestAttachmentPicker()));
        suggestions.add(createSuggestionChip("Draft commit message", () -> inputBar.requestAgentModeEnabled(true)));
        suggestions.add(createSuggestionChip("Search the web", () -> inputBar.requestWebSearchEnabled(true)));
        content.add(suggestions);

        if (!promptQuickActions.isEmpty()) {
            content.add(Box.createVerticalStrut(14));
            content.add(createEmptyStateSectionLabel("Prompts"));
            content.add(Box.createVerticalStrut(6));
            JPanel promptActions = new JPanel(new WrapLayout(
                    FlowLayout.CENTER,
                    EMPTY_STATE_PROMPT_CHIP_GAP,
                    EMPTY_STATE_PROMPT_CHIP_GAP
            ));
            promptActions.setOpaque(false);
            promptQuickActions.stream()
                    .map(this::createPromptActionChip)
                    .forEach(promptActions::add);
            content.add(promptActions);
        }

        panel.add(content);
        return panel;
    }

    private JButton createSuggestionChip(String text, Runnable action) {
        return createEmptyStateChip(text, true, () -> {
            inputBar.setText(text);
            if (action != null) {
                action.run();
            }
            inputBar.requestInputFocus();
        });
    }

    private JLabel createEmptyStateSectionLabel(String text) {
        JLabel label = new JLabel(text);
        Fonts.apply(label, Font.BOLD, Fonts.SIZE_SMALL);
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    private JButton createPromptActionChip(PromptQuickAction promptQuickAction) {
        return createEmptyStateChip(promptQuickAction.title(), false, () -> {
            promptQuickAction.action().run();
            inputBar.requestInputFocus();
        });
    }

    private JButton createEmptyStateChip(String text, boolean primary, Runnable action) {
        JButton chip = new JButton(text);
        chip.putClientProperty("JButton.buttonType", "roundRect");
        chip.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:999");
        chip.setFocusable(false);
        chip.setMargin(primary ? new Insets(6, 12, 6, 12) : new Insets(4, 10, 4, 10));
        Fonts.apply(chip, Font.PLAIN, primary ? Fonts.SIZE_BODY : Fonts.SIZE_SMALL);
        chip.addActionListener(e -> action.run());
        return chip;
    }

    private static class CenteredComposerPanel extends JPanel {
        private static final int HORIZONTAL_MARGIN = 16;
        private static final int BOTTOM_MARGIN = 12;

        private final JComponent composer;

        CenteredComposerPanel(JComponent composer) {
            this.composer = composer;
            setOpaque(false);
            setLayout(null);
            add(composer);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = composer.getPreferredSize();
            return new Dimension(
                    preferred.width + HORIZONTAL_MARGIN * 2,
                    preferred.height + BOTTOM_MARGIN
            );
        }

        @Override
        public void doLayout() {
            Dimension preferred = composer.getPreferredSize();
            int width = Math.min(CHAT_COLUMN_MAX_WIDTH, Math.max(0, getWidth() - HORIZONTAL_MARGIN * 2));
            int x = Math.max(HORIZONTAL_MARGIN, (getWidth() - width) / 2);
            composer.setBounds(x, 0, width, preferred.height);
        }
    }

    private static class WrapLayout extends FlowLayout {

        private WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width -= getHgap() + 1;
            return minimum;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getWidth();
                if (targetWidth <= 0) {
                    targetWidth = Integer.MAX_VALUE;
                }

                Insets insets = target.getInsets();
                int maxWidth = targetWidth - insets.left - insets.right - getHgap() * 2;
                Dimension dimension = new Dimension();
                int rowWidth = 0;
                int rowHeight = 0;

                for (Component component : target.getComponents()) {
                    if (!component.isVisible()) {
                        continue;
                    }

                    Dimension size = preferred ? component.getPreferredSize() : component.getMinimumSize();
                    if (rowWidth > 0 && rowWidth + getHgap() + size.width > maxWidth) {
                        addRow(dimension, rowWidth, rowHeight);
                        rowWidth = 0;
                        rowHeight = 0;
                    }

                    if (rowWidth > 0) {
                        rowWidth += getHgap();
                    }
                    rowWidth += size.width;
                    rowHeight = Math.max(rowHeight, size.height);
                }

                addRow(dimension, rowWidth, rowHeight);
                dimension.width += insets.left + insets.right + getHgap() * 2;
                dimension.height += insets.top + insets.bottom + getVgap() * 2;
                return dimension;
            }
        }

        private void addRow(Dimension dimension, int rowWidth, int rowHeight) {
            dimension.width = Math.max(dimension.width, rowWidth);
            if (dimension.height > 0) {
                dimension.height += getVgap();
            }
            dimension.height += rowHeight;
        }
    }

    private static BufferedImage loadLogoSource() {
        URL url = ChatPanel.class.getResource("/icons/icon.png");
        if (url == null) {
            return null;
        }
        try {
            return ImageIO.read(url);
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage tintLogo(BufferedImage source) {
        Color base = UIManager.getColor("Label.foreground");
        if (base == null) {
            base = new Color(128, 128, 128);
        }
        int fgRgb = base.getRGB() & 0xFFFFFF;

        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage tinted = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = source.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    continue;
                }
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int luminance = (r * 299 + g * 587 + b * 114) / 1000;
                int newAlpha;
                if (luminance <= 80) {
                    newAlpha = a;
                } else if (luminance >= 180) {
                    newAlpha = 0;
                } else {
                    newAlpha = a * (180 - luminance) / 100;
                }
                if (newAlpha == 0) {
                    continue;
                }
                tinted.setRGB(x, y, (newAlpha << 24) | fgRgb);
            }
        }
        return tinted;
    }

    private static final class LogoIcon implements Icon {
        private final BufferedImage source;
        private final int size;

        LogoIcon(BufferedImage source, int size) {
            this.source = source;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(source, x, y, size, size, null);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private void addBubble(MessageBubble bubble, String text, Role role) {
        if (role == Role.ASSISTANT) {
            bubble.setAssistantRenderMode(assistantRenderMode);
        }

        if (text != null) {
            bubble.setText(text);
        }

        if (role == Role.ASSISTANT) {
            assistantBubbles.add(bubble);
        }
        installBubbleContextMenu(bubble);
        addMessageComponent(role, bubble, null);
    }

    private void addActivityBubble(ActivityBubble bubble, String text) {
        bubble.setAssistantRenderMode(assistantRenderMode);
        thinkingBubbles.add(bubble);

        if (text != null) {
            bubble.setText(text);
        }

        JPanel secondaryInfoWrapper = new JPanel(new BorderLayout());
        secondaryInfoWrapper.setOpaque(false);
        secondaryInfoWrapper.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 16));
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
            return currentAssistantBubble;
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

    private void installBubbleContextMenu(MessageBubble bubble) {
        JEditorPane pane = bubble.getEditorPane();
        JPopupMenu popup = buildBubbleContextMenu(bubble);
        pane.setComponentPopupMenu(popup);

        int shortcut = menuShortcutKeyMask();
        KeyStroke shiftCmdA = KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut | InputEvent.SHIFT_DOWN_MASK);
        pane.getInputMap(JComponent.WHEN_FOCUSED).put(shiftCmdA, "selectConversation");
        pane.getActionMap().put("selectConversation", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectAndCopyConversation();
            }
        });
    }

    private JPopupMenu buildBubbleContextMenu(MessageBubble bubble) {
        JEditorPane pane = bubble.getEditorPane();
        int shortcut = menuShortcutKeyMask();

        JMenuItem copyItem = buildChatMenuItem(
                "Copy",
                "/icons/input/copy.svg",
                KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcut),
                () -> {
                    Action action = pane.getActionMap().get(DefaultEditorKit.copyAction);
                    if (action != null) {
                        ActionEvent forwarded = new ActionEvent(
                                pane,
                                ActionEvent.ACTION_PERFORMED,
                                DefaultEditorKit.copyAction
                        );
                        action.actionPerformed(forwarded);
                    }
                }
        );

        JMenuItem selectMessageItem = buildChatMenuItem(
                "Select Message",
                "/icons/chat/text-select.svg",
                KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut),
                () -> {
                    pane.requestFocusInWindow();
                    pane.selectAll();
                }
        );

        JMenuItem selectConversationItem = buildChatMenuItem(
                "Select Conversation",
                "/icons/chat/messages-square.svg",
                KeyStroke.getKeyStroke(KeyEvent.VK_A, shortcut | InputEvent.SHIFT_DOWN_MASK),
                this::selectAndCopyConversation
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

        JPopupMenu popup = new JPopupMenu();
        popup.add(copyItem);
        popup.addSeparator();
        popup.add(selectMessageItem);
        popup.add(selectConversationItem);
        popup.addSeparator();
        popup.add(regenerateItem);
        popup.add(clearChatItem);

        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean hasSelection = pane.getSelectionStart() != pane.getSelectionEnd();
                copyItem.setEnabled(hasSelection);
                selectMessageItem.setEnabled(!bubble.getFullText().isEmpty());
                selectConversationItem.setEnabled(hasAnyConversationText());
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

    private static final class ThemeAwareSvgIcon implements Icon {
        private final FlatSVGIcon svg;
        private Color currentTint;

        ThemeAwareSvgIcon(URL url, int size) {
            this.svg = new FlatSVGIcon(url).derive(size, size);
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Color label = UIManager.getColor("Label.foreground");
            if (!Objects.equals(label, currentTint)) {
                Color tint = label != null ? label : new Color(90, 90, 90);
                svg.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> new Color(
                        tint.getRed(),
                        tint.getGreen(),
                        tint.getBlue(),
                        color.getAlpha()
                )));
                currentTint = label;
            }
            svg.paintIcon(c, g, x, y);
        }

        @Override
        public int getIconWidth() {
            return svg.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return svg.getIconHeight();
        }
    }

    private List<MessageBubble> collectBubbles() {
        List<MessageBubble> bubbles = new ArrayList<>();
        collectBubbles(messagesPanel, bubbles);
        return bubbles;
    }

    private void collectBubbles(Container container, List<MessageBubble> collected) {
        for (Component child : container.getComponents()) {
            if (child instanceof MessageBubble bubble) {
                collected.add(bubble);
            } else if (child instanceof Container nested) {
                collectBubbles(nested, collected);
            }
        }
    }

    private boolean hasAnyConversationText() {
        return collectBubbles().stream().anyMatch(bubble -> !bubble.getFullText().isEmpty());
    }

    private void selectAndCopyConversation() {
        List<MessageBubble> bubbles = collectBubbles();
        StringBuilder joined = new StringBuilder();
        for (MessageBubble bubble : bubbles) {
            bubble.getEditorPane().selectAll();
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

    private void clearChat(boolean cancelActiveStream) {
        if (cancelActiveStream) {
            cancelStreaming();
        }

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
        messagesCardLayout.show(messagesContainer, CARD_EMPTY);
        updateClearChatButtonVisibility();
    }

    public List<Message> getHistory() {
        return new ArrayList<>(history);
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void loadHistory(List<Message> messages) {
        clearChat(false);

        List<Message> normalizedMessages = normalizeLoadedHistory(messages);
        for (Message msg : normalizedMessages) {
            history.add(msg);
            if (msg.role() == Role.USER) {
                addUserBubble(msg);
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

                if (StringUtils.isBlank(msg.content())) {
                    continue;
                }
            }

            addBubble(new MessageBubble(msg.role()), msg.content(), msg.role());
        }
        updateClearChatButtonVisibility();
    }

    public String getSelectedModel() {
        if (selectedProviderName == null || selectedModelId == null) {
            return null;
        }

        return ModelSelectionCodec.format(selectedProviderName, selectedModelId);
    }

    public void setSelectedModel(String modelKey) {
        ModelSelectionCodec.parse(modelKey)
                .ifPresent(selection -> selectModel(selection.provider(), selection.model()));
    }

    public InputBar getInputBar() {
        return inputBar;
    }

    public ModelSelectorButton getModelSelectorButton() {
        return modelSelectorBtn;
    }

    public void hideModelPopup() {
        if (modelPopup != null) {
            modelPopup.hidePopup();
        }
    }

    public void showModelPopupCentered() {
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

    public AssistantRenderMode getAssistantRenderMode() {
        return assistantRenderMode;
    }

    public void setAssistantRenderMode(AssistantRenderMode mode, boolean rerenderAssistantBubbles) {
        if (mode == null || assistantRenderMode == mode) {
            updateRenderModeToggleSelection();
            return;
        }

        assistantRenderMode = mode;
        updateRenderModeToggleSelection();

        if (rerenderAssistantBubbles) {
            assistantBubbles.forEach(bubble -> bubble.setAssistantRenderMode(mode));
            thinkingBubbles.forEach(bubble -> bubble.setAssistantRenderMode(mode));
            messagesPanel.revalidate();
            messagesPanel.repaint();
        }

        if (assistantRenderModeChangedListener != null) {
            assistantRenderModeChangedListener.accept(mode);
        }
    }

    public void setOnAssistantRenderModeChanged(Consumer<AssistantRenderMode> listener) {
        this.assistantRenderModeChangedListener = listener;
    }

    private void updateRenderModeToggleSelection() {
        if (assistantRenderMode == AssistantRenderMode.MARKDOWN) {
            markdownToggle.setSelected(true);
        } else {
            previewToggle.setSelected(true);
        }
    }

    public void setOnSelectedModelChanged(Consumer<String> listener) {
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
        this.activeConversationId = conversationId;

        StreamingSession visibleSession = visibleStreamingSession();
        SendJob visiblePreparingJob = visiblePreparingJob();

        if (visibleSession != null) {
            activeStreamSessionId = visibleSession.sessionId;
            setVisibleStreaming(true);
            inputBar.setEnabled(false);
        } else if (visiblePreparingJob != null) {
            activeStreamSessionId = -1L;
            setVisibleStreaming(true);
            inputBar.setEnabled(false);
        } else {
            activeStreamSessionId = -1L;
            setVisibleStreaming(false);
            inputBar.setEnabled(true);
        }
        updateGenerationIndicator();
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
        if (!canClearChat()) {
            return;
        }
        if (clearChatRequestedListener != null) {
            clearChatRequestedListener.run();
        }
    }

    public boolean canClearChat() {
        return !history.isEmpty() && !isVisibleConversationBusy() && inputBar.isEnabled();
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
            if (!session.isLive() || !isVisibleConversation(session.conversationId)) {
                return;
            }
            if (currentAssistantBubble == null) {
                currentAssistantBubble = new MessageBubble(Role.ASSISTANT);
                addBubble(currentAssistantBubble, null, Role.ASSISTANT);
            }
            currentAssistantBubble.appendText(token);
            scrollToBottom();
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
            if (!session.isLive() || !isVisibleConversation(session.conversationId)) {
                return;
            }

            if (currentAssistantActivityBubble == null) {
                currentAssistantActivityBubble = new ActivityBubble(THINKING_COLLAPSED_BY_DEFAULT_WHEN_STREAMING);
                currentAssistantActivityBubble.setStreaming(true);
                addActivityBubble(currentAssistantActivityBubble, null);
            }

            currentAssistantActivityBubble.setVisible(true);
            currentAssistantActivityBubble.appendText(normalizedThinkingToken);
            scrollToBottom();
        });
    }

    private void appendAssistantResponse(StreamingSession session, String text) {
        if (session == null || !session.isLive() || StringUtils.isEmpty(text)) {
            return;
        }

        synchronized (session.response) {
            session.response.append(text);
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
        assistantWebSearch = normalizeWebSearchActivity(mergeAssistantWebSearchWithAnswerSources(sendJob, assistantText, assistantWebSearch));
        List<AgentToolActivityMeta> agentToolActivities = snapshotAgentToolActivities(session);
        if (isVisibleConversation(session.conversationId) && StringUtils.isNotBlank(assistantWebSearch)) {
            showWebSearchActivity(session, assistantWebSearch);
        }

        boolean hasContent = StringUtils.isNotBlank(assistantText)
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
                List.of(new TextPart(assistantText)),
                Instant.now(),
                new MessageMeta(
                        emptyList(),
                        emptyList(),
                        false,
                        "",
                        assistantThinking,
                        assistantWebSearch,
                        agentToolActivities
                )
        );
        UUID conversationId = session.conversationId;
        if (isVisibleConversation(conversationId)) {
            history.add(assistantMessage);
            if (currentAssistantBubble == null) {
                addBubble(new MessageBubble(Role.ASSISTANT), assistantText, Role.ASSISTANT);
            }
            updateClearChatButtonVisibility();
        }

        boolean persistedByListener = persistAssistantMessageEvent(conversationId, assistantMessage);
        if (!persistedByListener && (isVisibleConversation(conversationId) || conversationId == null)) {
            notifyMessageSubmitted();
        }
    }

    private String mergeAssistantWebSearchWithAnswerSources(SendJob sendJob, String assistantText, String existingActivity) {
        if (sendJob == null || !sendJob.webSearchEnabled) {
            return existingActivity;
        }

        String sourceActivity = extractWebSearchSourcesFromAssistantText(assistantText);
        if (StringUtils.isBlank(sourceActivity)) {
            return existingActivity;
        }

        return "%s\n\n**Sources**\n%s".formatted(
                StringUtils.defaultString(existingActivity).trim(),
                sourceActivity
        ).trim();
    }

    private String normalizeWebSearchActivity(String activity) {
        return WebSearchActivityNormalizer.normalize(activity);
    }

    private String extractWebSearchSourcesFromAssistantText(String assistantText) {
        if (StringUtils.isBlank(assistantText)) {
            return "";
        }

        List<String> urls = Arrays.stream(assistantText.split("\\R"))
                .map(SOURCE_URL_PATTERN::matcher)
                .filter(Matcher::find)
                .map(matcher -> StringUtils.defaultIfBlank(matcher.group(1), matcher.group(2)))
                .filter(StringUtils::isNotBlank)
                .distinct()
                .limit(10)
                .toList();
        if (urls.isEmpty()) {
            return "";
        }

        StringBuilder sources = new StringBuilder();
        for (String url : urls) {
            sources.append("- <").append(url).append(">\n");
        }
        return sources.toString().trim();
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
        MessageMeta mergedMeta = new MessageMeta(
                meta.activeSkills(),
                meta.fallbackNotices(),
                meta.cancelled(),
                meta.error(),
                mergedThinking,
                mergedWebSearch,
                mergedAgentToolActivities
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
        }

        messagesPanel.remove(current);
        addBottomFiller();
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }


    public void refreshProviders() {
        long refreshId = providerRefreshCounter.incrementAndGet();
        Thread.startVirtualThread(() -> {
            List<ProviderRegistry.ProviderDef> providers = ProviderRegistry.availableProviders();
            SwingUtilities.invokeLater(() -> {
                if (providerRefreshCounter.get() != refreshId) {
                    return;
                }

                applyProviderModels(providers);
                if (modelPopup != null) {
                    modelPopup.invalidateModelList();
                    SwingUtilities.invokeLater(this::preloadModelPopup);
                }
            });
        });
    }

    public void setAutoScrollEnabled(boolean autoScrollEnabled) {
        this.autoScrollEnabled = autoScrollEnabled;
        if (!autoScrollEnabled) {
            autoScrollQueued = false;
        }
        updateGenerationIndicator();
        refreshJumpOverlay();
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

            if (session.provider != null) {
                session.provider.cancelActiveRequest();
            }
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
        return visiblePreparingJob() != null || visibleStreamingSession() != null;
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
    }

    private static List<String> sanitizeModelIds(String providerName, List<String> modelIds) {
        return CodexLocalModelCache.mergeIfCodexProvider(providerName, modelIds);
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

    public record AssistantMessageEvent(UUID conversationId, Message message) {
    }

    public record HistoryTruncatedEvent(UUID conversationId, int keepMessageCount) {
    }

    public record ConversationStreamingEvent(UUID conversationId, boolean streaming) {
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

    private enum SendPhase {
        PREPARING,
        STREAMING
    }

    private static final class SendJob {
        final long jobId;
        volatile UUID conversationId;
        final String providerName;
        final String modelId;
        final String baseUrl;
        final String apiKey;
        final ProviderService provider;
        final List<Message> historySnapshot;
        final ReasoningLevel reasoningLevel;
        final boolean webSearchEnabled;
        final String webSearchOptionId;
        final int webBrowseTopN;
        final boolean agentModeEnabled;
        final Path agentProjectRoot;
        final String agentSystemPromptAppend;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile SendPhase phase = SendPhase.PREPARING;
        volatile Thread worker;
        volatile boolean finished;
        volatile Long streamSessionId;

        SendJob(long jobId,
                UUID conversationId,
                String providerName,
                String modelId,
                String baseUrl,
                String apiKey,
                ProviderService provider,
                List<Message> historySnapshot,
                ReasoningLevel reasoningLevel,
                boolean webSearchEnabled,
                String webSearchOptionId,
                int webBrowseTopN,
                boolean agentModeEnabled,
                Path agentProjectRoot,
                String agentSystemPromptAppend
        ) {
            this.jobId = jobId;
            this.conversationId = conversationId;
            this.providerName = providerName;
            this.modelId = modelId;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.provider = provider;
            this.historySnapshot = List.copyOf(historySnapshot);
            this.reasoningLevel = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
            this.webSearchEnabled = webSearchEnabled;
            this.webSearchOptionId = webSearchOptionId;
            this.webBrowseTopN = webBrowseTopN;
            this.agentModeEnabled = agentModeEnabled;
            this.agentProjectRoot = agentProjectRoot;
            this.agentSystemPromptAppend = StringUtils.defaultString(agentSystemPromptAppend);
        }

        boolean isLive() {
            return !finished && !cancelled.get();
        }
    }

    record ProviderSelectionSnapshot(
            String providerName,
            String modelId,
            ProviderCapabilities capabilities,
            String baseUrl,
            String apiKey
    ) {

        @Override
        public String toString() {
            return "ProviderSelectionSnapshot[providerName=%s, modelId=%s, capabilities=%s, baseUrl=%s, apiKey=<masked>]"
                    .formatted(providerName, modelId, capabilities, baseUrl);
        }
    }

    private static final class SendCancelledException extends RuntimeException {
    }

    private static final class StreamingSession {
        final long sessionId;
        final UUID conversationId;
        final ProviderService provider;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean persisted = new AtomicBoolean(false);
        final AtomicBoolean terminalCallbackStarted = new AtomicBoolean(false);
        final StringBuilder response = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder webSearchActivity = new StringBuilder();
        final List<AgentToolActivity> agentToolActivities = synchronizedList(new ArrayList<>());
        final ThinkTagStreamParser thinkTagParser = new ThinkTagStreamParser();
        volatile Thread worker;
        volatile boolean finished = false;

        StreamingSession(long sessionId, UUID conversationId, ProviderService provider) {
            this.sessionId = sessionId;
            this.conversationId = conversationId;
            this.provider = provider;
        }

        boolean isLive() {
            return !finished && !cancelled.get();
        }

        boolean beginTerminalCallback() {
            return isLive() && terminalCallbackStarted.compareAndSet(false, true);
        }
    }

    private static final class ThinkTagStreamParser {
        private static final String OPEN_TAG = "<think>";
        private static final String CLOSE_TAG = "</think>";

        private final StringBuilder pending = new StringBuilder();
        private boolean inThinking;

        ThinkTagSplit accept(String token) {
            if (StringUtils.isEmpty(token)) {
                return ThinkTagSplit.empty();
            }

            pending.append(token);
            return drain(false);
        }

        ThinkTagSplit flush() {
            return drain(true);
        }

        private ThinkTagSplit drain(boolean flush) {
            StringBuilder visible = new StringBuilder();
            StringBuilder thinking = new StringBuilder();

            while (!pending.isEmpty()) {
                String tag = inThinking ? CLOSE_TAG : OPEN_TAG;
                int tagIndex = Strings.CI.indexOf(pending.toString(), tag);
                if (tagIndex >= 0) {
                    appendCurrentModeText(visible, thinking, pending.substring(0, tagIndex));
                    pending.delete(0, tagIndex + tag.length());
                    inThinking = !inThinking;
                    continue;
                }

                int keepLength = flush ? 0 : partialTagSuffixLength(pending, tag);
                int emitLength = pending.length() - keepLength;
                if (emitLength <= 0) {
                    break;
                }

                appendCurrentModeText(visible, thinking, pending.substring(0, emitLength));
                pending.delete(0, emitLength);
            }

            if (flush && !pending.isEmpty()) {
                appendCurrentModeText(visible, thinking, pending.toString());
                pending.setLength(0);
            }

            return new ThinkTagSplit(visible.toString(), thinking.toString());
        }

        private void appendCurrentModeText(StringBuilder visible, StringBuilder thinking, String text) {
            if (text.isEmpty()) {
                return;
            }

            if (inThinking) {
                thinking.append(text);
            } else {
                visible.append(text);
            }
        }

        private int partialTagSuffixLength(StringBuilder value, String tag) {
            int maxLength = Math.min(value.length(), tag.length() - 1);
            for (int length = maxLength; length > 0; length--) {
                String suffix = value.substring(value.length() - length);
                if (Strings.CI.startsWith(tag, suffix)) {
                    return length;
                }
            }

            return 0;
        }
    }

    private record ThinkTagSplit(String visibleText, String thinkingText) {
        static ThinkTagSplit empty() {
            return new ThinkTagSplit("", "");
        }
    }

    private record ProviderModelSelection(String providerName, List<String> models) {
    }

    private static class ScrollablePanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class ComposerFadeOverlay extends JComponent {

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }

            Color background = resolveChatBackground();
            Color transparent = new Color(background.getRed(), background.getGreen(), background.getBlue(), 0);

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            LinearGradientPaint fade = new LinearGradientPaint(
                    new Point2D.Float(0, 0),
                    new Point2D.Float(0, h),
                    new float[] { 0f, 1f },
                    new Color[] { transparent, background }
            );
            g2.setPaint(fade);
            g2.fillRect(0, 0, w, h);
            g2.dispose();
        }

        @Override
        public boolean contains(int x, int y) {
            return false;
        }

        private Color resolveChatBackground() {
            Color color = UIManager.getColor("Panel.background");
            if (color == null) {
                color = getBackground();
            }
            return color != null ? color : new Color(245, 245, 245);
        }
    }
}
