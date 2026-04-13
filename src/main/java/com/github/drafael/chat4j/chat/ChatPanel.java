package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import com.github.drafael.chat4j.storage.ProviderModelCacheService;
import javax.swing.*;
import java.awt.*;
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

    private final JcefChatView chatView;
    private final InputBar inputBar;
    private final ModelSelectorButton modelSelectorBtn;
    private final ProviderModelCacheService modelCacheService;
    private final ModelFavoritesService modelFavoritesService;
    private final JToggleButton previewToggle = new JToggleButton(AssistantRenderMode.PREVIEW.displayName());
    private final JToggleButton markdownToggle = new JToggleButton(AssistantRenderMode.MARKDOWN.displayName());
    private AssistantRenderMode assistantRenderMode = AssistantRenderMode.PREVIEW;
    private MarkdownRenderOptions markdownRenderOptions = MarkdownRenderOptions.defaults();
    private Consumer<AssistantRenderMode> assistantRenderModeChangedListener;
    private Consumer<String> selectedModelChangedListener;
    private Runnable modelFavoritesChangedListener;
    private Runnable modelCatalogChangedListener;
    private ModelSelectorPopup modelPopup;

    private final List<Message> history = new ArrayList<>();
    private Map<String, ProviderRegistry.ProviderDef> providerMap = Map.of();
    private String selectedProviderName;
    private String selectedModelId;
    private ProviderService currentProvider;
    private final AtomicLong streamSessionCounter = new AtomicLong();
    private volatile long activeStreamSessionId = -1L;
    private volatile boolean streaming = false;
    private volatile boolean autoScrollEnabled = true;
    private volatile AtomicBoolean activeStreamCancelled = new AtomicBoolean(false);
    private volatile ProviderService activeStreamingProvider;
    private volatile Thread activeStreamWorker;

    private long messageIdCounter = 0;
    private String currentAssistantMessageId;
    private final StringBuilder currentAssistantText = new StringBuilder();

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

        chatView = new JcefChatView();
        add(chatView, BorderLayout.CENTER);

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
        if (chatView != null) {
            chatView.setTheme(ChatTheme.fromCurrentLookAndFeel());
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SwingUtilities.invokeLater(() -> {
            preloadModelPopup();
            chatView.setTheme(ChatTheme.fromCurrentLookAndFeel());
            chatView.setRenderMode(assistantRenderMode);
            chatView.setMathOptions(markdownRenderOptions);
        });
    }

    @Override
    public void removeNotify() {
        if (modelPopup != null) {
            modelPopup.dispose();
            modelPopup = null;
        }
        super.removeNotify();
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

    private String nextMessageId() {
        return "msg-" + (++messageIdCounter);
    }

    private void onSend() {
        String text = inputBar.getText();
        if (text.isEmpty() || streaming) return;

        Message userMsg = Message.user(text);
        history.add(userMsg);

        String userMsgId = nextMessageId();
        chatView.addMessage(userMsgId, "user", text, false);
        if (autoScrollEnabled) {
            chatView.scrollToBottom();
        }

        inputBar.clear();

        if (currentProvider == null) {
            String errorId = nextMessageId();
            chatView.addMessage(errorId, "assistant",
                    "No provider selected or configured. Set API key environment variables.", false);
            if (autoScrollEnabled) {
                chatView.scrollToBottom();
            }
            return;
        }

        currentAssistantMessageId = nextMessageId();
        currentAssistantText.setLength(0);
        chatView.addMessage(currentAssistantMessageId, "assistant", "", true);

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
                            if (!isActiveStreamingSession(streamSessionId) || currentAssistantMessageId == null) {
                                return;
                            }
                            currentAssistantText.append(token);
                            chatView.updateMessage(currentAssistantMessageId, currentAssistantText.toString());
                            if (autoScrollEnabled) {
                                chatView.scrollToBottom();
                            }
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
                            if (currentAssistantMessageId != null) {
                                chatView.finishMessage(currentAssistantMessageId);
                                history.add(Message.assistant(currentAssistantText.toString()));
                            }
                            currentAssistantMessageId = null;
                            currentAssistantText.setLength(0);
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
                            if (currentAssistantMessageId != null) {
                                currentAssistantText.append("\n\n[Error: ").append(error.getMessage()).append("]");
                                chatView.updateMessage(currentAssistantMessageId, currentAssistantText.toString());
                                chatView.finishMessage(currentAssistantMessageId);
                            }
                            currentAssistantMessageId = null;
                            currentAssistantText.setLength(0);
                            finishStreamingSession(streamSessionId);
                        });
                    },
                cancellationFlag::get
            );
        });
    }

    private void onJumpToLatestRequested() {
        chatView.scrollToBottom();
    }

    public void clearChat() {
        cancelStreaming();
        history.clear();
        messageIdCounter = 0;
        currentAssistantMessageId = null;
        currentAssistantText.setLength(0);
        chatView.clearAll();
    }

    public List<Message> getHistory() {
        return new ArrayList<>(history);
    }

    public void loadHistory(List<Message> messages) {
        clearChat();
        for (Message msg : messages) {
            history.add(msg);
            String id = nextMessageId();
            chatView.addMessage(id, msg.role().name().toLowerCase(), msg.content(), false);
        }
        if (autoScrollEnabled) {
            chatView.scrollToBottom();
        }
        chatView.invalidateLayout();
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

    public void setAssistantRenderMode(AssistantRenderMode mode, boolean rerender) {
        if (mode == null || assistantRenderMode == mode) {
            updateRenderModeToggleSelection();
            return;
        }

        assistantRenderMode = mode;
        updateRenderModeToggleSelection();

        if (rerender) {
            chatView.setRenderMode(mode);
        }

        if (assistantRenderModeChangedListener != null) {
            assistantRenderModeChangedListener.accept(mode);
        }
    }

    public MarkdownRenderOptions getMarkdownRenderOptions() {
        return markdownRenderOptions;
    }

    public void setMarkdownRenderOptions(MarkdownRenderOptions options, boolean rerender) {
        if (options == null || markdownRenderOptions.equals(options)) {
            return;
        }

        markdownRenderOptions = options;
        if (rerender) {
            chatView.setMathOptions(options);
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

        if (markAsCancelled && streaming && currentAssistantMessageId != null) {
            currentAssistantText.append("\n\n[Cancelled]");
            chatView.updateMessage(currentAssistantMessageId, currentAssistantText.toString());
            chatView.finishMessage(currentAssistantMessageId);
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
        activeStreamSessionId = -1L;
        streaming = false;
        currentAssistantMessageId = null;
        currentAssistantText.setLength(0);
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
}
