package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;

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
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import javax.imageio.ImageIO;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;

public class ChatPanel extends JPanel {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_CHAT = "chat";
    private static final int CHAT_MENU_ICON_SIZE = 14;
    private static final Map<String, Icon> CHAT_MENU_ICON_CACHE = new ConcurrentHashMap<>();

    private final JPanel messagesPanel;
    private final JScrollPane scrollPane;
    private final CardLayout messagesCardLayout = new CardLayout();
    private final JPanel messagesContainer;
    private final InputBar inputBar;
    private final ModelSelectorButton modelSelectorBtn;
    private final ProviderModelCacheService modelCacheService;
    private final ModelFavoritesService modelFavoritesService;
    private final AttachmentStager attachmentStager = new AttachmentStager();
    private final List<MessageBubble> assistantBubbles = new ArrayList<>();
    private final JToggleButton previewToggle = new JToggleButton(AssistantRenderMode.PREVIEW.displayName());
    private final JToggleButton markdownToggle = new JToggleButton(AssistantRenderMode.MARKDOWN.displayName());
    private AssistantRenderMode assistantRenderMode = AssistantRenderMode.PREVIEW;
    private Consumer<AssistantRenderMode> assistantRenderModeChangedListener;
    private Consumer<String> selectedModelChangedListener;
    private Runnable modelFavoritesChangedListener;
    private Runnable modelCatalogChangedListener;
    private Runnable messageSubmittedListener;
    private Consumer<AssistantMessageEvent> assistantMessageCompletedListener;
    private Consumer<HistoryTruncatedEvent> historyTruncatedListener;
    private Supplier<UUID> conversationIdSupplier;
    private ModelSelectorPopup modelPopup;

    private final List<Message> history = new ArrayList<>();
    private Map<String, ProviderRegistry.ProviderDef> providerMap = Map.of();
    private String selectedProviderName;
    private String selectedModelId;
    private ProviderService currentProvider;
    private MessageBubble currentAssistantBubble;
    private final AtomicLong sendJobCounter = new AtomicLong();
    private final AtomicLong streamSessionCounter = new AtomicLong();
    private final Map<Long, SendJob> activeSendJobs = new ConcurrentHashMap<>();
    private final Map<Long, StreamingSession> activeSessions = new ConcurrentHashMap<>();
    private volatile long activeStreamSessionId = -1L;
    private volatile boolean streaming = false;
    private volatile boolean autoScrollEnabled = true;
    private volatile UUID activeConversationId;
    private volatile SendPreparer sendPreparer = this::prepareUserMessage;
    private boolean autoScrollQueued = false;
    private int messageRow = 0;

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
        setLayout(new BorderLayout());

        modelSelectorBtn = new ModelSelectorButton();
        modelSelectorBtn.addActionListener(e -> toggleModelPopup());

        JPanel chatHeader = new JPanel(new BorderLayout());
        chatHeader.setOpaque(false);
        chatHeader.setBorder(BorderFactory.createEmptyBorder(4, 12, 0, 12));

        JPanel renderTogglePanel = createRenderTogglePanel();
        chatHeader.add(renderTogglePanel, BorderLayout.EAST);
        add(chatHeader, BorderLayout.NORTH);

        // Messages area — uses ScrollablePanel + GridBagLayout for proper width tracking
        messagesPanel = new ScrollablePanel();
        messagesPanel.setLayout(new GridBagLayout());
        messagesPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        messagesPanel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
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
        messagesContainer.add(buildEmptyStatePanel(), CARD_EMPTY);
        messagesContainer.add(scrollPane, CARD_CHAT);
        messagesCardLayout.show(messagesContainer, CARD_EMPTY);
        add(messagesContainer, BorderLayout.CENTER);

        // Input bar at bottom
        inputBar = new InputBar();
        inputBar.addSendListener(e -> onSend());
        inputBar.addJumpToLatestListener(e -> onJumpToLatestRequested());
        inputBar.addCancelGenerationListener(e -> cancelStreamingAndMarkCancelled());
        add(inputBar, BorderLayout.SOUTH);
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

        configureRenderToggleButton(
            previewToggle,
            "first",
            "Preview rendered markdown"
        );
        configureRenderToggleButton(
            markdownToggle,
            "last",
            "Show raw markdown"
        );

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

    private void configureRenderToggleButton(
        JToggleButton button,
        String segmentPosition,
        String tooltip
    ) {
        button.putClientProperty("JButton.buttonType", "segmented");
        button.putClientProperty("JButton.segmentPosition", segmentPosition);
        button.setFocusable(false);
        button.setToolTipText(tooltip);
        button.setMargin(new Insets(2, 8, 2, 8));
        Fonts.apply(button, Font.PLAIN, Fonts.SIZE_SMALL);
        button.setPreferredSize(new Dimension(92, 22));
        button.setMinimumSize(new Dimension(92, 22));
    }

    private void populateModels() {
        providerMap = ProviderRegistry.availableProviders().stream()
                .collect(Collectors.toMap(
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

            if (currentProvider != null) {
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

        selectedProviderName = null;
        selectedModelId = null;
        currentProvider = null;
        modelSelectorBtn.setSelection("", "");
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
                (providerName, modelId) -> selectModel(providerName, modelId),
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
        selectedProviderName = providerName;
        selectedModelId = modelId;
        modelSelectorBtn.setSelection(providerName, modelId);

        currentProvider = null;
        ProviderRegistry.ProviderDef providerDef = providerMap.get(providerName);
        if (providerDef != null) {
            currentProvider = providerDef.factory().create(modelId);
        }

        if (selectedModelChangedListener != null) {
            selectedModelChangedListener.accept(getSelectedModel());
        }
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
        if (provider == null) {
            inputBar.showValidationMessage("Select a model/provider before sending.");
            return;
        }

        long sendJobId = sendJobCounter.incrementAndGet();
        SendJob sendJob = new SendJob(
                sendJobId,
                resolveConversationId(),
                provider,
                new ArrayList<>(history)
        );
        activeSendJobs.put(sendJobId, sendJob);

        ProviderSelectionSnapshot providerSnapshot = captureProviderSelection();
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
        if (visibleConversation) {
            inputBar.clear();
            history.add(userMessage);
            addUserBubble(userMessage);
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
            currentAssistantBubble = new MessageBubble(Role.ASSISTANT);
            addBubble(currentAssistantBubble, null, Role.ASSISTANT);
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
        if (assistantMessageCompletedListener != null && conversationId != null) {
            assistantMessageCompletedListener.accept(new AssistantMessageEvent(conversationId, userMessage));
        }

        if (visibleConversation || conversationId == null) {
            notifyMessageSubmitted();
        }
    }

    private void startAssistantStream(UUID conversationId, ProviderService provider) {
        SendJob sendJob = new SendJob(
                sendJobCounter.incrementAndGet(),
                conversationId,
                provider,
                new ArrayList<>(history)
        );
        activeSendJobs.put(sendJob.jobId, sendJob);
        startAssistantStream(sendJob, new ArrayList<>(history));
    }

    private void startAssistantStream(SendJob sendJob, List<Message> requestHistory) {
        sendJob.phase = SendPhase.STREAMING;
        StreamingSession session = beginStreamingSession(sendJob.conversationId, sendJob.provider);
        sendJob.streamSessionId = session.sessionId;

        session.worker = Thread.startVirtualThread(() -> {
            session.provider.streamCompletion(
                requestHistory,
                token -> {
                        if (!session.isLive()) {
                            return;
                        }
                        appendAssistantResponse(session, token);
                        SwingUtilities.invokeLater(() -> {
                            if (!session.isLive()
                                    || currentAssistantBubble == null
                                    || !isVisibleConversation(session.conversationId)
                            ) {
                                return;
                            }
                            currentAssistantBubble.appendText(token);
                            scrollToBottom();
                        });
                    },
                () -> SwingUtilities.invokeLater(() -> {
                        if (!session.isLive()) {
                            return;
                        }
                        persistAssistantResponse(session, true);
                        if (isVisibleConversation(session.conversationId)) {
                            currentAssistantBubble = null;
                        }
                        finishStreamingSession(session);
                        finishSendJob(sendJob);
                    }),
                error -> {
                        if (!session.isLive()) {
                            return;
                        }
                        String details = StringUtils.defaultIfBlank(error.getMessage(), "Unknown error");
                        String errorText = "\n\n[Error: %s]".formatted(details);
                        appendAssistantResponse(session, errorText);
                        SwingUtilities.invokeLater(() -> {
                            if (!session.isLive()) {
                                return;
                            }
                            if (currentAssistantBubble != null && isVisibleConversation(session.conversationId)) {
                                currentAssistantBubble.appendText(errorText);
                            }
                            persistAssistantResponse(session, false);
                            if (isVisibleConversation(session.conversationId)) {
                                currentAssistantBubble = null;
                            }
                            finishStreamingSession(session);
                            finishSendJob(sendJob);
                        });
                    },
                session.cancelled::get
            );
        });
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
        if (attachments == null || attachments.isEmpty()) {
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

    private ProviderSelectionSnapshot captureProviderSelection() {
        ProviderRegistry.ProviderDef providerDef = selectedProviderDef();
        ProviderCapabilities capabilities = providerDef == null ? null : providerDef.capabilities();
        String baseUrl = providerDef == null ? null : providerDef.baseUrl();
        String apiKey = providerDef == null ? null : CredentialResolver.resolveApiKey(providerDef.envVar(), null);

        return new ProviderSelectionSnapshot(selectedProviderName, selectedModelId, capabilities, baseUrl, apiKey);
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
                .flatMap(text -> text.lines())
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
                .collect(Collectors.joining("\n"))
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
        int reserved = USER_LEFT_GUTTER + USER_BUBBLE_INSET + USER_ROW_PADDING;
        return Math.max(160, viewport - reserved);
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
        wrapper.setBorder(role == Role.USER
                ? BorderFactory.createEmptyBorder(2, USER_LEFT_GUTTER, 2, 0)
                : BorderFactory.createEmptyBorder(8, 0, 8, 40));
        if (topContent != null) {
            wrapper.add(topContent, BorderLayout.NORTH);
        }

        if (primaryContent instanceof MessageBubble bubble) {
            JPanel hoverGroup = new JPanel(new BorderLayout());
            hoverGroup.setOpaque(false);
            hoverGroup.add(bubble, BorderLayout.CENTER);
            JComponent actionBar = createBubbleActionBar(bubble, role);
            actionBar.setVisible(false);
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
        bar.add(createCopyMessageButton(bubble));
        bar.add(createRegenerateButton(bubble));
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
                if (!actionBar.isVisible()) {
                    actionBar.setVisible(true);
                    hoverGroup.revalidate();
                    hoverGroup.repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!actionBar.isVisible() || !hoverGroup.isShowing()) {
                    return;
                }
                Point screenPoint = new Point(e.getXOnScreen(), e.getYOnScreen());
                SwingUtilities.convertPointFromScreen(screenPoint, hoverGroup);
                if (!hoverGroup.contains(screenPoint)) {
                    actionBar.setVisible(false);
                    hoverGroup.revalidate();
                    hoverGroup.repaint();
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

    private JButton createBubbleActionButton(Icon icon, String tooltip, Runnable action) {
        JButton button = new JButton();
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:8");
        button.setIcon(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setPreferredSize(new Dimension(22, 22));
        button.setMinimumSize(new Dimension(22, 22));
        if (action != null) {
            button.addActionListener(e -> action.run());
        }
        return button;
    }

    private void copyBubbleTextToClipboard(MessageBubble bubble) {
        String text = bubble.getFullText();
        if (text.isEmpty()) {
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

    private void regenerateFromBubble(MessageBubble bubble) {
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

        currentAssistantBubble = new MessageBubble(Role.ASSISTANT);
        addBubble(currentAssistantBubble, null, Role.ASSISTANT);
        startAssistantStream(conversationId, currentProvider);
    }

    private void truncateHistoryAndBubbles(int keepCount) {
        while (history.size() > keepCount) {
            history.remove(history.size() - 1);
        }

        List<Component> wrappers = new ArrayList<>();
        for (Component child : messagesPanel.getComponents()) {
            if (!"filler".equals(child.getName())) {
                wrappers.add(child);
            }
        }
        for (int i = wrappers.size() - 1; i >= keepCount; i--) {
            messagesPanel.remove(wrappers.get(i));
        }

        List<MessageBubble> remaining = collectBubbles();
        assistantBubbles.retainAll(remaining);
        if (currentAssistantBubble != null && !remaining.contains(currentAssistantBubble)) {
            currentAssistantBubble = null;
        }

        addBottomFiller();
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    private void addMessageWrapper(JPanel wrapper) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = messageRow++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTH;

        messagesPanel.add(wrapper, gbc);
        addBottomFiller();
        messagesPanel.revalidate();
        messagesCardLayout.show(messagesContainer, CARD_CHAT);
        scrollToBottom();
    }

    private static final int EMPTY_STATE_ICON_SIZE = 128;

    private JPanel buildEmptyStatePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        BufferedImage source = loadLogoSource();
        if (source != null) {
            JLabel label = new JLabel() {
                @Override
                public void updateUI() {
                    super.updateUI();
                    setIcon(new LogoIcon(tintLogo(source), EMPTY_STATE_ICON_SIZE));
                }
            };
            label.setIcon(new LogoIcon(tintLogo(source), EMPTY_STATE_ICON_SIZE));
            panel.add(label);
        }
        return panel;
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
            assistantBubbles.add(bubble);
        }

        if (text != null) {
            bubble.setText(text);
        }

        installBubbleContextMenu(bubble);
        addMessageComponent(role, bubble, null);
    }

    private void addBottomFiller() {
        for (Component c : messagesPanel.getComponents()) {
            if ("filler".equals(c.getName())) {
                messagesPanel.remove(c);
                break;
            }
        }
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

    private void installBubbleContextMenu(MessageBubble bubble) {
        JEditorPane pane = bubble.getEditorPane();
        JPopupMenu popup = buildBubbleContextMenu(bubble);
        pane.setComponentPopupMenu(popup);

        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
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
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

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

        JPopupMenu popup = new JPopupMenu();
        popup.add(copyItem);
        popup.addSeparator();
        popup.add(selectMessageItem);
        popup.add(selectConversationItem);
        popup.addSeparator();
        popup.add(regenerateItem);

        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                boolean hasSelection = pane.getSelectionStart() != pane.getSelectionEnd();
                copyItem.setEnabled(hasSelection);
                selectMessageItem.setEnabled(!bubble.getFullText().isEmpty());
                selectConversationItem.setEnabled(hasAnyConversationText());
                regenerateItem.setEnabled(canRegenerateFrom(bubble));
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

    private void addErrorBubble(String message) {
        MessageBubble errorBubble = new MessageBubble(Role.ASSISTANT);
        errorBubble.setText(message);
        errorBubble.setBackground(new Color(200, 50, 50));
        addBubble(errorBubble, null, Role.ASSISTANT);
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

        currentAssistantBubble = null;
        history.clear();
        assistantBubbles.clear();
        messagesPanel.removeAll();
        messageRow = 0;
        messagesPanel.revalidate();
        messagesPanel.repaint();
        messagesCardLayout.show(messagesContainer, CARD_EMPTY);
    }

    public List<Message> getHistory() {
        return new ArrayList<>(history);
    }

    public void loadHistory(List<Message> messages) {
        clearChat(false);
        for (Message msg : messages) {
            history.add(msg);
            if (msg.role() == Role.USER) {
                addUserBubble(msg);
            } else {
                addBubble(new MessageBubble(msg.role()), msg.content(), msg.role());
            }
        }
    }

    public String getSelectedModel() {
        if (selectedProviderName == null || selectedModelId == null) {
            return null;
        }

        return "%s > %s".formatted(selectedProviderName, selectedModelId);
    }

    public void setSelectedModel(String modelKey) {
        if (modelKey == null) {
            return;
        }

        String[] parts = modelKey.split(" > ", 2);
        if (parts.length == 2) {
            selectModel(parts[0], parts[1]);
        }
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

    public void setOnAssistantMessageCompleted(Consumer<AssistantMessageEvent> listener) {
        this.assistantMessageCompletedListener = listener;
    }

    public void setOnHistoryTruncated(Consumer<HistoryTruncatedEvent> listener) {
        this.historyTruncatedListener = listener;
    }

    public void setConversationIdSupplier(Supplier<UUID> supplier) {
        this.conversationIdSupplier = supplier;
    }

    void setSendPreparerForTests(SendPreparer sendPreparer) {
        this.sendPreparer = sendPreparer == null ? this::prepareUserMessage : sendPreparer;
    }

    public void setActiveConversationId(UUID conversationId) {
        this.activeConversationId = conversationId;

        StreamingSession visibleSession = visibleStreamingSession();
        SendJob visiblePreparingJob = visiblePreparingJob();

        if (visibleSession != null) {
            activeStreamSessionId = visibleSession.sessionId;
            streaming = true;
            inputBar.setEnabled(false);
        } else if (visiblePreparingJob != null) {
            activeStreamSessionId = -1L;
            streaming = true;
            inputBar.setEnabled(false);
        } else {
            activeStreamSessionId = -1L;
            streaming = false;
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

    private void appendAssistantResponse(StreamingSession session, String text) {
        if (session == null || !session.isLive() || text == null || text.isEmpty()) {
            return;
        }

        synchronized (session.response) {
            session.response.append(text);
        }
    }

    private void persistAssistantResponse(StreamingSession session, boolean allowBlankContent) {
        String assistantText;
        synchronized (session.response) {
            assistantText = session.response.toString();
        }

        boolean hasContent = !assistantText.isBlank();
        if (!allowBlankContent && !hasContent) {
            return;
        }

        Message assistantMessage = Message.assistant(assistantText);
        UUID conversationId = session.conversationId;
        if (isVisibleConversation(conversationId)) {
            history.add(assistantMessage);
            if (currentAssistantBubble == null) {
                addBubble(new MessageBubble(Role.ASSISTANT), assistantText, Role.ASSISTANT);
            }
        }

        if (assistantMessageCompletedListener != null && conversationId != null) {
            assistantMessageCompletedListener.accept(new AssistantMessageEvent(conversationId, assistantMessage));
            return;
        }

        if (isVisibleConversation(conversationId) || conversationId == null) {
            notifyMessageSubmitted();
        }
    }

    private void updateRenderModeToggleSelection() {
        if (assistantRenderMode == AssistantRenderMode.MARKDOWN) {
            markdownToggle.setSelected(true);
        } else {
            previewToggle.setSelected(true);
        }
    }

    public void refreshProviders() {
        populateModels();
        if (modelPopup != null) {
            modelPopup.invalidateModelList();
            SwingUtilities.invokeLater(this::preloadModelPopup);
        }
    }

    public void setAutoScrollEnabled(boolean autoScrollEnabled) {
        this.autoScrollEnabled = autoScrollEnabled;
        if (!autoScrollEnabled) {
            autoScrollQueued = false;
        }
        updateGenerationIndicator();
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
                persistAssistantResponse(session, false);
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
            finishSendJobByStreamSession(session.sessionId);
        }

        autoScrollQueued = false;
        activeStreamSessionId = -1L;
        streaming = false;
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
        if (isVisibleConversation(conversationId)) {
            activeStreamSessionId = streamSessionId;
            streaming = true;
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

        if (activeStreamSessionId == session.sessionId) {
            autoScrollQueued = false;
            activeStreamSessionId = -1L;
            if (visiblePreparingJob() == null && visibleStreamingSession() == null) {
                streaming = false;
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
            streaming = false;
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

    private boolean isPreparing(SendJob sendJob) {
        return sendJob != null
                && activeSendJobs.containsKey(sendJob.jobId)
                && sendJob.phase == SendPhase.PREPARING
                && sendJob.isLive();
    }

    private boolean isVisibleConversationBusy() {
        return visiblePreparingJob() != null || visibleStreamingSession() != null;
    }

    private boolean isVisibleConversationStreaming() {
        return visibleStreamingSession() != null;
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

        streaming = indicatorVisible;
        inputBar.setGenerationStatusText(showingPreparing ? "Preparing…" : "Generating response…");
        inputBar.setGenerationIndicatorVisible(indicatorVisible);
    }

    private static List<String> sanitizeModelIds(String providerName, List<String> modelIds) {
        return ModelOrdering.sanitizeAndSortByProvider(providerName, modelIds);
    }

    public record AssistantMessageEvent(UUID conversationId, Message message) {
    }

    public record HistoryTruncatedEvent(UUID conversationId, int keepMessageCount) {
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
        final ProviderService provider;
        final List<Message> historySnapshot;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile SendPhase phase = SendPhase.PREPARING;
        volatile Thread worker;
        volatile boolean finished;
        volatile Long streamSessionId;

        SendJob(long jobId, UUID conversationId, ProviderService provider, List<Message> historySnapshot) {
            this.jobId = jobId;
            this.conversationId = conversationId;
            this.provider = provider;
            this.historySnapshot = List.copyOf(historySnapshot);
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
    }

    private static final class SendCancelledException extends RuntimeException {
    }

    private static final class StreamingSession {
        final long sessionId;
        final UUID conversationId;
        final ProviderService provider;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final StringBuilder response = new StringBuilder();
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
}
