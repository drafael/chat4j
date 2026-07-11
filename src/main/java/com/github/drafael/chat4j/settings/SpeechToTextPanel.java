package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.SpeechToTextProviderRegistry;
import com.github.drafael.chat4j.stt.SpeechToTextSettings;
import com.github.drafael.chat4j.stt.SpeechToTextSettingsSnapshot;
import com.github.drafael.chat4j.stt.model.UnavailableSpeechToTextModelDownloader;
import com.github.drafael.chat4j.stt.model.SpeechToTextModelDescriptor;
import com.github.drafael.chat4j.stt.model.SpeechToTextModelDownloader;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogStore;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.vosk.VoskLocalModelRow;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementSnapshot;
import com.github.drafael.chat4j.stt.provider.vosk.VoskSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperLocalModelRow;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementSnapshot;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextProvider;
import com.github.drafael.chat4j.util.Fonts;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.util.Collections.emptyList;

public class SpeechToTextPanel extends AbstractSettingsPanel implements PendingSettingsSaveParticipant {

    private static final int FIELD_WIDTH = 520;
    private static final int LOCAL_MODELS_WIDTH = FIELD_WIDTH;
    private static final int LOCAL_MODELS_TABLE_HEIGHT = 230;
    private static final int LOCAL_MODELS_ROW_GAP = 6;
    private static final int LOCAL_MODELS_COLUMN_GAP = 12;
    private static final String VOSK_OPERATION_IN_PROGRESS_ERROR = "A Vosk model operation is still in progress.";
    private static final String WHISPER_OPERATION_IN_PROGRESS_ERROR = "A Whisper.cpp model operation is still in progress.";

    private final SpeechToTextProviderRegistry providerRegistry;
    private final SpeechToTextSettings settings;
    private final SpeechToTextCatalogStore catalogStore;
    private final SpeechToTextModelDownloader modelDownloader;
    private final VoskModelManagementService voskModelManagementService;
    private final WhisperModelManagementService whisperModelManagementService;
    private final boolean ownsVoskModelManagementService;
    private final boolean ownsWhisperModelManagementService;
    private long refreshCounter;
    private final Object refreshLock = new Object();
    private final AtomicLong saveCounter = new AtomicLong();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat4j-stt-settings-save-", 0).factory()
    );

    private JComboBox<ProviderOption> providerComboBox;
    private JComboBox<SpeechToTextCatalogItem> modelComboBox;
    private JTextField modelDirectoryField;
    private JButton modelDirectoryBrowseButton;
    private JPanel localModelsPanel;
    private JTable localModelsTable;
    private DefaultTableModel localModelsTableModel;
    private JButton downloadModelButton;
    private JButton deleteModelButton;
    private JButton importModelButton;
    private JLabel localModelsHintLabel;
    private JSpinner maxDurationSpinner;
    private JButton refreshButton;
    private JLabel helperLabel;
    private boolean updating;
    private boolean updatingLocalModels;
    private List<SpeechToTextCatalogItem> localModelItems = emptyList();
    private List<VoskLocalModelRow> voskRows = emptyList();
    private List<WhisperLocalModelRow> whisperRows = emptyList();
    private boolean voskOperationInProgress;
    private boolean whisperOperationInProgress;
    private String voskOperationSuccessMessage = "";
    private String whisperOperationSuccessMessage = "";
    private Runnable voskUnsubscribe = () -> {
    };
    private Runnable whisperUnsubscribe = () -> {
    };
    private final List<CompletableFuture<Boolean>> pendingSaves = new CopyOnWriteArrayList<>();
    private volatile boolean removed;
    private volatile String lastSaveError = "";

    public SpeechToTextPanel(SettingsRepository settingsRepo, Path defaultModelDirectory) {
        this(settingsRepo, defaultModelDirectory, SpeechToTextProviderRegistry.createDefault(),
                new UnavailableSpeechToTextModelDownloader(),
                new VoskModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                new WhisperModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                true,
                true);
    }

    public SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            VoskModelManagementService voskModelManagementService
    ) {
        this(settingsRepo, defaultModelDirectory, SpeechToTextProviderRegistry.createDefault(),
                new UnavailableSpeechToTextModelDownloader(), voskModelManagementService,
                new WhisperModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")), false, true);
    }

    public SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            VoskModelManagementService voskModelManagementService,
            WhisperModelManagementService whisperModelManagementService
    ) {
        this(settingsRepo, defaultModelDirectory, SpeechToTextProviderRegistry.createDefault(),
                new UnavailableSpeechToTextModelDownloader(), voskModelManagementService, whisperModelManagementService, false, false);
    }

    SpeechToTextPanel(SettingsRepository settingsRepo, Path defaultModelDirectory, SpeechToTextProviderRegistry providerRegistry) {
        this(settingsRepo, defaultModelDirectory, providerRegistry, new UnavailableSpeechToTextModelDownloader(),
                new VoskModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                new WhisperModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")), true, true);
    }

    SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            VoskModelManagementService voskModelManagementService,
            WhisperModelManagementService whisperModelManagementService
    ) {
        this(settingsRepo, defaultModelDirectory, providerRegistry, new UnavailableSpeechToTextModelDownloader(), voskModelManagementService, whisperModelManagementService, false, false);
    }

    SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader
    ) {
        this(settingsRepo, defaultModelDirectory, providerRegistry, modelDownloader,
                new VoskModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                new WhisperModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")), true, true);
    }

    SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader,
            VoskModelManagementService voskModelManagementService
    ) {
        this(settingsRepo, defaultModelDirectory, providerRegistry, modelDownloader, voskModelManagementService,
                new WhisperModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")), false, true);
    }

    private SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader,
            VoskModelManagementService voskModelManagementService,
            WhisperModelManagementService whisperModelManagementService,
            boolean ownsVoskModelManagementService,
            boolean ownsWhisperModelManagementService
    ) {
        super(settingsRepo);
        this.providerRegistry = providerRegistry;
        this.voskModelManagementService = voskModelManagementService;
        this.whisperModelManagementService = whisperModelManagementService;
        this.ownsVoskModelManagementService = ownsVoskModelManagementService;
        this.ownsWhisperModelManagementService = ownsWhisperModelManagementService;
        this.settings = new SpeechToTextSettings(settingsRepo, providerRegistry, CredentialSource.SYSTEM, defaultModelDirectory, voskModelManagementService, whisperModelManagementService);
        this.catalogStore = new SpeechToTextCatalogStore(settingsRepo);
        this.modelDownloader = modelDownloader;
        buildUi();
        voskUnsubscribe = voskModelManagementService.addListener(snapshot -> runWhenActiveOnEventDispatchThread(() -> handleVoskModelSnapshot(snapshot)));
        whisperUnsubscribe = whisperModelManagementService.addListener(snapshot -> runWhenActiveOnEventDispatchThread(() -> handleWhisperModelSnapshot(snapshot)));
    }

    @Override
    public boolean savePendingChanges() {
        VoskModelManagementSnapshot voskSnapshot = voskModelManagementService.snapshot();
        if (voskModelOperationBlocksClose(voskSnapshot)) {
            lastSaveError = VOSK_OPERATION_IN_PROGRESS_ERROR;
            return false;
        }
        WhisperModelManagementSnapshot whisperSnapshot = whisperModelManagementService.snapshot();
        if (whisperSnapshot.operationInProgress() && !"refresh".equals(whisperSnapshot.operationType())) {
            lastSaveError = WHISPER_OPERATION_IN_PROGRESS_ERROR;
            return false;
        }
        clearResolvedOperationError();
        try {
            boolean saved = true;
            for (CompletableFuture<Boolean> pendingSave : List.copyOf(pendingSaves)) {
                saved = pendingSave.get() && saved;
            }
            return saved && StringUtils.isBlank(lastSaveError);
        } catch (Exception e) {
            lastSaveError = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean voskModelOperationBlocksClose(VoskModelManagementSnapshot snapshot) {
        return snapshot.operationInProgress()
                && !Strings.CS.equalsAny(
                        snapshot.operationStatus(),
                        VoskModelManagementService.SCAN_OPERATION_STATUS,
                        VoskModelManagementService.CATALOG_REFRESH_OPERATION_STATUS
                );
    }

    private void clearResolvedOperationError() {
        if (Strings.CS.equalsAny(lastSaveError, VOSK_OPERATION_IN_PROGRESS_ERROR, WHISPER_OPERATION_IN_PROGRESS_ERROR)) {
            lastSaveError = "";
        }
    }

    @Override
    public String lastSaveError() {
        return lastSaveError;
    }

    @Override
    public String settingsSectionName() {
        return "Speech to Text settings";
    }

    @Override
    public void removeNotify() {
        removed = true;
        cancelCatalogRefreshes();
        voskUnsubscribe.run();
        whisperUnsubscribe.run();
        saveExecutor.shutdown();
        closeOwnedModelManagementServices();
        super.removeNotify();
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
            Thread.ofVirtual().name("chat4j-stt-model-services-close-").start(closeServices);
            return;
        }
        closeServices.run();
    }

    private void buildUi() {
        JPanel form = createFormPanel("Speech to Text");
        GridBagConstraints gbc = createFormConstraints();
        int row = 0;

        providerComboBox = withPreferredWidth(new JComboBox<>(), FIELD_WIDTH);
        providerComboBox.setRenderer(new ProviderOptionRenderer());
        providerComboBox.addActionListener(e -> onProviderSelected());
        addRow(form, gbc, row++, "Provider", providerComboBox);

        modelComboBox = withPreferredWidth(new JComboBox<>(), FIELD_WIDTH);
        modelComboBox.setRenderer(new CatalogItemRenderer());
        modelComboBox.addActionListener(e -> onModelSelected());
        addRow(form, gbc, row++, "Model", modelComboBox);

        modelDirectoryField = new JTextField();

        maxDurationSpinner = new JSpinner(new SpinnerNumberModel(SpeechToTextSettings.DEFAULT_MAX_DURATION_SECONDS, 1, 600, 1));
        maxDurationSpinner.addChangeListener(e -> onMaxDurationChanged());
        addRow(form, gbc, row++, "Max recording seconds", withPreferredWidth(maxDurationSpinner, FIELD_WIDTH));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        refreshButton = new JButton("Refresh catalogs");
        refreshButton.addActionListener(e -> refreshCatalogs(true));
        buttons.add(refreshButton);
        row = addFullWidthRow(form, gbc, row, buttons);

        helperLabel = new JLabel(" ");
        Fonts.apply(helperLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        helperLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        row = addFullWidthRow(form, gbc, row, helperLabel);

        localModelsPanel = createLocalModelsPanel();
        row = addLocalModelsRow(form, gbc, row);
        addVerticalSpacer(form, gbc, row);

        reloadProviderOptions();
        refreshControlsFromSettings(true);
    }

    private int addLocalModelsRow(JPanel form, GridBagConstraints gbc, int row) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(LOCAL_MODELS_ROW_GAP, 0, LOCAL_MODELS_ROW_GAP, 0);
        form.add(localModelsPanel, gbc);

        gbc.gridwidth = 1;
        gbc.insets = new Insets(LOCAL_MODELS_ROW_GAP, 0, LOCAL_MODELS_ROW_GAP, LOCAL_MODELS_COLUMN_GAP);
        return row + 1;
    }

    private JPanel createLocalModelsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.setVisible(false);

        JPanel directoryPanel = new JPanel(new BorderLayout(8, 0));
        directoryPanel.setOpaque(false);
        JLabel directoryLabel = new JLabel("Local models folder");
        directoryLabel.setPreferredSize(new Dimension(138, directoryLabel.getPreferredSize().height));
        modelDirectoryBrowseButton = new JButton("Browse…");
        modelDirectoryBrowseButton.addActionListener(e -> browseModelDirectory());
        modelDirectoryField.addActionListener(e -> saveModelDirectory());
        directoryPanel.add(directoryLabel, BorderLayout.WEST);
        directoryPanel.add(modelDirectoryField, BorderLayout.CENTER);
        directoryPanel.add(modelDirectoryBrowseButton, BorderLayout.EAST);
        panel.add(withPreferredWidth(directoryPanel, LOCAL_MODELS_WIDTH), BorderLayout.NORTH);

        localModelsTableModel = new DefaultTableModel(new Object[] {"Model", "Selected"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                String name = getColumnName(columnIndex);
                boolean booleanColumn = "Installed".equals(name) || "Selected".equals(name);
                return booleanColumn ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                SpeechToTextSettingsSnapshot snapshot = settings.resolve();
                if (isVosk(snapshot)) {
                    return column == 5 && !voskModelManagementService.snapshot().operationInProgress();
                }
                if (isWhisper(snapshot)) {
                    return column == 4 && !whisperModelManagementService.snapshot().operationInProgress();
                }
                return column == 1;
            }
        };
        localModelsTable = new JTable(localModelsTableModel);
        localModelsTable.setAutoCreateRowSorter(false);
        localModelsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        localModelsTable.setFillsViewportHeight(true);
        localModelsTable.setRowHeight(Math.max(localModelsTable.getRowHeight(), 24));
        configureGenericLocalModelColumns();
        localModelsTableModel.addTableModelListener(event -> {
            if (updatingLocalModels || event.getFirstRow() < 0) {
                return;
            }
            SpeechToTextSettingsSnapshot snapshot = settings.resolve();
            int selectedColumn = localSelectedColumn(snapshot);
            if (event.getColumn() != selectedColumn) {
                return;
            }
            if (Boolean.TRUE.equals(localModelsTableModel.getValueAt(event.getFirstRow(), selectedColumn))) {
                selectLocalModel(event.getFirstRow());
            } else {
                refreshLocalModelSelection(snapshot);
            }
        });
        localModelsTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateLocalModelButtons(settings.resolve());
            }
        });

        JScrollPane modelsScrollPane = new JScrollPane(localModelsTable);
        modelsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        modelsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        modelsScrollPane.setPreferredSize(new Dimension(LOCAL_MODELS_WIDTH, LOCAL_MODELS_TABLE_HEIGHT));
        panel.add(modelsScrollPane, BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout(0, 4));
        actions.setOpaque(false);
        JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionButtons.setOpaque(false);
        downloadModelButton = new JButton("Download");
        downloadModelButton.setToolTipText("Download the selected Vosk catalog model");
        downloadModelButton.getAccessibleContext().setAccessibleName("Download selected speech model");
        downloadModelButton.addActionListener(e -> downloadSelectedLocalModel());
        deleteModelButton = new JButton("Delete");
        deleteModelButton.setToolTipText("Delete the selected managed local speech model");
        deleteModelButton.getAccessibleContext().setAccessibleName("Delete selected speech model");
        deleteModelButton.addActionListener(e -> deleteSelectedLocalModel());
        importModelButton = new JButton("Add Folder…");
        importModelButton.setToolTipText("Copy an existing unzipped Vosk model into Chat4J's managed model folder");
        importModelButton.getAccessibleContext().setAccessibleName("Add existing Vosk model folder");
        importModelButton.addActionListener(e -> importExistingVoskModel());
        actionButtons.add(downloadModelButton);
        actionButtons.add(deleteModelButton);
        actionButtons.add(importModelButton);
        actions.add(actionButtons, BorderLayout.NORTH);
        localModelsHintLabel = new JLabel(" ");
        Fonts.apply(localModelsHintLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        localModelsHintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        actions.add(localModelsHintLabel, BorderLayout.SOUTH);
        panel.add(actions, BorderLayout.SOUTH);

        return panel;
    }

    private void handleVoskModelSnapshot(VoskModelManagementSnapshot snapshot) {
        if (removed) {
            return;
        }
        boolean completed = voskOperationInProgress && !snapshot.operationInProgress();
        voskOperationInProgress = snapshot.operationInProgress();
        if (localModelsPanel != null && isShowing()) {
            refreshControlsFromSettings(false);
        }
        if (completed) {
            applyCompletedVoskOperationStatus(snapshot);
        }
    }

    private void applyCompletedVoskOperationStatus(VoskModelManagementSnapshot snapshot) {
        if (StringUtils.isNotBlank(snapshot.operationStatus())) {
            setStatusError(snapshot.operationStatus());
        } else if (StringUtils.isNotBlank(voskOperationSuccessMessage)) {
            setStatusInfo(voskOperationSuccessMessage);
        }
        voskOperationSuccessMessage = "";
    }

    private void handleWhisperModelSnapshot(WhisperModelManagementSnapshot snapshot) {
        if (removed) {
            return;
        }
        boolean completed = whisperOperationInProgress && !snapshot.operationInProgress();
        whisperOperationInProgress = snapshot.operationInProgress();
        if (localModelsPanel != null && isShowing()) {
            refreshControlsFromSettings(false);
        }
        if (completed) {
            applyCompletedWhisperOperationStatus(snapshot);
        }
    }

    private void applyCompletedWhisperOperationStatus(WhisperModelManagementSnapshot snapshot) {
        if (StringUtils.isNotBlank(snapshot.operationStatus())) {
            setStatusError(snapshot.operationStatus());
        } else if (StringUtils.isNotBlank(whisperOperationSuccessMessage)) {
            setStatusInfo(whisperOperationSuccessMessage);
        }
        whisperOperationSuccessMessage = "";
    }

    private void reloadProviderOptions() {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        DefaultComboBoxModel<ProviderOption> model = new DefaultComboBoxModel<>();
        model.addElement(ProviderOption.off());
        providerRegistry.providers().stream()
                .map(provider -> ProviderOption.of(provider, provider.available(CredentialSource.SYSTEM)))
                .forEach(model::addElement);
        updating = true;
        providerComboBox.setModel(model);
        providerComboBox.setSelectedItem(findProviderOption(snapshot.providerId()));
        updating = false;
    }

    private void refreshControlsFromSettings(boolean refreshCatalogs) {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        List<SpeechToTextCatalogItem> models = emptyList();
        updating = true;
        try {
            modelDirectoryField.setText(snapshot.modelDirectory().toString());
            maxDurationSpinner.setValue(snapshot.maxDurationSeconds());
            if (!snapshot.enabled()) {
                updateModelCombo(emptyList());
            } else if (isVosk(snapshot)) {
                models = voskInstalledCatalogItems();
                updateModelCombo(models);
                selectCatalogItem(snapshot.model());
            } else if (isWhisper(snapshot)) {
                models = whisperInstalledCatalogItems();
                updateModelCombo(models);
                selectCatalogItem(snapshot.model());
            } else {
                models = catalogStore.models(snapshot.provider(), snapshot.model());
                updateModelCombo(models);
                selectCatalogItem(snapshot.model());
            }
        } finally {
            updating = false;
        }
        updateLocalModels(snapshot, models);
        updateAvailability(snapshot);
        if (refreshCatalogs && isVosk(snapshot) && !voskModelManagementService.snapshot().operationInProgress()) {
            voskModelManagementService.refreshAsync();
            return;
        }
        if (refreshCatalogs && isWhisper(snapshot) && !whisperModelManagementService.snapshot().operationInProgress()) {
            whisperModelManagementService.refreshAsync(true);
            return;
        }
        if (refreshCatalogs && snapshot.enabled() && !isVosk(snapshot) && !isWhisper(snapshot) && snapshot.available() && catalogStore.stale(snapshot.providerId())) {
            refreshCatalogs(false);
        }
    }

    private void onProviderSelected() {
        if (updating) {
            return;
        }
        Object selected = providerComboBox.getSelectedItem();
        if (!(selected instanceof ProviderOption option)) {
            return;
        }
        scheduleSave(() -> {
            settings.saveProvider(option.providerId());
            refreshLocalProvider(option.providerId());
        }, () -> {
            reloadProviderOptions();
            refreshControlsFromSettings(!localProvider(option.providerId()));
            setStatusInfo(STATUS_SAVED);
        });
    }

    private void refreshLocalProvider(String providerId) {
        try {
            if (VoskSpeechToTextProvider.ID.equals(providerId)) {
                voskModelManagementService.refreshAsync();
            } else if (WhisperSpeechToTextProvider.ID.equals(providerId)) {
                whisperModelManagementService.refreshAsync(true);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private boolean localProvider(String providerId) {
        return VoskSpeechToTextProvider.ID.equals(providerId)
                || WhisperSpeechToTextProvider.ID.equals(providerId);
    }

    private void onModelSelected() {
        if (updating) {
            return;
        }
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        Object selected = modelComboBox.getSelectedItem();
        if (snapshot.enabled() && selected instanceof SpeechToTextCatalogItem item) {
            if (isVosk(snapshot)) {
                selectVoskModel(item.id(), item.label());
                return;
            }
            if (isWhisper(snapshot)) {
                selectWhisperModel(item.id(), item.label());
                return;
            }
            scheduleSave(() -> settings.saveModel(snapshot.providerId(), item), () -> {
                refreshLocalModelSelection(settings.resolve());
                setStatusInfo(STATUS_SAVED);
            });
        }
    }

    private void onMaxDurationChanged() {
        if (updating) {
            return;
        }
        int value = ((Number) maxDurationSpinner.getValue()).intValue();
        try {
            SpeechToTextSettings.validateMaxDurationSeconds(value);
        } catch (IllegalArgumentException e) {
            setStatusError(e.getMessage());
            return;
        }
        scheduleSave(() -> settings.saveMaxDurationSeconds(value), () -> setStatusInfo(STATUS_SAVED));
    }

    private void browseModelDirectory() {
        if (localOperationBlocksModelDirectoryChange()) {
            return;
        }
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle("Select Speech to Text models folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        String currentPath = modelDirectoryField.getText();
        if (StringUtils.isNotBlank(currentPath)) {
            try {
                File currentDirectory = Path.of(currentPath).toFile();
                if (currentDirectory.isDirectory()) {
                    chooser.setCurrentDirectory(currentDirectory);
                    chooser.setSelectedFile(currentDirectory);
                }
            } catch (Exception ignored) {
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            modelDirectoryField.setText(chooser.getSelectedFile().toPath().toString());
            saveModelDirectory();
        }
    }

    private void saveModelDirectory() {
        if (localOperationBlocksModelDirectoryChange()) {
            return;
        }
        String rawPath = modelDirectoryField.getText();
        AtomicReference<Path> savedPath = new AtomicReference<>();
        scheduleSave(() -> savedPath.set(settings.saveModelDirectory(rawPath)), () -> {
            Path path = savedPath.get();
            if (path != null) {
                modelDirectoryField.setText(path.toString());
                localModelsHintLabel.setText("Stored under %s".formatted(compactPath(path)));
                voskModelManagementService.refreshAsync();
                whisperModelManagementService.refreshAsync(isWhisper(settings.resolve()));
            }
            setStatusInfo(STATUS_SAVED);
        });
    }

    private boolean localOperationBlocksModelDirectoryChange() {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (voskModelManagementService.snapshot().operationInProgress()) {
            modelDirectoryField.setText(snapshot.modelDirectory().toString());
            setStatusError("Finish the current Vosk model operation before changing the models folder.");
            return true;
        }
        if (whisperModelManagementService.snapshot().operationInProgress()) {
            modelDirectoryField.setText(snapshot.modelDirectory().toString());
            setStatusError("Finish the current Whisper.cpp model operation before changing the models folder.");
            return true;
        }
        return false;
    }

    private void refreshCatalogs(boolean explicit) {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (!snapshot.enabled()) {
            updateAvailability(snapshot);
            return;
        }
        if (isVosk(snapshot)) {
            if (explicit) {
                voskOperationSuccessMessage = "Vosk model catalog refreshed.";
                setStatusInfo("Refreshing Vosk model catalog...");
            }
            try {
                voskModelManagementService.refreshCatalogAsync();
            } catch (Exception e) {
                voskOperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not refresh Vosk model catalog."));
            }
            return;
        }
        if (isWhisper(snapshot)) {
            if (explicit) {
                whisperOperationSuccessMessage = "Whisper.cpp models refreshed.";
                setStatusInfo("Refreshing Whisper.cpp models...");
            }
            try {
                whisperModelManagementService.refreshAsync(true);
            } catch (Exception e) {
                whisperOperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not refresh Whisper.cpp models."));
            }
            return;
        }
        if (!snapshot.available()) {
            updateAvailability(snapshot);
            return;
        }
        long refreshId = nextCatalogRefreshId();
        if (explicit) {
            setStatusInfo("Refreshing Speech to Text catalogs...");
        }
        Thread.startVirtualThread(() -> {
            try {
                SpeechToTextProviderContext context = new SpeechToTextProviderContext(
                        snapshot.baseUri(),
                        snapshot.transcriptionUri(),
                        CredentialSource.SYSTEM,
                        () -> !catalogRefreshCurrent(refreshId),
                        Duration.ofSeconds(45)
                );
                List<SpeechToTextCatalogItem> models = snapshot.provider().fetchModels(context);
                if (!saveCatalogModelsIfCurrent(refreshId, snapshot.providerId(), models)) {
                    return;
                }
                runWhenActiveOnEventDispatchThread(() -> applyCatalogRefresh(refreshId, snapshot, models, explicit));
            } catch (Exception e) {
                runWhenActiveOnEventDispatchThread(() -> {
                    if (catalogRefreshCurrent(refreshId) && explicit) {
                        setStatusError("Could not refresh %s Speech to Text models.".formatted(snapshot.provider().displayName()));
                    }
                });
            }
        });
    }

    private long nextCatalogRefreshId() {
        synchronized (refreshLock) {
            return ++refreshCounter;
        }
    }

    private void cancelCatalogRefreshes() {
        synchronized (refreshLock) {
            refreshCounter++;
        }
    }

    private boolean catalogRefreshCurrent(long refreshId) {
        synchronized (refreshLock) {
            return refreshId == refreshCounter;
        }
    }

    private boolean saveCatalogModelsIfCurrent(long refreshId, String providerId, List<SpeechToTextCatalogItem> models) throws Exception {
        synchronized (refreshLock) {
            if (refreshId != refreshCounter) {
                return false;
            }
            catalogStore.saveModels(providerId, models);
            return true;
        }
    }

    private void applyCatalogRefresh(long refreshId, SpeechToTextSettingsSnapshot previous, List<SpeechToTextCatalogItem> models, boolean explicit) {
        if (!catalogRefreshCurrent(refreshId)) {
            return;
        }
        SpeechToTextSettingsSnapshot current = settings.resolve();
        if (!Objects.equals(current.providerId(), previous.providerId())) {
            return;
        }
        updating = true;
        List<SpeechToTextCatalogItem> currentModels;
        try {
            currentModels = catalogStore.mergeWithSelected(models, current.provider().bundledModels(), current.model());
            updateModelCombo(currentModels);
            selectCatalogItem(current.model());
        } finally {
            updating = false;
        }
        updateLocalModels(current, currentModels);
        if (explicit) {
            setStatusInfo("Speech to Text catalogs refreshed");
        }
        updateAvailability(current);
    }

    private void updateModelCombo(List<SpeechToTextCatalogItem> models) {
        DefaultComboBoxModel<SpeechToTextCatalogItem> model = new DefaultComboBoxModel<>();
        models.forEach(model::addElement);
        modelComboBox.setModel(model);
    }

    private void selectCatalogItem(SpeechToTextCatalogItem selected) {
        if (selected == null) {
            return;
        }
        for (int i = 0; i < modelComboBox.getItemCount(); i++) {
            if (Objects.equals(modelComboBox.getItemAt(i).id(), selected.id())) {
                modelComboBox.setSelectedIndex(i);
                return;
            }
        }
    }

    private void updateLocalModels(SpeechToTextSettingsSnapshot snapshot, List<SpeechToTextCatalogItem> models) {
        boolean visible = localModelsVisible(snapshot);
        localModelsPanel.setVisible(visible);
        updateImportModelButton(snapshot);
        updateModelDirectoryControls(snapshot);
        if (!visible) {
            localModelItems = emptyList();
            voskRows = emptyList();
            whisperRows = emptyList();
            updateLocalModelRows(snapshot, emptyList());
            updateLocalModelButtons(snapshot);
            return;
        }
        if (isVosk(snapshot)) {
            updateVoskLocalModels(snapshot);
            return;
        }
        if (isWhisper(snapshot)) {
            updateWhisperLocalModels(snapshot);
            return;
        }

        localModelItems = List.copyOf(models == null ? emptyList() : models);
        updateLocalModelRows(snapshot, localModelItems);
        updateLocalModelButtons(snapshot);
        Path directory = settings.modelDirectory().resolve();
        localModelsHintLabel.setText("Stored under %s".formatted(compactPath(directory)));
    }

    private void updateModelDirectoryControls(SpeechToTextSettingsSnapshot snapshot) {
        boolean enabled = !voskModelManagementService.snapshot().operationInProgress() && !whisperModelManagementService.snapshot().operationInProgress();
        modelDirectoryField.setEnabled(enabled);
        if (modelDirectoryBrowseButton != null) {
            modelDirectoryBrowseButton.setEnabled(enabled);
        }
    }

    private void updateVoskLocalModels(SpeechToTextSettingsSnapshot snapshot) {
        VoskModelManagementSnapshot voskSnapshot = voskModelManagementService.snapshot();
        voskRows = List.copyOf(voskSnapshot.rows());
        updatingLocalModels = true;
        try {
            localModelsTableModel.setColumnIdentifiers(new Object[] {"Model", "Language", "Type", "Size", "Installed", "Selected", "Status"});
            resetLocalModelsSorter();
            configureVoskLocalModelColumns();
            localModelsTableModel.setRowCount(0);
            voskRows.forEach(row -> localModelsTableModel.addRow(new Object[] {
                    row.label(),
                    row.language(),
                    row.type(),
                    row.sizeLabel(),
                    row.installed(),
                    row.selected(),
                    row.actionStatus()
            }));
        } finally {
            updatingLocalModels = false;
        }
        localModelsHintLabel.setText(voskSnapshot.operationInProgress()
                ? voskSnapshot.operationStatus()
                : "Vosk root: %s".formatted(compactPath(voskSnapshot.modelRoot())));
        updateLocalModelButtons(snapshot);
    }

    private void updateWhisperLocalModels(SpeechToTextSettingsSnapshot snapshot) {
        WhisperModelManagementSnapshot whisperSnapshot = whisperModelManagementService.snapshot();
        whisperRows = List.copyOf(whisperSnapshot.rows());
        updatingLocalModels = true;
        try {
            localModelsTableModel.setColumnIdentifiers(new Object[] {"Model", "Type/Language", "Size", "Installed", "Selected", "Status"});
            resetLocalModelsSorter();
            configureWhisperLocalModelColumns();
            localModelsTableModel.setRowCount(0);
            whisperRows.forEach(row -> localModelsTableModel.addRow(new Object[] {
                    row.label(),
                    row.typeLanguage(),
                    row.sizeLabel(),
                    row.installed(),
                    row.selected(),
                    row.actionStatus()
            }));
        } finally {
            updatingLocalModels = false;
        }
        localModelsHintLabel.setText(whisperSnapshot.operationInProgress()
                ? operationStatus(whisperSnapshot)
                : "Whisper.cpp root: %s".formatted(compactPath(whisperSnapshot.modelRoot())));
        updateLocalModelButtons(snapshot);
    }

    private String operationStatus(WhisperModelManagementSnapshot snapshot) {
        if (snapshot.totalBytes() <= 0) {
            return snapshot.operationStatus();
        }
        long percent = snapshot.bytesDownloaded() <= 0 ? 0 : Math.min(100, snapshot.bytesDownloaded() * 100 / snapshot.totalBytes());
        return "%s (%d%%)".formatted(StringUtils.defaultIfBlank(snapshot.operationStatus(), "Downloading Whisper.cpp model..."), percent);
    }

    private void updateLocalModelRows(SpeechToTextSettingsSnapshot snapshot, List<SpeechToTextCatalogItem> models) {
        updatingLocalModels = true;
        try {
            localModelsTableModel.setColumnIdentifiers(new Object[] {"Model", "Selected"});
            resetLocalModelsSorter();
            configureGenericLocalModelColumns();
            localModelsTableModel.setRowCount(0);
            models.forEach(item -> localModelsTableModel.addRow(new Object[] {
                    item.label(),
                    snapshot.model() != null && Objects.equals(item.id(), snapshot.model().id())
            }));
        } finally {
            updatingLocalModels = false;
        }
    }

    private void resetLocalModelsSorter() {
        if (localModelsTable != null) {
            localModelsTable.setRowSorter(null);
        }
    }

    private void configureGenericLocalModelColumns() {
        if (localModelsTable == null || localModelsTable.getColumnModel().getColumnCount() < 2) {
            return;
        }
        localModelsTable.getColumnModel().getColumn(0).setPreferredWidth(LOCAL_MODELS_WIDTH - 92);
        localModelsTable.getColumnModel().getColumn(1).setPreferredWidth(86);
        localModelsTable.getColumnModel().getColumn(1).setMaxWidth(96);
    }

    private void configureVoskLocalModelColumns() {
        if (localModelsTable == null || localModelsTable.getColumnModel().getColumnCount() < 7) {
            return;
        }
        int[] widths = {260, 140, 88, 96, 82, 82, 260};
        for (int column = 0; column < widths.length; column++) {
            localModelsTable.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }
        localModelsTable.getColumnModel().getColumn(4).setMaxWidth(96);
        localModelsTable.getColumnModel().getColumn(5).setMaxWidth(96);
    }

    private void configureWhisperLocalModelColumns() {
        if (localModelsTable == null || localModelsTable.getColumnModel().getColumnCount() < 6) {
            return;
        }
        int[] widths = {260, 150, 96, 82, 82, 320};
        for (int column = 0; column < widths.length; column++) {
            localModelsTable.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }
        localModelsTable.getColumnModel().getColumn(3).setMaxWidth(96);
        localModelsTable.getColumnModel().getColumn(4).setMaxWidth(96);
    }

    private void refreshLocalModelSelection(SpeechToTextSettingsSnapshot snapshot) {
        if (!localModelsVisible(snapshot)) {
            return;
        }
        if (isVosk(snapshot)) {
            updateVoskLocalModels(snapshot);
            return;
        }
        if (isWhisper(snapshot)) {
            updateWhisperLocalModels(snapshot);
            return;
        }
        updatingLocalModels = true;
        try {
            for (int row = 0; row < localModelItems.size(); row++) {
                SpeechToTextCatalogItem item = localModelItems.get(row);
                localModelsTableModel.setValueAt(snapshot.model() != null && Objects.equals(item.id(), snapshot.model().id()), row, 1);
            }
        } finally {
            updatingLocalModels = false;
        }
        updateLocalModelButtons(snapshot);
    }

    private void selectVoskModel(String modelId, String label) {
        try {
            voskOperationSuccessMessage = STATUS_SAVED;
            setStatusInfo("Selecting %s...".formatted(StringUtils.defaultIfBlank(label, "Vosk model")));
            voskModelManagementService.selectModelAsync(modelId);
            refreshControlsFromSettings(false);
        } catch (Exception e) {
            voskOperationSuccessMessage = "";
            refreshControlsFromSettings(false);
            setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not select Vosk model."));
        }
    }

    private void selectWhisperModel(String modelId, String label) {
        try {
            whisperOperationSuccessMessage = STATUS_SAVED;
            setStatusInfo("Selecting %s...".formatted(StringUtils.defaultIfBlank(label, "Whisper.cpp model")));
            whisperModelManagementService.selectModelAsync(modelId);
            refreshControlsFromSettings(false);
        } catch (Exception e) {
            whisperOperationSuccessMessage = "";
            refreshControlsFromSettings(false);
            setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not select Whisper.cpp model."));
        }
    }

    private void selectLocalModel(int row) {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (!localModelsVisible(snapshot)) {
            return;
        }
        if (isVosk(snapshot)) {
            VoskLocalModelRow voskRow = voskRowAt(row);
            if (voskRow == null || !voskRow.selectable()) {
                refreshLocalModelSelection(snapshot);
                return;
            }
            selectVoskModel(voskRow.id(), voskRow.label());
            return;
        }
        if (isWhisper(snapshot)) {
            WhisperLocalModelRow whisperRow = whisperRowAt(row);
            if (whisperRow == null || !whisperRow.selectable()) {
                refreshLocalModelSelection(snapshot);
                return;
            }
            selectWhisperModel(whisperRow.id(), whisperRow.label());
            return;
        }
        if (row < 0 || row >= localModelItems.size()) {
            return;
        }
        SpeechToTextCatalogItem item = localModelItems.get(row);
        scheduleSave(() -> settings.saveModel(snapshot.providerId(), item), () -> {
            selectCatalogItem(item);
            refreshLocalModelSelection(settings.resolve());
            setStatusInfo(STATUS_SAVED);
        });
    }

    private void updateImportModelButton(SpeechToTextSettingsSnapshot snapshot) {
        boolean vosk = isVosk(snapshot);
        importModelButton.setVisible(vosk);
        importModelButton.setToolTipText(vosk ? "Copy an existing unzipped Vosk model into Chat4J's managed model folder" : null);
        importModelButton.getAccessibleContext().setAccessibleName(vosk ? "Add existing Vosk model folder" : "");
    }

    private void updateLocalModelButtons(SpeechToTextSettingsSnapshot snapshot) {
        updateImportModelButton(snapshot);
        if (isVosk(snapshot)) {
            VoskModelManagementSnapshot voskSnapshot = voskModelManagementService.snapshot();
            VoskLocalModelRow selected = selectedVoskRow();
            boolean idle = !voskSnapshot.operationInProgress();
            downloadModelButton.setText("Download");
            downloadModelButton.setEnabled(idle && selected != null && selected.downloadable());
            deleteModelButton.setEnabled(idle && selected != null && selected.deleteable());
            importModelButton.setEnabled(idle);
            return;
        }
        if (isWhisper(snapshot)) {
            WhisperModelManagementSnapshot whisperSnapshot = whisperModelManagementService.snapshot();
            WhisperLocalModelRow selected = selectedWhisperRow();
            boolean idle = !whisperSnapshot.operationInProgress();
            downloadModelButton.setText(idle ? "Download" : "Cancel");
            downloadModelButton.setEnabled((idle && selected != null && selected.downloadable()) || (!idle && whisperSnapshot.cancelable()));
            deleteModelButton.setEnabled(idle && selected != null && selected.deleteable());
            importModelButton.setEnabled(false);
            return;
        }
        SpeechToTextCatalogItem selected = selectedLocalModel();
        boolean enabled = localModelsVisible(snapshot) && selected != null;
        downloadModelButton.setEnabled(enabled && snapshot.available());
        deleteModelButton.setEnabled(enabled);
        importModelButton.setEnabled(false);
    }

    private SpeechToTextCatalogItem selectedLocalModel() {
        int row = localModelsTable == null ? -1 : localModelsTable.getSelectedRow();
        if (row >= 0) {
            int modelRow = localModelsTable.convertRowIndexToModel(row);
            if (modelRow >= 0 && modelRow < localModelItems.size()) {
                return localModelItems.get(modelRow);
            }
        }
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (snapshot.model() == null) {
            return null;
        }
        return localModelItems.stream()
                .filter(item -> Objects.equals(item.id(), snapshot.model().id()))
                .findFirst()
                .orElse(null);
    }

    private void downloadSelectedLocalModel() {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (isVosk(snapshot)) {
            VoskLocalModelRow selected = selectedVoskRow();
            if (selected == null || !selected.downloadable()) {
                return;
            }
            voskOperationSuccessMessage = "Downloaded %s".formatted(selected.label());
            setStatusInfo("Downloading %s...".formatted(selected.label()));
            try {
                voskModelManagementService.downloadAsync(selected.id());
            } catch (Exception e) {
                voskOperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not download Vosk model."));
            }
            return;
        }
        if (isWhisper(snapshot)) {
            WhisperModelManagementSnapshot whisperSnapshot = whisperModelManagementService.snapshot();
            if (whisperSnapshot.operationInProgress()) {
                whisperModelManagementService.cancelActiveOperation();
                setStatusInfo("Canceling Whisper.cpp model operation...");
                return;
            }
            WhisperLocalModelRow selected = selectedWhisperRow();
            if (selected == null || !selected.downloadable()) {
                return;
            }
            if (!confirmLargeWhisperDownload(selected)) {
                return;
            }
            whisperOperationSuccessMessage = "Downloaded %s".formatted(selected.label());
            setStatusInfo("Downloading %s...".formatted(selected.label()));
            try {
                whisperModelManagementService.downloadAsync(selected.id());
            } catch (Exception e) {
                whisperOperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not download Whisper.cpp model."));
            }
            return;
        }
        SpeechToTextCatalogItem selected = selectedLocalModel();
        if (!localModelsVisible(snapshot) || selected == null) {
            return;
        }
        Path directory = settings.modelDirectory().directoryFor(snapshot.providerId(), selected.id());
        setStatusInfo("Downloading %s...".formatted(selected.label()));
        Thread.startVirtualThread(() -> {
            try {
                modelDownloader.download(new SpeechToTextModelDescriptor(snapshot.providerId(), selected.id(), directory));
                runWhenActiveOnEventDispatchThread(() -> setStatusInfo("%s downloaded".formatted(selected.label())));
            } catch (Exception e) {
                runWhenActiveOnEventDispatchThread(() -> setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not download model.")));
            }
        });
    }

    private void deleteSelectedLocalModel() {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (isVosk(snapshot)) {
            VoskLocalModelRow selected = selectedVoskRow();
            if (selected == null || !selected.deleteable()) {
                return;
            }
            voskOperationSuccessMessage = "Deleted %s".formatted(selected.label());
            try {
                voskModelManagementService.deleteAsync(selected.id());
            } catch (Exception e) {
                voskOperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not delete Vosk model."));
            }
            return;
        }
        if (isWhisper(snapshot)) {
            WhisperLocalModelRow selected = selectedWhisperRow();
            if (selected == null || !selected.deleteable()) {
                return;
            }
            whisperOperationSuccessMessage = "Deleted %s".formatted(selected.label());
            try {
                whisperModelManagementService.deleteAsync(selected.id());
            } catch (Exception e) {
                whisperOperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not delete Whisper.cpp model."));
            }
            return;
        }
        SpeechToTextCatalogItem selected = selectedLocalModel();
        if (!localModelsVisible(snapshot) || selected == null) {
            return;
        }
        Path directory = settings.modelDirectory().directoryFor(snapshot.providerId(), selected.id());
        Thread.startVirtualThread(() -> {
            try {
                deleteDirectory(directory);
                runWhenActiveOnEventDispatchThread(() -> setStatusInfo("Deleted local files for %s".formatted(selected.label())));
            } catch (Exception e) {
                runWhenActiveOnEventDispatchThread(() -> setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not delete local model.")));
            }
        });
    }

    private void importExistingVoskModel() {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (!isVosk(snapshot)) {
            return;
        }
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle("Select existing Vosk model folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            try {
                voskOperationSuccessMessage = "Imported %s".formatted(chooser.getSelectedFile().getName());
                voskModelManagementService.importAsync(chooser.getSelectedFile().toPath());
            } catch (Exception e) {
                voskOperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not import Vosk model."));
            }
        }
    }

    private void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private boolean confirmLargeWhisperDownload(WhisperLocalModelRow row) {
        if (row.catalogEntry() == null || row.catalogEntry().sizeBytes() < 1024L * 1024L * 1024L) {
            return true;
        }
        String message = "Download %s (%s) to %s? Larger Whisper.cpp models can require substantial RAM and CPU."
                .formatted(row.label(), row.sizeLabel(), compactPath(whisperModelManagementService.snapshot().modelRoot()));
        return JOptionPane.showConfirmDialog(this, message, "Download Whisper.cpp model", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private String compactPath(Path path) {
        return path == null ? "" : StringUtils.abbreviateMiddle(path.toString(), "…", 68);
    }

    private int localSelectedColumn(SpeechToTextSettingsSnapshot snapshot) {
        if (isVosk(snapshot)) {
            return 5;
        }
        if (isWhisper(snapshot)) {
            return 4;
        }
        return 1;
    }

    private boolean isVosk(SpeechToTextSettingsSnapshot snapshot) {
        return snapshot != null && VoskSpeechToTextProvider.ID.equals(snapshot.providerId());
    }

    private boolean isWhisper(SpeechToTextSettingsSnapshot snapshot) {
        return snapshot != null && WhisperSpeechToTextProvider.ID.equals(snapshot.providerId());
    }

    private List<SpeechToTextCatalogItem> voskInstalledCatalogItems() {
        return voskModelManagementService.snapshot().installedModels().stream()
                .filter(model -> model.eligible())
                .map(model -> SpeechToTextCatalogItem.of(model.id(), model.label(), model.validationMessage()))
                .toList();
    }

    private List<SpeechToTextCatalogItem> whisperInstalledCatalogItems() {
        return whisperModelManagementService.snapshot().installedModels().stream()
                .filter(model -> model.eligible())
                .map(model -> SpeechToTextCatalogItem.of(model.id(), model.label(), model.validationMessage()))
                .toList();
    }

    private VoskLocalModelRow selectedVoskRow() {
        int row = localModelsTable == null ? -1 : localModelsTable.getSelectedRow();
        if (row >= 0) {
            return voskRowAt(localModelsTable.convertRowIndexToModel(row));
        }
        return voskRows.stream()
                .filter(VoskLocalModelRow::selected)
                .findFirst()
                .orElse(null);
    }

    private VoskLocalModelRow voskRowAt(int modelRow) {
        return modelRow >= 0 && modelRow < voskRows.size() ? voskRows.get(modelRow) : null;
    }

    private WhisperLocalModelRow selectedWhisperRow() {
        int row = localModelsTable == null ? -1 : localModelsTable.getSelectedRow();
        if (row >= 0) {
            return whisperRowAt(localModelsTable.convertRowIndexToModel(row));
        }
        return whisperRows.stream()
                .filter(WhisperLocalModelRow::selected)
                .findFirst()
                .orElse(null);
    }

    private WhisperLocalModelRow whisperRowAt(int modelRow) {
        return modelRow >= 0 && modelRow < whisperRows.size() ? whisperRows.get(modelRow) : null;
    }

    private boolean localModelsVisible(SpeechToTextSettingsSnapshot snapshot) {
        return snapshot.enabled() && snapshot.provider() != null && snapshot.provider().supportsLocalModels();
    }

    private void updateAvailability(SpeechToTextSettingsSnapshot snapshot) {
        boolean voskBusy = isVosk(snapshot) && voskModelManagementService.snapshot().operationInProgress();
        boolean whisperBusy = isWhisper(snapshot) && whisperModelManagementService.snapshot().operationInProgress();
        boolean localBusy = voskBusy || whisperBusy;
        modelComboBox.setEnabled(snapshot.enabled() && ((!isVosk(snapshot) && !isWhisper(snapshot)) || modelComboBox.getItemCount() > 0 && !localBusy));
        refreshButton.setEnabled(snapshot.enabled() && ((isVosk(snapshot) || isWhisper(snapshot)) ? !localBusy : snapshot.available()));
        if (!snapshot.enabled()) {
            helperLabel.setText("Speech to Text is off.");
        } else if (isVosk(snapshot)) {
            helperLabel.setText(StringUtils.defaultIfBlank(snapshot.statusMessage(), "Download or add a Vosk model to enable transcription."));
        } else if (isWhisper(snapshot)) {
            helperLabel.setText(StringUtils.defaultIfBlank(snapshot.statusMessage(), "Transcription runs locally with Whisper.cpp after a model is downloaded. No recorded audio is uploaded."));
        } else if (!snapshot.available()) {
            helperLabel.setText(StringUtils.defaultIfBlank(snapshot.statusMessage(), snapshot.provider().unavailableMessage()));
        } else if (GroqSpeechToTextProvider.ID.equals(snapshot.providerId())) {
            helperLabel.setText("Recorded audio is sent to Groq for transcription. No API key is stored by Chat4J.");
        } else if (ElevenLabsSpeechToTextProvider.ID.equals(snapshot.providerId())) {
            helperLabel.setText("Recorded audio is sent to ElevenLabs for transcription. No API key is stored by Chat4J.");
        } else if (DeepgramSpeechToTextProvider.ID.equals(snapshot.providerId())) {
            helperLabel.setText("Recorded audio is sent to Deepgram for transcription. No API key is stored by Chat4J.");
        } else if (AssemblyAiSpeechToTextProvider.ID.equals(snapshot.providerId())) {
            helperLabel.setText("Recorded audio is sent to AssemblyAI for transcription. No API key is stored by Chat4J.");
        } else {
            helperLabel.setText(snapshot.provider().availableMessage());
        }
    }

    private ProviderOption findProviderOption(String providerId) {
        for (int i = 0; i < providerComboBox.getItemCount(); i++) {
            ProviderOption option = providerComboBox.getItemAt(i);
            if (Objects.equals(option.providerId(), providerId)) {
                return option;
            }
        }
        return ProviderOption.off();
    }

    private void scheduleSave(ThrowingRunnable action, Runnable onSuccess) {
        if (removed) {
            return;
        }
        long saveId = saveCounter.incrementAndGet();
        CompletableFuture<Boolean> pendingSave;
        try {
            pendingSave = CompletableFuture.supplyAsync(() -> {
                try {
                    action.run();
                    if (saveId == saveCounter.get()) {
                        lastSaveError = "";
                    }
                    return true;
                } catch (Exception e) {
                    if (saveId == saveCounter.get()) {
                        lastSaveError = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
                        return false;
                    }
                    return true;
                }
            }, saveExecutor);
        } catch (RejectedExecutionException e) {
            if (!removed) {
                throw e;
            }
            return;
        }
        pendingSaves.add(pendingSave);
        pendingSave.whenComplete((saved, error) -> pendingSaves.remove(pendingSave));
        pendingSave.thenAccept(saved -> runWhenActiveOnEventDispatchThread(() -> {
            if (saveId != saveCounter.get()) {
                return;
            }
            if (saved) {
                onSuccess.run();
            } else {
                setStatusError(lastSaveError);
                refreshControlsFromSettings(false);
            }
        }));
    }

    private void runWhenActiveOnEventDispatchThread(Runnable action) {
        if (removed) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!removed) {
                action.run();
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record ProviderOption(String providerId, String label, boolean available) {
        static ProviderOption off() {
            return new ProviderOption(SpeechToTextSettings.PROVIDER_OFF, "Off", true);
        }

        static ProviderOption of(SpeechToTextProvider provider, boolean available) {
            return new ProviderOption(provider.id(), available ? provider.displayName() : provider.unavailableLabel(), available);
        }
    }

    private static final class ProviderOptionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ProviderOption option) {
                label.setText(option.label());
                label.setEnabled(option.available() || SpeechToTextSettings.PROVIDER_OFF.equals(option.providerId()));
            }
            return label;
        }
    }

    private static final class CatalogItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SpeechToTextCatalogItem item) {
                label.setText(item.label());
            }
            return label;
        }
    }
}
