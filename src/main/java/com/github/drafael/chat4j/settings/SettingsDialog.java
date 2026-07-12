package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.webview.WebViewRuntimeStatus;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public class SettingsDialog extends JDialog {

    private static final int SIDEBAR_WIDTH = 230;

    private JPanel titleBarSpacer;
    private JPanel actionBar;
    private JList<SettingsSection> sectionList;
    private List<SettingsSection> sections = emptyList();
    private boolean savingBeforeDispose;
    private final PropertyChangeListener lafChangeListener;

    private final Runnable exitAction;
    private final Path sttModelsDirectory;
    private final VoskModelManagementService voskModelManagementService;
    private final WhisperModelManagementService whisperModelManagementService;
    private final boolean ownsVoskModelManagementService;
    private final boolean ownsWhisperModelManagementService;
    private final SettingsCredentialChangeListener credentialChangeListener;

    public SettingsDialog(@NonNull Frame owner, @NonNull SettingsRepository settingsRepo) {
        this(owner, settingsRepo, WebViewRuntimeStatus.jEditorPaneDefault(), () -> System.exit(0));
    }

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus
    ) {
        this(owner, settingsRepo, chatWebViewRuntimeStatus, () -> System.exit(0));
    }

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull Runnable exitAction
    ) {
        this(owner, settingsRepo, chatWebViewRuntimeStatus, exitAction, StoragePaths.defaultPaths().sttModelsDirectory(), StoragePaths.defaultPaths().sttTempDirectory());
    }

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull Runnable exitAction,
            @NonNull Path sttModelsDirectory
    ) {
        this(owner, settingsRepo, chatWebViewRuntimeStatus, exitAction, sttModelsDirectory, StoragePaths.defaultPaths().sttTempDirectory());
    }

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull Runnable exitAction,
            @NonNull Path sttModelsDirectory,
            @NonNull Path sttTempDirectory
    ) {
        this(owner, settingsRepo, chatWebViewRuntimeStatus, exitAction, sttModelsDirectory,
                new VoskModelManagementService(settingsRepo, sttModelsDirectory, sttTempDirectory),
                new WhisperModelManagementService(settingsRepo, sttModelsDirectory, sttTempDirectory), true, true);
    }

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull Runnable exitAction,
            @NonNull Path sttModelsDirectory,
            @NonNull VoskModelManagementService voskModelManagementService
    ) {
        this(owner, settingsRepo, chatWebViewRuntimeStatus, exitAction, sttModelsDirectory, voskModelManagementService,
                new WhisperModelManagementService(settingsRepo, sttModelsDirectory, StoragePaths.defaultPaths().sttTempDirectory()), false, true);
    }

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull Runnable exitAction,
            @NonNull Path sttModelsDirectory,
            @NonNull VoskModelManagementService voskModelManagementService,
            @NonNull WhisperModelManagementService whisperModelManagementService
    ) {
        this(owner, settingsRepo, chatWebViewRuntimeStatus, exitAction, sttModelsDirectory, voskModelManagementService, whisperModelManagementService, SettingsCredentialChangeListener.NO_OP);
    }

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull Runnable exitAction,
            @NonNull Path sttModelsDirectory,
            @NonNull VoskModelManagementService voskModelManagementService,
            @NonNull WhisperModelManagementService whisperModelManagementService,
            @NonNull SettingsCredentialChangeListener credentialChangeListener
    ) {
        this(owner, settingsRepo, chatWebViewRuntimeStatus, exitAction, sttModelsDirectory, voskModelManagementService, whisperModelManagementService, false, false, credentialChangeListener);
    }

    private SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull Runnable exitAction,
            @NonNull Path sttModelsDirectory,
            @NonNull VoskModelManagementService voskModelManagementService,
            @NonNull WhisperModelManagementService whisperModelManagementService,
            boolean ownsVoskModelManagementService,
            boolean ownsWhisperModelManagementService
    ) {
        this(owner, settingsRepo, chatWebViewRuntimeStatus, exitAction, sttModelsDirectory, voskModelManagementService, whisperModelManagementService,
                ownsVoskModelManagementService, ownsWhisperModelManagementService, SettingsCredentialChangeListener.NO_OP);
    }

    private SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepository settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull Runnable exitAction,
            @NonNull Path sttModelsDirectory,
            @NonNull VoskModelManagementService voskModelManagementService,
            @NonNull WhisperModelManagementService whisperModelManagementService,
            boolean ownsVoskModelManagementService,
            boolean ownsWhisperModelManagementService,
            @NonNull SettingsCredentialChangeListener credentialChangeListener
    ) {
        super(owner, "Settings", true);
        this.exitAction = exitAction;
        this.sttModelsDirectory = sttModelsDirectory;
        this.voskModelManagementService = voskModelManagementService;
        this.whisperModelManagementService = whisperModelManagementService;
        this.ownsVoskModelManagementService = ownsVoskModelManagementService;
        this.ownsWhisperModelManagementService = ownsWhisperModelManagementService;
        this.credentialChangeListener = credentialChangeListener;

        configureDialog(owner);
        configureMacTitleBarIfNeeded();

        add(createSettingsShell(settingsRepo, chatWebViewRuntimeStatus), BorderLayout.CENTER);
        add(createActionBar(), BorderLayout.SOUTH);

        lafChangeListener = event -> {
            if ("lookAndFeel".equals(event.getPropertyName())) {
                SwingUtilities.invokeLater(this::applyThemeStyles);
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

    private JComponent createSettingsShell(SettingsRepository settingsRepo, WebViewRuntimeStatus chatWebViewRuntimeStatus) {
        sections = createSections(settingsRepo, chatWebViewRuntimeStatus);

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
                cardsLayout.show(cardsPanel, selected.id());
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

    private List<SettingsSection> createSections(SettingsRepository settingsRepo, WebViewRuntimeStatus chatWebViewRuntimeStatus) {
        ApiTokenFieldRegistry tokenFieldRegistry = new ApiTokenFieldRegistry();
        return List.of(
                new SettingsSection("general", "General", "/icons/sidebar/settings.svg", new GeneralPanel(settingsRepo, exitAction)),
                new SettingsSection("appearance", "Appearance", "/icons/settings/palette.svg", new AppearancePanel(settingsRepo, chatWebViewRuntimeStatus, exitAction)),
                new SettingsSection("providers", "Providers", "/icons/settings/cpu.svg", new ProvidersPanel(settingsRepo, tokenFieldRegistry, credentialChangeListener)),
                new SettingsSection("tts", "Text to Speech", "/icons/chat/volume-2.svg", new TextToSpeechPanel(settingsRepo, tokenFieldRegistry, credentialChangeListener)),
                new SettingsSection("stt", "Speech to Text", "/icons/chat/mic.svg", new SpeechToTextPanel(settingsRepo, sttModelsDirectory, voskModelManagementService, whisperModelManagementService, tokenFieldRegistry, credentialChangeListener)),
                new SettingsSection("prompts", "Prompts", "/icons/settings/book-open.svg", new PromptsPanel(settingsRepo))
        );
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
                UIManager.removePropertyChangeListener(lafChangeListener);
                closeOwnedModelManagementServices();
            }
        });
    }

    private void closeOwnedModelManagementServices() {
        if (!ownsVoskModelManagementService && !ownsWhisperModelManagementService) {
            return;
        }
        Runnable closeServices = () -> {
            if (ownsVoskModelManagementService) {
                voskModelManagementService.close();
            }
            if (ownsWhisperModelManagementService) {
                whisperModelManagementService.close();
            }
        };
        if (EventQueue.isDispatchThread()) {
            Thread.ofVirtual().name("chat4j-settings-stt-model-services-close-").start(closeServices);
            return;
        }
        closeServices.run();
    }

    @Override
    public void dispose() {
        if (savingBeforeDispose) {
            return;
        }
        savingBeforeDispose = true;
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        savePendingPanelChangesResultAsync()
                .exceptionally(error -> new SavePendingResult(false, saveErrorMessage(error), "Settings"))
                .thenAccept(saveResult -> SwingUtilities.invokeLater(() -> finishDisposeAfterSave(saveResult)));
    }

    private void finishDisposeAfterSave(SavePendingResult saveResult) {
        setCursor(Cursor.getDefaultCursor());
        savingBeforeDispose = false;
        if (!saveResult.saved()) {
            JOptionPane.showMessageDialog(
                    this,
                    "%s could not be saved:\n\n%s".formatted(saveResult.sectionName(), saveResult.message()),
                    "Settings Not Saved",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }
        SettingsDialog.super.dispose();
    }

    private CompletableFuture<SavePendingResult> savePendingPanelChangesResultAsync() {
        List<PendingSettingsSaveParticipant> participants = sections.stream()
                .map(SettingsSection::content)
                .filter(PendingSettingsSaveParticipant.class::isInstance)
                .map(PendingSettingsSaveParticipant.class::cast)
                .toList();
        return saveParticipantAt(participants, 0);
    }

    private CompletableFuture<SavePendingResult> saveParticipantAt(List<PendingSettingsSaveParticipant> participants, int index) {
        if (index >= participants.size()) {
            return CompletableFuture.completedFuture(new SavePendingResult(true, "", "Settings"));
        }
        PendingSettingsSaveParticipant participant = participants.get(index);
        CompletableFuture<Boolean> saved = runOnEdt(() -> participant instanceof AsyncPendingSettingsSaveParticipant asyncParticipant
                ? asyncParticipant.savePendingChangesAsync()
                : CompletableFuture.completedFuture(participant.savePendingChanges()))
                .thenCompose(Function.identity());
        return saved.handle((success, error) -> {
                    if (error != null) {
                        return CompletableFuture.completedFuture(
                                new SavePendingResult(false, saveErrorMessage(error), participant.settingsSectionName())
                        );
                    }
                    if (success) {
                        return saveParticipantAt(participants, index + 1);
                    }
                    return runOnEdt(() -> CompletableFuture.completedFuture(
                            new SavePendingResult(false, participant.lastSaveError(), participant.settingsSectionName())
                    )).thenCompose(Function.identity());
                })
                .thenCompose(Function.identity());
    }

    private String saveErrorMessage(Throwable error) {
        Throwable unwrapped = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        String message = unwrapped.getMessage();
        return StringUtils.isBlank(message)
                ? unwrapped.getClass().getSimpleName()
                : message;
    }

    private <T> CompletableFuture<T> runOnEdt(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                result.complete(action.call());
            } catch (Exception e) {
                result.completeExceptionally(e);
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

    private record SettingsSection(String id, String title, String iconPath, JComponent content) {
    }

    private record SavePendingResult(boolean saved, String message, String sectionName) {
    }
}
