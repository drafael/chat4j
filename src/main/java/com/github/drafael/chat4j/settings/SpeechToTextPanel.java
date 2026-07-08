package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
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
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.sphinx4.Sphinx4InstalledModel;
import com.github.drafael.chat4j.stt.provider.sphinx4.Sphinx4LocalModelRow;
import com.github.drafael.chat4j.stt.provider.sphinx4.Sphinx4ModelManagementService;
import com.github.drafael.chat4j.stt.provider.sphinx4.Sphinx4ModelManagementSnapshot;
import com.github.drafael.chat4j.stt.provider.vosk.VoskLocalModelRow;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementSnapshot;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public class SpeechToTextPanel extends AbstractSettingsPanel implements PendingSettingsSaveParticipant {

    private static final int FIELD_WIDTH = 520;
    private static final int LOCAL_MODELS_WIDTH = FIELD_WIDTH;
    private static final int LOCAL_MODELS_TABLE_HEIGHT = 230;
    private static final int LOCAL_MODELS_ROW_GAP = 6;
    private static final int LOCAL_MODELS_COLUMN_GAP = 12;

    private final SpeechToTextProviderRegistry providerRegistry;
    private final SpeechToTextSettings settings;
    private final SpeechToTextCatalogStore catalogStore;
    private final SpeechToTextModelDownloader modelDownloader;
    private final VoskModelManagementService voskModelManagementService;
    private final Sphinx4ModelManagementService sphinx4ModelManagementService;
    private final boolean ownsVoskModelManagementService;
    private final boolean ownsSphinx4ModelManagementService;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final AtomicLong refreshCounter = new AtomicLong();
    private final AtomicLong saveCounter = new AtomicLong();

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
    private List<Sphinx4LocalModelRow> sphinx4Rows = emptyList();
    private boolean voskOperationInProgress;
    private boolean sphinx4OperationInProgress;
    private String voskOperationSuccessMessage = "";
    private String sphinx4OperationSuccessMessage = "";
    private Runnable voskUnsubscribe = () -> {
    };
    private Runnable sphinx4Unsubscribe = () -> {
    };
    private final List<CompletableFuture<Boolean>> pendingSaves = new CopyOnWriteArrayList<>();
    private volatile String lastSaveError = "";

    public SpeechToTextPanel(SettingsRepository settingsRepo, Path defaultModelDirectory) {
        this(
                settingsRepo,
                defaultModelDirectory,
                SpeechToTextProviderRegistry.createDefault(),
                new UnavailableSpeechToTextModelDownloader(),
                new VoskModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                new Sphinx4ModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                true,
                true
        );
    }

    public SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            VoskModelManagementService voskModelManagementService
    ) {
        this(
                settingsRepo,
                defaultModelDirectory,
                SpeechToTextProviderRegistry.createDefault(),
                new UnavailableSpeechToTextModelDownloader(),
                voskModelManagementService,
                new Sphinx4ModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                false,
                true
        );
    }

    public SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            VoskModelManagementService voskModelManagementService,
            Sphinx4ModelManagementService sphinx4ModelManagementService
    ) {
        this(settingsRepo, defaultModelDirectory, SpeechToTextProviderRegistry.createDefault(),
                new UnavailableSpeechToTextModelDownloader(), voskModelManagementService, sphinx4ModelManagementService, false, false);
    }

    SpeechToTextPanel(SettingsRepository settingsRepo, Path defaultModelDirectory, SpeechToTextProviderRegistry providerRegistry) {
        this(
                settingsRepo,
                defaultModelDirectory,
                providerRegistry,
                new UnavailableSpeechToTextModelDownloader(),
                new VoskModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                new Sphinx4ModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                true,
                true
        );
    }

    SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader
    ) {
        this(
                settingsRepo,
                defaultModelDirectory,
                providerRegistry,
                modelDownloader,
                new VoskModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                new Sphinx4ModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                true,
                true
        );
    }

    SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader,
            VoskModelManagementService voskModelManagementService
    ) {
        this(
                settingsRepo,
                defaultModelDirectory,
                providerRegistry,
                modelDownloader,
                voskModelManagementService,
                new Sphinx4ModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")),
                false,
                true
        );
    }

    private SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader,
            VoskModelManagementService voskModelManagementService,
            Sphinx4ModelManagementService sphinx4ModelManagementService,
            boolean ownsVoskModelManagementService,
            boolean ownsSphinx4ModelManagementService
    ) {
        super(settingsRepo);
        this.providerRegistry = providerRegistry;
        this.voskModelManagementService = voskModelManagementService;
        this.sphinx4ModelManagementService = sphinx4ModelManagementService;
        this.ownsVoskModelManagementService = ownsVoskModelManagementService;
        this.ownsSphinx4ModelManagementService = ownsSphinx4ModelManagementService;
        this.settings = new SpeechToTextSettings(
                settingsRepo,
                providerRegistry,
                CredentialSource.SYSTEM,
                defaultModelDirectory,
                voskModelManagementService,
                sphinx4ModelManagementService
        );
        this.catalogStore = new SpeechToTextCatalogStore(settingsRepo);
        this.modelDownloader = modelDownloader;
        buildUi();
        voskUnsubscribe = voskModelManagementService.addListener(snapshot -> SwingUtilities.invokeLater(() -> {
            if (!disposed.get()) {
                handleVoskModelSnapshot(snapshot);
            }
        }));
        sphinx4Unsubscribe = sphinx4ModelManagementService.addListener(snapshot -> SwingUtilities.invokeLater(() -> {
            if (!disposed.get()) {
                handleSphinx4ModelSnapshot(snapshot);
            }
        }));
    }

    @Override
    public boolean savePendingChanges() {
        if (anyLocalOperationInProgress()) {
            lastSaveError = "A local speech model operation is still in progress.";
            return false;
        }
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
        disposed.set(true);
        refreshCounter.incrementAndGet();
        voskUnsubscribe.run();
        sphinx4Unsubscribe.run();
        if (ownsVoskModelManagementService) {
            voskModelManagementService.close();
        }
        if (ownsSphinx4ModelManagementService) {
            sphinx4ModelManagementService.close();
        }
        super.removeNotify();
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
                boolean voskColumns = getColumnCount() > 2;
                boolean booleanColumn = voskColumns ? columnIndex == 4 || columnIndex == 5 : columnIndex == 1;
                return booleanColumn ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                SpeechToTextSettingsSnapshot snapshot = settings.resolve();
                return isManagedLocalProvider(snapshot)
                        ? column == 5 && !localOperationInProgress(snapshot)
                        : column == 1;
            }
        };
        localModelsTable = new JTable(localModelsTableModel);
        localModelsTable.setAutoCreateRowSorter(true);
        localModelsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        localModelsTable.setFillsViewportHeight(true);
        localModelsTable.setRowHeight(Math.max(localModelsTable.getRowHeight(), 24));
        configureGenericLocalModelColumns();
        localModelsTableModel.addTableModelListener(event -> {
            if (updatingLocalModels || event.getFirstRow() < 0) {
                return;
            }
            SpeechToTextSettingsSnapshot snapshot = settings.resolve();
            int selectedColumn = isManagedLocalProvider(snapshot) ? 5 : 1;
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
        importModelButton.setToolTipText("Copy an existing local speech model into Chat4J's managed model folder");
        importModelButton.getAccessibleContext().setAccessibleName("Add existing local speech model folder");
        importModelButton.addActionListener(e -> importExistingLocalModel());
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

    private void handleSphinx4ModelSnapshot(Sphinx4ModelManagementSnapshot snapshot) {
        boolean completed = sphinx4OperationInProgress && !snapshot.operationInProgress();
        sphinx4OperationInProgress = snapshot.operationInProgress();
        if (localModelsPanel != null && isShowing()) {
            refreshControlsFromSettings(false);
        }
        if (completed) {
            applyCompletedSphinx4OperationStatus(snapshot);
        }
    }

    private void applyCompletedSphinx4OperationStatus(Sphinx4ModelManagementSnapshot snapshot) {
        if (StringUtils.isNotBlank(snapshot.operationStatus())) {
            if (StringUtils.startsWith(snapshot.operationStatus(), "Imported ")
                    && !snapshot.operationStatus().contains("but it is not selectable")) {
                setStatusInfo(snapshot.operationStatus());
            } else {
                setStatusError(snapshot.operationStatus());
            }
        } else if (StringUtils.isNotBlank(sphinx4OperationSuccessMessage)) {
            setStatusInfo(sphinx4OperationSuccessMessage);
        }
        sphinx4OperationSuccessMessage = "";
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
            } else if (isSphinx4(snapshot)) {
                models = sphinx4InstalledCatalogItems();
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
        if (refreshCatalogs
                && snapshot.enabled()
                && !snapshot.provider().supportsLocalModels()
                && snapshot.available()
                && catalogStore.stale(snapshot.providerId())) {
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
        if (anyLocalOperationInProgress()) {
            updating = true;
            providerComboBox.setSelectedItem(findProviderOption(settings.resolve().providerId()));
            updating = false;
            setStatusError("Finish the current local speech model operation before changing providers.");
            return;
        }
        scheduleSave(() -> settings.saveProvider(option.providerId()), () -> {
            reloadProviderOptions();
            refreshControlsFromSettings(true);
            setStatusInfo(STATUS_SAVED);
        });
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
            if (isSphinx4(snapshot)) {
                selectSphinx4Model(item.id(), item.label());
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
                sphinx4ModelManagementService.refreshAsync();
            }
            setStatusInfo(STATUS_SAVED);
        });
    }

    private boolean localOperationBlocksModelDirectoryChange() {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (!anyLocalOperationInProgress()) {
            return false;
        }
        modelDirectoryField.setText(snapshot.modelDirectory().toString());
        setStatusError("Finish the current local speech model operation before changing the models folder.");
        return true;
    }

    private void refreshCatalogs(boolean explicit) {
        if (disposed.get()) {
            return;
        }
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
        if (isSphinx4(snapshot)) {
            if (explicit) {
                sphinx4OperationSuccessMessage = "Sphinx4 model catalog refreshed.";
                setStatusInfo("Refreshing Sphinx4 model catalog...");
            }
            try {
                sphinx4ModelManagementService.refreshCatalogAsync();
            } catch (Exception e) {
                sphinx4OperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not refresh Sphinx4 model catalog."));
            }
            return;
        }
        if (!snapshot.available()) {
            updateAvailability(snapshot);
            return;
        }
        long refreshId = refreshCounter.incrementAndGet();
        if (explicit) {
            setStatusInfo("Refreshing Speech to Text catalogs...");
        }
        Thread.startVirtualThread(() -> {
            try {
                SpeechToTextProviderContext context = new SpeechToTextProviderContext(
                        snapshot.baseUri(),
                        snapshot.transcriptionUri(),
                        CredentialSource.SYSTEM,
                        () -> refreshId != refreshCounter.get(),
                        Duration.ofSeconds(45)
                );
                List<SpeechToTextCatalogItem> models = snapshot.provider().fetchModels(context);
                if (disposed.get() || refreshId != refreshCounter.get()) {
                    return;
                }
                catalogStore.saveModels(snapshot.providerId(), models);
                if (disposed.get() || refreshId != refreshCounter.get()) {
                    return;
                }
                SwingUtilities.invokeLater(() -> applyCatalogRefresh(refreshId, snapshot, models, explicit));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (!disposed.get() && refreshId == refreshCounter.get() && explicit) {
                        setStatusError("Could not refresh %s Speech to Text models.".formatted(snapshot.provider().displayName()));
                    }
                });
            }
        });
    }

    private void applyCatalogRefresh(long refreshId, SpeechToTextSettingsSnapshot previous, List<SpeechToTextCatalogItem> models, boolean explicit) {
        if (disposed.get() || refreshId != refreshCounter.get()) {
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
        importModelButton.setVisible(isManagedLocalProvider(snapshot));
        updateLocalModelActionText(snapshot);
        updateModelDirectoryControls(snapshot);
        if (!visible) {
            localModelItems = emptyList();
            voskRows = emptyList();
            sphinx4Rows = emptyList();
            updateLocalModelRows(snapshot, emptyList());
            updateLocalModelButtons(snapshot);
            return;
        }
        if (isVosk(snapshot)) {
            updateVoskLocalModels(snapshot);
            return;
        }
        if (isSphinx4(snapshot)) {
            updateSphinx4LocalModels(snapshot);
            return;
        }

        localModelItems = List.copyOf(models == null ? emptyList() : models);
        updateLocalModelRows(snapshot, localModelItems);
        updateLocalModelButtons(snapshot);
        Path directory = settings.modelDirectory().resolve();
        localModelsHintLabel.setText("Stored under %s".formatted(compactPath(directory)));
    }

    private void updateModelDirectoryControls(SpeechToTextSettingsSnapshot snapshot) {
        boolean enabled = !localOperationInProgress(snapshot);
        modelDirectoryField.setEnabled(enabled);
        if (modelDirectoryBrowseButton != null) {
            modelDirectoryBrowseButton.setEnabled(enabled);
        }
    }

    private void updateLocalModelActionText(SpeechToTextSettingsSnapshot snapshot) {
        if (isSphinx4(snapshot)) {
            downloadModelButton.setToolTipText("Download the selected verified Sphinx4 catalog model");
            importModelButton.setToolTipText("Copy an existing Sphinx4 model into Chat4J's managed model folder");
            importModelButton.getAccessibleContext().setAccessibleName("Add existing Sphinx4 model folder");
            return;
        }
        if (isVosk(snapshot)) {
            downloadModelButton.setToolTipText("Download the selected Vosk catalog model");
            importModelButton.setToolTipText("Copy an existing unzipped Vosk model into Chat4J's managed model folder");
            importModelButton.getAccessibleContext().setAccessibleName("Add existing Vosk model folder");
        }
    }

    private void updateVoskLocalModels(SpeechToTextSettingsSnapshot snapshot) {
        VoskModelManagementSnapshot voskSnapshot = voskModelManagementService.snapshot();
        voskRows = List.copyOf(voskSnapshot.rows());
        updateManagedLocalRows(voskRows.stream()
                .map(row -> new Object[] {row.label(), row.language(), row.type(), row.sizeLabel(), row.installed(), row.selected(), row.actionStatus()})
                .toList());
        localModelsHintLabel.setText(voskSnapshot.operationInProgress()
                ? voskSnapshot.operationStatus()
                : "Vosk root: %s".formatted(compactPath(voskSnapshot.modelRoot())));
        updateLocalModelButtons(snapshot);
    }

    private void updateSphinx4LocalModels(SpeechToTextSettingsSnapshot snapshot) {
        Sphinx4ModelManagementSnapshot sphinx4Snapshot = sphinx4ModelManagementService.snapshot();
        sphinx4Rows = List.copyOf(sphinx4Snapshot.rows());
        updateManagedLocalRows(sphinx4Rows.stream()
                .map(row -> new Object[] {row.label(), row.language(), row.type(), row.sizeLabel(), row.installed(), row.selected(), row.actionStatus()})
                .toList());
        localModelsHintLabel.setText(sphinx4Snapshot.operationInProgress()
                ? sphinx4Snapshot.operationStatus()
                : "Sphinx4 root: %s".formatted(compactPath(sphinx4Snapshot.modelRoot())));
        updateLocalModelButtons(snapshot);
    }

    private void updateManagedLocalRows(List<Object[]> rows) {
        updatingLocalModels = true;
        try {
            localModelsTableModel.setColumnIdentifiers(new Object[] {"Model", "Language", "Type", "Size", "Installed", "Selected", "Status"});
            configureVoskLocalModelColumns();
            localModelsTableModel.setRowCount(0);
            rows.forEach(localModelsTableModel::addRow);
        } finally {
            updatingLocalModels = false;
        }
    }

    private void updateLocalModelRows(SpeechToTextSettingsSnapshot snapshot, List<SpeechToTextCatalogItem> models) {
        updatingLocalModels = true;
        try {
            localModelsTableModel.setColumnIdentifiers(new Object[] {"Model", "Selected"});
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

    private void refreshLocalModelSelection(SpeechToTextSettingsSnapshot snapshot) {
        if (!localModelsVisible(snapshot)) {
            return;
        }
        if (isVosk(snapshot)) {
            updateVoskLocalModels(snapshot);
            return;
        }
        if (isSphinx4(snapshot)) {
            updateSphinx4LocalModels(snapshot);
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

    private void selectSphinx4Model(String modelId, String label) {
        try {
            sphinx4OperationSuccessMessage = STATUS_SAVED;
            setStatusInfo("Selecting %s...".formatted(StringUtils.defaultIfBlank(label, "Sphinx4 model")));
            sphinx4ModelManagementService.selectModelAsync(modelId);
            refreshControlsFromSettings(false);
        } catch (Exception e) {
            sphinx4OperationSuccessMessage = "";
            refreshControlsFromSettings(false);
            setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not select Sphinx4 model."));
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
        if (isSphinx4(snapshot)) {
            Sphinx4LocalModelRow sphinx4Row = sphinx4RowAt(row);
            if (sphinx4Row == null || !sphinx4Row.selectable()) {
                refreshLocalModelSelection(snapshot);
                return;
            }
            selectSphinx4Model(sphinx4Row.id(), sphinx4Row.label());
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

    private void updateLocalModelButtons(SpeechToTextSettingsSnapshot snapshot) {
        if (isVosk(snapshot)) {
            VoskModelManagementSnapshot voskSnapshot = voskModelManagementService.snapshot();
            VoskLocalModelRow selected = selectedVoskRow();
            boolean idle = !voskSnapshot.operationInProgress();
            downloadModelButton.setEnabled(idle && selected != null && selected.downloadable());
            deleteModelButton.setEnabled(idle && selected != null && selected.deleteable());
            importModelButton.setEnabled(idle);
            return;
        }
        if (isSphinx4(snapshot)) {
            Sphinx4ModelManagementSnapshot sphinx4Snapshot = sphinx4ModelManagementService.snapshot();
            Sphinx4LocalModelRow selected = selectedSphinx4Row();
            boolean idle = !sphinx4Snapshot.operationInProgress();
            downloadModelButton.setEnabled(idle && selected != null && selected.downloadable());
            deleteModelButton.setEnabled(idle && selected != null && selected.deleteable());
            importModelButton.setEnabled(idle);
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
        if (isSphinx4(snapshot)) {
            Sphinx4LocalModelRow selected = selectedSphinx4Row();
            if (selected == null || !selected.downloadable()) {
                setStatusError(selected == null ? "Select a Sphinx4 catalog row first." : selected.actionStatus());
                return;
            }
            sphinx4OperationSuccessMessage = "Downloaded %s".formatted(selected.label());
            setStatusInfo("Downloading %s...".formatted(selected.label()));
            try {
                sphinx4ModelManagementService.downloadAsync(selected.id());
            } catch (Exception e) {
                sphinx4OperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not download Sphinx4 model."));
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
                SwingUtilities.invokeLater(() -> setStatusInfo("%s downloaded".formatted(selected.label())));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not download model.")));
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
        if (isSphinx4(snapshot)) {
            Sphinx4LocalModelRow selected = selectedSphinx4Row();
            if (selected == null || !selected.deleteable()) {
                return;
            }
            sphinx4OperationSuccessMessage = "Deleted %s".formatted(selected.label());
            try {
                sphinx4ModelManagementService.deleteAsync(selected.id());
            } catch (Exception e) {
                sphinx4OperationSuccessMessage = "";
                setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not delete Sphinx4 model."));
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
                SwingUtilities.invokeLater(() -> setStatusInfo("Deleted local files for %s".formatted(selected.label())));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not delete local model.")));
            }
        });
    }

    private void importExistingLocalModel() {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (!isManagedLocalProvider(snapshot)) {
            return;
        }
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle(isSphinx4(snapshot) ? "Select existing Sphinx4 model folder" : "Select existing Vosk model folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            if (isSphinx4(snapshot)) {
                importExistingSphinx4Model(chooser.getSelectedFile().toPath(), chooser.getSelectedFile().getName());
            } else {
                importExistingVoskModel(chooser.getSelectedFile().toPath(), chooser.getSelectedFile().getName());
            }
        }
    }

    private void importExistingVoskModel(Path source, String name) {
        try {
            voskOperationSuccessMessage = "Imported %s".formatted(name);
            voskModelManagementService.importAsync(source);
        } catch (Exception e) {
            voskOperationSuccessMessage = "";
            setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not import Vosk model."));
        }
    }

    private void importExistingSphinx4Model(Path source, String name) {
        try {
            sphinx4OperationSuccessMessage = "Imported %s".formatted(name);
            sphinx4ModelManagementService.importAsync(source);
        } catch (Exception e) {
            sphinx4OperationSuccessMessage = "";
            setStatusError(StringUtils.defaultIfBlank(e.getMessage(), "Could not import Sphinx4 model."));
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

    private String compactPath(Path path) {
        return path == null ? "" : StringUtils.abbreviateMiddle(path.toString(), "…", 68);
    }

    private boolean isVosk(SpeechToTextSettingsSnapshot snapshot) {
        return snapshot != null && VoskModelManagementService.PROVIDER_ID.equals(snapshot.providerId());
    }

    private boolean isSphinx4(SpeechToTextSettingsSnapshot snapshot) {
        return snapshot != null && Sphinx4ModelManagementService.PROVIDER_ID.equals(snapshot.providerId());
    }

    private boolean isManagedLocalProvider(SpeechToTextSettingsSnapshot snapshot) {
        return isVosk(snapshot) || isSphinx4(snapshot);
    }

    private boolean anyLocalOperationInProgress() {
        return voskModelManagementService.snapshot().operationInProgress() || sphinx4ModelManagementService.snapshot().operationInProgress();
    }

    private boolean localOperationInProgress(SpeechToTextSettingsSnapshot snapshot) {
        if (isVosk(snapshot)) {
            return voskModelManagementService.snapshot().operationInProgress();
        }
        if (isSphinx4(snapshot)) {
            return sphinx4ModelManagementService.snapshot().operationInProgress();
        }
        return false;
    }

    private List<SpeechToTextCatalogItem> voskInstalledCatalogItems() {
        return voskModelManagementService.snapshot().installedModels().stream()
                .filter(model -> model.eligible())
                .map(model -> SpeechToTextCatalogItem.of(model.id(), model.label(), model.validationMessage()))
                .toList();
    }

    private List<SpeechToTextCatalogItem> sphinx4InstalledCatalogItems() {
        return sphinx4ModelManagementService.snapshot().installedModels().stream()
                .filter(Sphinx4InstalledModel::eligible)
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

    private Sphinx4LocalModelRow selectedSphinx4Row() {
        int row = localModelsTable == null ? -1 : localModelsTable.getSelectedRow();
        if (row >= 0) {
            return sphinx4RowAt(localModelsTable.convertRowIndexToModel(row));
        }
        return sphinx4Rows.stream()
                .filter(Sphinx4LocalModelRow::selected)
                .findFirst()
                .orElse(null);
    }

    private Sphinx4LocalModelRow sphinx4RowAt(int modelRow) {
        return modelRow >= 0 && modelRow < sphinx4Rows.size() ? sphinx4Rows.get(modelRow) : null;
    }

    private boolean localModelsVisible(SpeechToTextSettingsSnapshot snapshot) {
        return snapshot.enabled() && snapshot.provider() != null && snapshot.provider().supportsLocalModels();
    }

    private void updateAvailability(SpeechToTextSettingsSnapshot snapshot) {
        boolean localBusy = localOperationInProgress(snapshot);
        modelComboBox.setEnabled(snapshot.enabled() && (!isManagedLocalProvider(snapshot) || modelComboBox.getItemCount() > 0 && !localBusy));
        refreshButton.setEnabled(snapshot.enabled() && (isManagedLocalProvider(snapshot) ? !localBusy : snapshot.available()));
        if (!snapshot.enabled()) {
            helperLabel.setText("Speech to Text is off.");
        } else if (isVosk(snapshot)) {
            helperLabel.setText(StringUtils.defaultIfBlank(snapshot.statusMessage(), "Download or add a Vosk model to enable transcription."));
        } else if (isSphinx4(snapshot)) {
            helperLabel.setText(StringUtils.defaultIfBlank(snapshot.statusMessage(), "Download or add a Sphinx4 model to enable transcription."));
        } else if (!snapshot.available()) {
            helperLabel.setText(StringUtils.defaultIfBlank(snapshot.statusMessage(), snapshot.provider().unavailableMessage()));
        } else if (GroqSpeechToTextProvider.ID.equals(snapshot.providerId())) {
            helperLabel.setText("Recorded audio is sent to Groq for transcription. No API key is stored by Chat4J.");
        } else if (ElevenLabsSpeechToTextProvider.ID.equals(snapshot.providerId())) {
            helperLabel.setText("Recorded audio is sent to ElevenLabs for transcription. No API key is stored by Chat4J.");
        } else if (DeepgramSpeechToTextProvider.ID.equals(snapshot.providerId())) {
            helperLabel.setText("Recorded audio is sent to Deepgram for transcription. No API key is stored by Chat4J.");
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
        long saveId = saveCounter.incrementAndGet();
        CompletableFuture<Boolean> pendingSave = CompletableFuture.supplyAsync(() -> {
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
        });
        pendingSaves.add(pendingSave);
        pendingSave.whenComplete((saved, error) -> pendingSaves.remove(pendingSave));
        pendingSave.thenAccept(saved -> SwingUtilities.invokeLater(() -> {
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private record ProviderOption(String providerId, String label, boolean available) {
        static ProviderOption off() {
            return new ProviderOption(SettingsKeys.STT_PROVIDER_OFF, "Off", true);
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
                label.setEnabled(option.available() || SettingsKeys.STT_PROVIDER_OFF.equals(option.providerId()));
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
