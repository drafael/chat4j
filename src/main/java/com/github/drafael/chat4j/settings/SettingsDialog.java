package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.webview.WebViewRuntimeStatus;
import com.github.drafael.chat4j.mcp.McpManager;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.prompts.PromptCatalogRepo;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialMutationService;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperNativeRuntime;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import static com.github.drafael.chat4j.util.ModalDialogSupport.showMessageDialog;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Collections.emptyList;

@Slf4j
public class SettingsDialog extends JDialog {

    private static final long APPLICATION_EXIT_SAVE_TIMEOUT_MILLIS = 2_000;
    private static final int SIDEBAR_WIDTH = 230;

    private JPanel titleBarSpacer;
    private JPanel actionBar;
    private JList<SettingsSection> sectionList;
    private List<SettingsSection> sections = emptyList();
    private SettingsSection visibleSection;
    private boolean savingBeforeDispose;
    private boolean exitAfterSave;
    private boolean permanentlyClosed;
    private long closeAttempt;
    private long applicationExitDeadlineNanos;
    private final PropertyChangeListener lafChangeListener;

    private final LongConsumer exitAction;
    private final LongUnaryOperator exitAdmission;
    private final ExitTiming exitTiming;
    private final Path sttModelsDirectory;
    private final VoskModelManagementService voskModelManagementService;
    private final WhisperModelManagementService whisperModelManagementService;
    private final SettingsCredentialChangeListener credentialChangeListener;
    private final CopilotAuthResolver copilotAuthResolver;
    private final CodexAuthResolver codexAuthResolver;
    private final CredentialResolver credentialResolver;
    private final CredentialMutationService credentialMutationService;
    private final Map<String, String> subprocessEnvironment;
    private final WhisperNativeRuntime whisperNativeRuntime;
    private final McpManager mcpManager;

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull PromptCatalogRepo promptCatalogRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull LongConsumer exitAction,
            @NonNull LongUnaryOperator exitAdmission,
            @NonNull Path sttModelsDirectory,
            @NonNull VoskModelManagementService voskModelManagementService,
            @NonNull WhisperModelManagementService whisperModelManagementService,
            @NonNull SettingsCredentialChangeListener credentialChangeListener,
            @NonNull CopilotAuthResolver copilotAuthResolver,
            @NonNull CodexAuthResolver codexAuthResolver,
            @NonNull CredentialResolver credentialResolver,
            @NonNull CredentialMutationService credentialMutationService,
            @NonNull Map<String, String> subprocessEnvironment,
            @NonNull WhisperNativeRuntime whisperNativeRuntime,
            @NonNull McpManager mcpManager,
            @NonNull ExitTiming exitTiming
    ) {
        super(owner, "Settings", true);
        this.exitAction = exitAction;
        this.exitAdmission = exitAdmission;
        this.exitTiming = exitTiming;
        this.sttModelsDirectory = sttModelsDirectory;
        this.voskModelManagementService = voskModelManagementService;
        this.whisperModelManagementService = whisperModelManagementService;
        this.credentialChangeListener = credentialChangeListener;
        this.copilotAuthResolver = copilotAuthResolver;
        this.codexAuthResolver = codexAuthResolver;
        this.credentialResolver = credentialResolver;
        this.credentialMutationService = credentialMutationService;
        this.subprocessEnvironment = Map.copyOf(subprocessEnvironment);
        this.whisperNativeRuntime = whisperNativeRuntime;
        this.mcpManager = mcpManager;

        configureDialog(owner);
        configureMacTitleBarIfNeeded();

        add(createSettingsShell(settingsRepo, promptCatalogRepo, chatWebViewRuntimeStatus), BorderLayout.CENTER);
        add(createActionBar(), BorderLayout.SOUTH);

        lafChangeListener = event -> {
            if ("lookAndFeel".equals(event.getPropertyName())) {
                SwingUtilities.invokeLater(() -> {
                    if (!permanentlyClosed) {
                        applyThemeStyles();
                    }
                });
            }
        };
        UIManager.addPropertyChangeListener(lafChangeListener);

        applyThemeStyles();
        installEscapeCloseAction();
        installLifecycleCleanup();
    }

    private void configureDialog(Frame owner) {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(980, 680);
        setMinimumSize(new Dimension(840, 560));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());
    }

    private void configureMacTitleBarIfNeeded() {
        if (!SystemInfo.isMacOS) {
            return;
        }

        getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
        getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
        getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
        setTitle("");

        titleBarSpacer = new JPanel();
        titleBarSpacer.setPreferredSize(new Dimension(0, 26));
        add(titleBarSpacer, BorderLayout.NORTH);
    }

    private JComponent createSettingsShell(
            SettingsRepository settingsRepo,
            PromptCatalogRepo promptCatalogRepo,
            WebViewRuntimeStatus chatWebViewRuntimeStatus
    ) {
        sections = createSections(settingsRepo, promptCatalogRepo, chatWebViewRuntimeStatus);

        DefaultListModel<SettingsSection> sectionModel = new DefaultListModel<>();
        sections.forEach(sectionModel::addElement);

        sectionList = new JList<>(sectionModel);
        sectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sectionList.setFixedCellHeight(42);
        sectionList.setCellRenderer(new SettingsSectionRenderer());
        sectionList.setBorder(new EmptyBorder(6, 6, 6, 6));
        Color selectionBackground = UIManager.getColor("List.selectionBackground");
        Color selectionForeground = UIManager.getColor("List.selectionForeground");
        if (selectionBackground != null) {
            sectionList.setSelectionBackground(selectionBackground);
        }
        if (selectionForeground != null) {
            sectionList.setSelectionForeground(selectionForeground);
        }

        JScrollPane sidebar = new JScrollPane(sectionList);
        sidebar.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        sidebar.setBorder(BorderFactory.createMatteBorder(
                0,
                0,
                0,
                1,
                UIManager.getColor("Separator.foreground")
        ));

        CardLayout cardsLayout = new CardLayout();
        JPanel cardsPanel = new JPanel(cardsLayout);
        sections.forEach(section -> cardsPanel.add(section.content(), section.id()));

        sectionList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }

            SettingsSection selected = sectionList.getSelectedValue();
            if (selected != null) {
                if (visibleSection != null && visibleSection.content() instanceof McpPanel mcpPanel
                        && visibleSection != selected) {
                    mcpPanel.finishActiveEditing();
                }
                cardsLayout.show(cardsPanel, selected.id());
                visibleSection = selected;
            }
        });
        sectionList.setSelectedIndex(0);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, cardsPanel);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerSize(1);
        splitPane.setDividerLocation(SIDEBAR_WIDTH);
        splitPane.setResizeWeight(0);
        splitPane.setBorder(null);

        return splitPane;
    }

    private List<SettingsSection> createSections(
            SettingsRepository settingsRepo,
            PromptCatalogRepo promptCatalogRepo,
            WebViewRuntimeStatus chatWebViewRuntimeStatus
    ) {
        ApiTokenFieldRegistry tokenFieldRegistry = new ApiTokenFieldRegistry();
        List<SettingsSection> created = new ArrayList<>(List.of(
                new SettingsSection(
                        "general",
                        "General",
                        "/icons/sidebar/settings.svg",
                        new GeneralPanel(settingsRepo, this::requestApplicationExit)
                ),
                new SettingsSection(
                        "appearance",
                        "Appearance",
                        "/icons/settings/palette.svg",
                        new AppearancePanel(settingsRepo, chatWebViewRuntimeStatus, this::requestApplicationExit)
                ),
                new SettingsSection(
                        "providers",
                        "Providers",
                        "/icons/settings/cpu.svg",
                        new ProvidersPanel(
                                settingsRepo,
                                tokenFieldRegistry,
                                credentialResolver,
                                credentialMutationService,
                                credentialChangeListener,
                                copilotAuthResolver,
                                codexAuthResolver
                        )
                ),
                new SettingsSection("tts", "Text to Speech", "/icons/chat/volume-2.svg", new TextToSpeechPanel(
                        settingsRepo,
                        credentialResolver,
                        credentialMutationService,
                        subprocessEnvironment,
                        tokenFieldRegistry,
                        credentialChangeListener
                )),
                new SettingsSection("stt", "Speech to Text", "/icons/chat/mic.svg", new SpeechToTextPanel(
                        settingsRepo,
                        sttModelsDirectory,
                        voskModelManagementService,
                        whisperModelManagementService,
                        credentialResolver,
                        credentialMutationService,
                        whisperNativeRuntime,
                        tokenFieldRegistry,
                        credentialChangeListener
                )),
                new SettingsSection(
                        "pdf-export",
                        "PDF Export",
                        "/icons/settings/file-text.svg",
                        new PdfExportPanel(settingsRepo)
                ),
                new SettingsSection(
                        "prompts",
                        "Prompts",
                        "/icons/settings/message-square-text.svg",
                        new PromptsPanel(promptCatalogRepo)
                ),
                new SettingsSection(
                        "agent-mode",
                        "Agent Mode",
                        "/icons/input/agent.svg",
                        new AgentModePanel(settingsRepo)
                )
        ));
        created.add(new SettingsSection("mcp", "MCP", "/icons/settings/mcp.svg", new McpPanel(mcpManager)));
        return List.copyOf(created);
    }

    private JComponent createActionBar() {
        actionBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        actionBar.add(closeButton);

        return actionBar;
    }

    private void installEscapeCloseAction() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put(
                "close",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        dispose();
                    }
                }
        );
    }

    private void installLifecycleCleanup() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                boolean cleanupRequired = !permanentlyClosed;
                permanentlyClosed = true;
                closeAttempt++;
                try {
                    if (cleanupRequired) {
                        disposeSections();
                    }
                } finally {
                    UIManager.removePropertyChangeListener(lafChangeListener);
                }
            }
        });
    }

    @Override
    public void dispose() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::dispose);
            return;
        }
        if (savingBeforeDispose || permanentlyClosed) {
            return;
        }
        exitAfterSave = false;
        startSaveBeforeDispose();
    }

    private void requestApplicationExit() {
        requestApplicationExit(admitSettingsOriginatedExit(exitTiming.nanoTime(), exitAdmission));
    }

    static long admitSettingsOriginatedExit(LongSupplier nanoTime, LongUnaryOperator exitAdmission) {
        long settingsDeadline = nanoTime.getAsLong()
                + TimeUnit.MILLISECONDS.toNanos(APPLICATION_EXIT_SAVE_TIMEOUT_MILLIS);
        return exitAdmission.applyAsLong(settingsDeadline);
    }

    static boolean abortCloseAfterSaveFailure(boolean exitAfterSave, boolean saved) {
        return !saved && !exitAfterSave;
    }

    void requestApplicationExit(long deadlineNanos) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> requestApplicationExit(deadlineNanos));
            return;
        }
        if (permanentlyClosed) {
            return;
        }
        boolean newExitAttempt = !exitAfterSave;
        if (newExitAttempt) {
            applicationExitDeadlineNanos = deadlineNanos;
        }
        exitAfterSave = true;
        if (!savingBeforeDispose) {
            startSaveBeforeDispose();
        }
        if (newExitAttempt) {
            long settingsDeadlineNanos = min(
                    applicationExitDeadlineNanos,
                    exitTiming.nanoTime().getAsLong()
                            + TimeUnit.MILLISECONDS.toNanos(APPLICATION_EXIT_SAVE_TIMEOUT_MILLIS)
            );
            scheduleApplicationExitTimeout(closeAttempt, settingsDeadlineNanos);
        }
    }

    private void startSaveBeforeDispose() {
        savingBeforeDispose = true;
        long attempt = ++closeAttempt;
        setEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        savePendingPanelChangesResultAsync(attempt)
                .exceptionally(error -> new SavePendingResult(false, saveErrorMessage(error), "Settings"))
                .thenAccept(saveResult -> SwingUtilities.invokeLater(() -> finishDisposeAfterSave(attempt, saveResult)));
    }

    private void scheduleApplicationExitTimeout(long attempt, long deadlineNanos) {
        long remainingNanos = max(0, deadlineNanos - exitTiming.nanoTime().getAsLong());
        exitTiming.scheduler().schedule(
                remainingNanos,
                () -> SwingUtilities.invokeLater(() -> finishApplicationExitAfterTimeout(attempt))
        );
    }

    private void finishApplicationExitAfterTimeout(long attempt) {
        if (permanentlyClosed || !exitAfterSave || attempt != closeAttempt) {
            return;
        }
        exitAfterSave = false;
        savingBeforeDispose = false;
        permanentlyClosed = true;
        closeAttempt++;
        try {
            disposeSections();
        } finally {
            try {
                exitAction.accept(applicationExitDeadlineNanos);
            } finally {
                SettingsDialog.super.dispose();
            }
        }
    }

    private void finishDisposeAfterSave(long attempt, SavePendingResult saveResult) {
        if (permanentlyClosed || attempt != closeAttempt) {
            return;
        }
        setCursor(Cursor.getDefaultCursor());
        savingBeforeDispose = false;
        if (!saveResult.saved()) {
            setEnabled(true);
            if (abortCloseAfterSaveFailure(exitAfterSave, saveResult.saved())) {
                showMessageDialog(
                        this,
                        "%s could not be saved:\n\n%s".formatted(saveResult.sectionName(), saveResult.message()),
                        JOptionPane.ERROR_MESSAGE
                );
                return;
            }
            log.warn("{} could not be saved during application exit; continuing exit", saveResult.sectionName());
        }
        boolean shouldExit = exitAfterSave;
        exitAfterSave = false;
        permanentlyClosed = true;
        try {
            disposeSections();
        } finally {
            try {
                if (shouldExit) {
                    exitAction.accept(applicationExitDeadlineNanos);
                }
            } finally {
                SettingsDialog.super.dispose();
            }
        }
    }

    private void disposeSections() {
        Throwable failure = null;
        for (SettingsSection section : sections) {
            try {
                disposeSection(section.content());
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                } else {
                    failure.addSuppressed(t);
                }
            }
        }
        if (failure instanceof RuntimeException e) {
            throw e;
        }
        if (failure instanceof Error e) {
            throw e;
        }
        if (failure != null) {
            throw new IllegalStateException("Failed to dispose Settings sections", failure);
        }
    }

    private void disposeSection(JComponent content) {
        if (content instanceof GeneralPanel panel) {
            panel.disposePanel();
        } else if (content instanceof AgentModePanel panel) {
            panel.disposePanel();
        } else if (content instanceof AppearancePanel panel) {
            panel.disposePanel();
        } else if (content instanceof PdfExportPanel panel) {
            panel.disposePanel();
        } else if (content instanceof McpPanel panel) {
            panel.disposePanel();
        } else if (content instanceof PromptsPanel panel) {
            panel.disposePanel();
        } else if (content instanceof SpeechToTextPanel panel) {
            panel.disposePanel();
            panel.disposeSettingsPanel();
        } else if (content instanceof AbstractSettingsPanel panel) {
            panel.disposeSettingsPanel();
        }
    }

    private CompletableFuture<SavePendingResult> savePendingPanelChangesResultAsync(long attempt) {
        List<AsyncPendingSettingsSaveParticipant> participants = sections.stream()
                .map(SettingsSection::content)
                .filter(AsyncPendingSettingsSaveParticipant.class::isInstance)
                .map(AsyncPendingSettingsSaveParticipant.class::cast)
                .toList();
        return saveParticipants(participants, () -> attempt == closeAttempt && !permanentlyClosed);
    }

    static CompletableFuture<SavePendingResult> saveParticipants(
            List<AsyncPendingSettingsSaveParticipant> participants,
            BooleanSupplier active
    ) {
        var orderedParticipants = new ArrayList<>(participants);
        orderedParticipants.stream()
                .filter(McpPanel.class::isInstance)
                .findFirst()
                .ifPresent(mcp -> {
                    orderedParticipants.remove(mcp);
                    orderedParticipants.addFirst(mcp);
                });
        return saveParticipantAt(orderedParticipants, 0, active);
    }

    private static CompletableFuture<SavePendingResult> saveParticipantAt(
            List<AsyncPendingSettingsSaveParticipant> participants,
            int index,
            BooleanSupplier active
    ) {
        if (index >= participants.size()) {
            return CompletableFuture.completedFuture(new SavePendingResult(true, "", "Settings"));
        }
        AsyncPendingSettingsSaveParticipant participant = participants.get(index);
        CompletableFuture<Boolean> saved = runOnEdt(() -> active.getAsBoolean()
                        ? participant.savePendingChangesAsync()
                        : null)
                .thenCompose(save -> save == null
                        ? CompletableFuture.completedFuture(false)
                        : save);
        return saved.handle(SaveCompletion::new)
                .thenCompose(completion -> runOnEdt(() -> {
                    if (!active.getAsBoolean()) {
                        return CompletableFuture.completedFuture(new SavePendingResult(true, "", "Settings"));
                    }
                    if (completion.error() != null) {
                        return CompletableFuture.completedFuture(new SavePendingResult(
                                false,
                                saveErrorMessage(completion.error()),
                                participant.settingsSectionName()
                        ));
                    }
                    if (Boolean.TRUE.equals(completion.saved())) {
                        return saveParticipantAt(participants, index + 1, active);
                    }
                    return CompletableFuture.completedFuture(new SavePendingResult(
                            false,
                            participant.lastSaveError(),
                            participant.settingsSectionName()
                    ));
                }))
                .thenCompose(Function.identity());
    }

    private static String saveErrorMessage(Throwable error) {
        Throwable unwrapped = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        String message = unwrapped.getMessage();
        return StringUtils.isBlank(message)
                ? unwrapped.getClass().getSimpleName()
                : message;
    }

    private static <T> CompletableFuture<T> runOnEdt(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                result.complete(action.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
                if (t instanceof Error error && !(error instanceof LinkageError)) {
                    throw error;
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
        return result;
    }

    private void applyThemeStyles() {
        Color panelBackground = UIManager.getColor("Panel.background");
        if (panelBackground == null) {
            panelBackground = getBackground();
        }

        getContentPane().setBackground(panelBackground);
        if (titleBarSpacer != null) {
            titleBarSpacer.setOpaque(true);
            titleBarSpacer.setBackground(panelBackground);
        }
        if (actionBar != null) {
            actionBar.setOpaque(true);
            actionBar.setBackground(panelBackground);
        }
    }

    private static final class SettingsSectionRenderer extends DefaultListCellRenderer {

        private static final int ICON_SIZE = 20;

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SettingsSection section) {
                label.setText(section.title());
                label.setIcon(loadSectionIcon(section.iconPath(), label.getForeground()));
            }
            if (isSelected) {
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
                label.setIcon(value instanceof SettingsSection section
                        ? loadSectionIcon(section.iconPath(), list.getSelectionForeground())
                        : null);
                label.setOpaque(true);
            }
            label.setIconTextGap(12);
            label.setBorder(new EmptyBorder(6, 12, 6, 12));
            return label;
        }

        private Icon loadSectionIcon(String iconPath, Color color) {
            URL url = SettingsDialog.class.getResource(iconPath);
            if (url == null) {
                return null;
            }
            FlatSVGIcon icon = new FlatSVGIcon(url).derive(ICON_SIZE, ICON_SIZE);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, original) -> color));
            return icon;
        }
    }

    public record ExitTiming(@NonNull LongSupplier nanoTime, @NonNull DelayedTaskScheduler scheduler) {
        public static ExitTiming system() {
            return new ExitTiming(
                    System::nanoTime,
                    (delayNanos, task) -> CompletableFuture.delayedExecutor(
                            delayNanos,
                            TimeUnit.NANOSECONDS,
                            Runnable::run
                    ).execute(task)
            );
        }
    }

    @FunctionalInterface
    public interface DelayedTaskScheduler {
        void schedule(long delayNanos, @NonNull Runnable task);
    }

    private record SettingsSection(String id, String title, String iconPath, JComponent content) {
    }

    private record SaveCompletion(Boolean saved, Throwable error) {
    }

    record SavePendingResult(boolean saved, String message, String sectionName) {
    }
}
