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
    private final boolean ownsVoskModelManagementService;
    private long refreshCounter;
    private final Object refreshLock = new Object();
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
    private boolean voskOperationInProgress;
    private String voskOperationSuccessMessage = "";
    private Runnable voskUnsubscribe = () -> {
    };
    private final List<CompletableFuture<Boolean>> pendingSaves = new CopyOnWriteArrayList<>();
    private volatile String lastSaveError = "";

    public SpeechToTextPanel(SettingsRepository settingsRepo, Path defaultModelDirectory) {
        this(settingsRepo, defaultModelDirectory, SpeechToTextProviderRegistry.createDefault(),
                new UnavailableSpeechToTextModelDownloader(), new VoskModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")), true);
    }

    public SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            VoskModelManagementService voskModelManagementService
    ) {
        this(settingsRepo, defaultModelDirectory, SpeechToTextProviderRegistry.createDefault(),
                new UnavailableSpeechToTextModelDownloader(), voskModelManagementService, false);
    }

    SpeechToTextPanel(SettingsRepository settingsRepo, Path defaultModelDirectory, SpeechToTextProviderRegistry providerRegistry) {
        this(settingsRepo, defaultModelDirectory, providerRegistry, new UnavailableSpeechToTextModelDownloader(),
                new VoskModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")), true);
    }

    SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader
    ) {
        this(settingsRepo, defaultModelDirectory, providerRegistry, modelDownloader,
                new VoskModelManagementService(settingsRepo, defaultModelDirectory, defaultModelDirectory.resolveSibling("temp")), true);
    }

    SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader,
            VoskModelManagementService voskModelManagementService
    ) {
        this(settingsRepo, defaultModelDirectory, providerRegistry, modelDownloader, voskModelManagementService, false);
    }

    private SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader,
            VoskModelManagementService voskModelManagementService,
            boolean ownsVoskModelManagementService
    ) {
        super(settingsRepo);
        this.providerRegistry = providerRegistry;
        this.voskModelManagementService = voskModelManagementService;
        this.ownsVoskModelManagementService = ownsVoskModelManagementService;
        this.settings = new SpeechToTextSettings(settingsRepo, providerRegistry, CredentialSource.SYSTEM, defaultModelDirectory, voskModelManagementService);
        this.catalogStore = new SpeechToTextCatalogStore(settingsRepo);
        this.modelDownloader = modelDownloader;
        buildUi();
        voskUnsubscribe = voskModelManagementService.addListener(snapshot -> SwingUtilities.invokeLater(() -> handleVoskModelSnapshot(snapshot)));
    }

    @Override
    public boolean savePendingChanges() {
        if (VoskModelManagementService.PROVIDER_ID.equals(settings.resolve().providerId())
                && voskModelManagementService.snapshot().operationInProgress()) {
            lastSaveError = "A Vosk model operation is still in progress.";
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
        cancelCatalogRefreshes();
        voskUnsubscribe.run();
        if (ownsVoskModelManagementService) {
            voskModelManagementService.close();
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
                return isVosk(snapshot)
                        ? column == 5 && !voskModelManagementService.snapshot().operationInProgress()
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
            int selectedColumn = isVosk(snapshot) ? 5 : 1;
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
        if (refreshCatalogs && snapshot.enabled() && !isVosk(snapshot) && snapshot.available() && catalogStore.stale(snapshot.providerId())) {
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
        if (voskOperationBlocksModelDirectoryChange()) {
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
        if (voskOperationBlocksModelDirectoryChange()) {
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
            }
            setStatusInfo(STATUS_SAVED);
        });
    }

    private boolean voskOperationBlocksModelDirectoryChange() {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (!isVosk(snapshot) || !voskModelManagementService.snapshot().operationInProgress()) {
            return false;
        }
        modelDirectoryField.setText(snapshot.modelDirectory().toString());
        setStatusError("Finish the current Vosk model operation before changing the models folder.");
        return true;
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
                SwingUtilities.invokeLater(() -> applyCatalogRefresh(refreshId, snapshot, models, explicit));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
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
        importModelButton.setVisible(isVosk(snapshot));
        updateModelDirectoryControls(snapshot);
        if (!visible) {
            localModelItems = emptyList();
            voskRows = emptyList();
            updateLocalModelRows(snapshot, emptyList());
            updateLocalModelButtons(snapshot);
            return;
        }
        if (isVosk(snapshot)) {
            updateVoskLocalModels(snapshot);
            return;
        }

        localModelItems = List.copyOf(models == null ? emptyList() : models);
        updateLocalModelRows(snapshot, localModelItems);
        updateLocalModelButtons(snapshot);
        Path directory = settings.modelDirectory().resolve();
        localModelsHintLabel.setText("Stored under %s".formatted(compactPath(directory)));
    }

    private void updateModelDirectoryControls(SpeechToTextSettingsSnapshot snapshot) {
        boolean enabled = !isVosk(snapshot) || !voskModelManagementService.snapshot().operationInProgress();
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

    private String compactPath(Path path) {
        return path == null ? "" : StringUtils.abbreviateMiddle(path.toString(), "…", 68);
    }

    private boolean isVosk(SpeechToTextSettingsSnapshot snapshot) {
        return snapshot != null && VoskModelManagementService.PROVIDER_ID.equals(snapshot.providerId());
    }

    private List<SpeechToTextCatalogItem> voskInstalledCatalogItems() {
        return voskModelManagementService.snapshot().installedModels().stream()
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

    private boolean localModelsVisible(SpeechToTextSettingsSnapshot snapshot) {
        return snapshot.enabled() && snapshot.provider() != null && snapshot.provider().supportsLocalModels();
    }

    private void updateAvailability(SpeechToTextSettingsSnapshot snapshot) {
        boolean voskBusy = isVosk(snapshot) && voskModelManagementService.snapshot().operationInProgress();
        modelComboBox.setEnabled(snapshot.enabled() && (!isVosk(snapshot) || modelComboBox.getItemCount() > 0 && !voskBusy));
        refreshButton.setEnabled(snapshot.enabled() && (isVosk(snapshot) ? !voskBusy : snapshot.available()));
        if (!snapshot.enabled()) {
            helperLabel.setText("Speech to Text is off.");
        } else if (isVosk(snapshot)) {
            helperLabel.setText(StringUtils.defaultIfBlank(snapshot.statusMessage(), "Download or add a Vosk model to enable transcription."));
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
