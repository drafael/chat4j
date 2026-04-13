package com.github.drafael.chat4j.chat;

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
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ChatPanel extends JPanel {

    private final JPanel messagesPanel;
    private final JScrollPane scrollPane;
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
    private ModelSelectorPopup modelPopup;

    private final List<Message> history = new ArrayList<>();
    private Map<String, ProviderRegistry.ProviderDef> providerMap = Map.of();
    private String selectedProviderName;
    private String selectedModelId;
    private ProviderService currentProvider;
    private MessageBubble currentAssistantBubble;
    private final AtomicLong streamSessionCounter = new AtomicLong();
    private volatile long activeStreamSessionId = -1L;
    private volatile boolean streaming = false;
    private volatile boolean autoScrollEnabled = true;
    private volatile AtomicBoolean activeStreamCancelled = new AtomicBoolean(false);
    private volatile ProviderService activeStreamingProvider;
    private volatile Thread activeStreamWorker;
    private boolean autoScrollQueued = false;
    private int messageRow = 0;

    public ChatPanel() {
        this(ProviderModelCacheService.createDefault(), ModelFavoritesService.createInMemory());
    }

    public ChatPanel(ProviderModelCacheService modelCacheService) {
        this(modelCacheService, ModelFavoritesService.createInMemory());
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
                if (autoScrollEnabled) {
                    scheduleAutoScroll();
                }
            }
        });

        scrollPane = new JScrollPane(messagesPanel);
        scrollPane.addPropertyChangeListener("UI", e -> SwingUtilities.invokeLater(this::applyScrollPaneStyles));
        applyScrollPaneStyles();
        add(scrollPane, BorderLayout.CENTER);

        // Input bar at bottom
        inputBar = new InputBar();
        inputBar.addSendListener(e -> onSend());
        inputBar.addJumpToLatestListener(e -> onJumpToLatestRequested());
        inputBar.addCancelGenerationListener(e -> cancelStreamingAndMarkCancelled());
        add(inputBar, BorderLayout.SOUTH);

        populateModels();
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
        button.setFont(button.getFont().deriveFont(Font.PLAIN, 11f));
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
        if (streaming) {
            return;
        }

        ComposerState composerState = inputBar.getComposerState();
        if (composerState.isEmpty()) {
            return;
        }

        if (currentProvider == null) {
            inputBar.showValidationMessage("Select a model/provider before sending.");
            return;
        }

        Message userMsg;
        try {
            userMsg = toUserMessage(composerState);
        } catch (IOException e) {
            inputBar.showValidationMessage("Failed to stage attachment: " + e.getMessage());
            return;
        }

        inputBar.clear();
        history.add(userMsg);
        addBubble(new MessageBubble(Role.USER), formatUserBubbleText(userMsg), Role.USER);
        notifyMessageSubmitted();

        currentAssistantBubble = new MessageBubble(Role.ASSISTANT);
        addBubble(currentAssistantBubble, null, Role.ASSISTANT);

        long streamSessionId = beginStreamingSession();

        AtomicBoolean cancellationFlag = activeStreamCancelled;
        ProviderService streamingProvider = currentProvider;
        activeStreamingProvider = streamingProvider;

        activeStreamWorker = Thread.startVirtualThread(() -> {
            streamingProvider.streamCompletion(
                new ArrayList<>(history),
                token -> {
                        if (!isActiveStreamingSession(streamSessionId)) {
                            return;
                        }
                        SwingUtilities.invokeLater(() -> {
                            if (!isActiveStreamingSession(streamSessionId) || currentAssistantBubble == null) {
                                return;
                            }
                            currentAssistantBubble.appendText(token);
                            scrollToBottom();
                        });
                    },
                () -> {
                        if (!isActiveStreamingSession(streamSessionId)) {
                            return;
                        }
                        SwingUtilities.invokeLater(() -> {
                            if (!isActiveStreamingSession(streamSessionId)) {
                                return;
                            }
                            if (currentAssistantBubble != null) {
                                history.add(Message.assistant(currentAssistantBubble.getFullText()));
                            }
                            currentAssistantBubble = null;
                            finishStreamingSession(streamSessionId);
                        });
                    },
                error -> {
                        if (!isActiveStreamingSession(streamSessionId)) {
                            return;
                        }
                        SwingUtilities.invokeLater(() -> {
                            if (!isActiveStreamingSession(streamSessionId)) {
                                return;
                            }
                            if (currentAssistantBubble != null) {
                                currentAssistantBubble.appendText("\n\n[Error: " + error.getMessage() + "]");
                            }
                            currentAssistantBubble = null;
                            finishStreamingSession(streamSessionId);
                        });
                    },
                cancellationFlag::get
            );
        });
    }

    private Message toUserMessage(ComposerState composerState) throws IOException {
        List<ContentPart> parts = new ArrayList<>();

        if (!composerState.activeSkills().isEmpty()) {
            String skillDirective = "Activated skills: " + String.join(", ", composerState.activeSkills());
            parts.add(new TextPart(skillDirective));
        }

        String text = composerState.text().trim();
        if (!text.isEmpty()) {
            parts.add(new TextPart(text));
        }

        for (ComposerAttachment attachment : composerState.attachments()) {
            parts.add(toAttachmentPart(attachment));
        }

        List<String> fallbackNotices = buildFallbackNotices(composerState.attachments());
        MessageMeta meta = new MessageMeta(composerState.activeSkills(), fallbackNotices, false, "");
        return new Message(Role.USER, parts, Instant.now(), meta);
    }

    private ContentPart toAttachmentPart(ComposerAttachment attachment) throws IOException {
        AttachmentRef attachmentRef = attachmentStager.stage(attachment);
        return attachment.image()
                ? new ImagePart(attachmentRef, null, null)
                : new FilePart(attachmentRef);
    }

    private List<String> buildFallbackNotices(List<ComposerAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        List<String> notices = new ArrayList<>();
        boolean hasImage = attachments.stream().anyMatch(ComposerAttachment::image);
        boolean hasFile = attachments.stream().anyMatch(attachment -> !attachment.image());

        ProviderCapabilities capabilities = selectedProviderCapabilities();
        boolean supportsImageInput = ProviderCapabilityResolver.supportsImageInput(
                capabilities,
                selectedProviderName,
                selectedModelId
        );
        boolean supportsFileInput = ProviderCapabilityResolver.supportsFileInput(
                capabilities,
                selectedProviderName,
                selectedModelId
        );

        if (hasImage && !supportsImageInput) {
            notices.add(buildImageFallbackNotice());
        }

        if (hasFile && !supportsFileInput) {
            notices.add(buildFileFallbackNotice(supportsImageInput));
        }

        return notices;
    }

    private String buildImageFallbackNotice() {
        String providerLabel = selectedProviderName == null || selectedProviderName.isBlank()
                ? "Selected provider"
                : selectedProviderName;
        String modelLabel = selectedModelId == null || selectedModelId.isBlank()
                ? "current model"
                : selectedModelId;

        return "%s (%s) is currently mapped to text-only image references.".formatted(providerLabel, modelLabel);
    }

    private String buildFileFallbackNotice(boolean supportsImageInput) {
        String providerLabel = selectedProviderName == null || selectedProviderName.isBlank()
                ? "Selected provider"
                : selectedProviderName;
        String modelLabel = selectedModelId == null || selectedModelId.isBlank()
                ? "current model"
                : selectedModelId;

        if (supportsImageInput) {
            return "%s (%s) supports rich input, but file upload mapping is not enabled yet in Chat4J; sending file references.".formatted(
                    providerLabel,
                    modelLabel
            );
        }

        return "%s (%s) uses text-only fallback for file attachments.".formatted(providerLabel, modelLabel);
    }

    private ProviderCapabilities selectedProviderCapabilities() {
        if (selectedProviderName == null || selectedProviderName.isBlank()) {
            return null;
        }

        ProviderRegistry.ProviderDef providerDef = providerMap.get(selectedProviderName);
        return providerDef == null ? null : providerDef.capabilities();
    }

    private String formatUserBubbleText(Message message) {
        if (message.meta() == null) {
            return message.content();
        }

        List<String> lines = new ArrayList<>();
        if (!message.meta().activeSkills().isEmpty()) {
            lines.add("[SKILL] " + String.join(", ", message.meta().activeSkills()));
        }

        message.meta().fallbackNotices().stream()
                .map(notice -> "[FALLBACK] " + notice)
                .forEach(lines::add);

        String content = message.content();
        if (!content.isBlank()) {
            content.lines()
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .filter(line -> message.meta().activeSkills().isEmpty() || !line.startsWith("Activated skills:"))
                    .forEach(lines::add);
        }

        return lines.isEmpty() ? message.content() : String.join("\n", lines);
    }

    private void addBubble(MessageBubble bubble, String text, Role role) {
        if (role == Role.ASSISTANT) {
            bubble.setAssistantRenderMode(assistantRenderMode);
            assistantBubbles.add(bubble);
        }

        if (text != null) {
            bubble.setText(text);
        }

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);

        if (role == Role.USER) {
            wrapper.setBorder(BorderFactory.createEmptyBorder(2, 120, 2, 0));
        } else {
            wrapper.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 40));
        }
        wrapper.add(bubble, BorderLayout.CENTER);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = messageRow++;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.NORTH;

        messagesPanel.add(wrapper, gbc);
        addBottomFiller();
        messagesPanel.revalidate();
        scrollToBottom();
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
        cancelStreaming();
        history.clear();
        assistantBubbles.clear();
        messagesPanel.removeAll();
        messageRow = 0;
        messagesPanel.revalidate();
        messagesPanel.repaint();
    }

    public List<Message> getHistory() {
        return new ArrayList<>(history);
    }

    public void loadHistory(List<Message> messages) {
        clearChat();
        for (Message msg : messages) {
            history.add(msg);
            MessageBubble bubble = new MessageBubble(msg.role());
            String bubbleText = msg.role() == Role.USER ? formatUserBubbleText(msg) : msg.content();
            addBubble(bubble, bubbleText, msg.role());
        }
    }

    public String getSelectedModel() {
        if (selectedProviderName == null || selectedModelId == null) return null;
        return selectedProviderName + " > " + selectedModelId;
    }

    public void setSelectedModel(String modelKey) {
        if (modelKey == null) return;
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
        activeStreamCancelled.set(true);

        if (markAsCancelled && streaming && currentAssistantBubble != null) {
            currentAssistantBubble.appendText("\n\n[Cancelled]");
        }

        ProviderService provider = activeStreamingProvider;
        if (provider != null) {
            provider.cancelActiveRequest();
        }

        Thread worker = activeStreamWorker;
        if (worker != null) {
            worker.interrupt();
        }

        activeStreamingProvider = null;
        activeStreamWorker = null;
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

    private long beginStreamingSession() {
        long streamSessionId = streamSessionCounter.incrementAndGet();
        activeStreamSessionId = streamSessionId;
        activeStreamCancelled = new AtomicBoolean(false);
        activeStreamWorker = null;
        streaming = true;
        updateGenerationIndicator();
        inputBar.setEnabled(false);
        return streamSessionId;
    }

    private void finishStreamingSession(long streamSessionId) {
        if (!isActiveStreamingSession(streamSessionId)) {
            return;
        }
        activeStreamingProvider = null;
        activeStreamWorker = null;
        autoScrollQueued = false;
        activeStreamSessionId = -1L;
        streaming = false;
        updateGenerationIndicator();
        inputBar.setEnabled(true);
        inputBar.requestInputFocus();
    }

    private boolean isActiveStreamingSession(long streamSessionId) {
        return activeStreamSessionId == streamSessionId;
    }

    private void updateGenerationIndicator() {
        inputBar.setGenerationIndicatorVisible(streaming);
    }

    private static List<String> sanitizeModelIds(String providerName, List<String> modelIds) {
        return ModelOrdering.sanitizeAndSortByProvider(providerName, modelIds);
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
