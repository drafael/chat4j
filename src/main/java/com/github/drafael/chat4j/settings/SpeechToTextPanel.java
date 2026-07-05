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
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSttEndpointResolver;
import com.github.drafael.chat4j.util.Fonts;
import java.awt.*;
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
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public class SpeechToTextPanel extends AbstractSettingsPanel implements PendingSettingsSaveParticipant {

    private static final int FIELD_WIDTH = 520;

    private final SpeechToTextProviderRegistry providerRegistry;
    private final SpeechToTextSettings settings;
    private final SpeechToTextCatalogStore catalogStore;
    private final SpeechToTextModelDownloader modelDownloader;
    private final AtomicLong refreshCounter = new AtomicLong();
    private final AtomicLong saveCounter = new AtomicLong();

    private JComboBox<ProviderOption> providerComboBox;
    private JComboBox<SpeechToTextCatalogItem> modelComboBox;
    private JTextField modelDirectoryField;
    private JPanel localModelsPanel;
    private JTable localModelsTable;
    private DefaultTableModel localModelsTableModel;
    private JButton downloadModelButton;
    private JButton deleteModelButton;
    private JLabel localModelsHintLabel;
    private JSpinner maxDurationSpinner;
    private JButton refreshButton;
    private JLabel helperLabel;
    private boolean updating;
    private boolean updatingLocalModels;
    private List<SpeechToTextCatalogItem> localModelItems = emptyList();
    private final List<CompletableFuture<Boolean>> pendingSaves = new CopyOnWriteArrayList<>();
    private volatile String lastSaveError = "";

    public SpeechToTextPanel(SettingsRepository settingsRepo, Path defaultModelDirectory) {
        this(settingsRepo, defaultModelDirectory, SpeechToTextProviderRegistry.createDefault());
    }

    SpeechToTextPanel(SettingsRepository settingsRepo, Path defaultModelDirectory, SpeechToTextProviderRegistry providerRegistry) {
        this(settingsRepo, defaultModelDirectory, providerRegistry, new UnavailableSpeechToTextModelDownloader());
    }

    SpeechToTextPanel(
            SettingsRepository settingsRepo,
            Path defaultModelDirectory,
            SpeechToTextProviderRegistry providerRegistry,
            SpeechToTextModelDownloader modelDownloader
    ) {
        super(settingsRepo);
        this.providerRegistry = providerRegistry;
        this.settings = new SpeechToTextSettings(settingsRepo, providerRegistry, CredentialSource.SYSTEM, defaultModelDirectory);
        this.catalogStore = new SpeechToTextCatalogStore(settingsRepo);
        this.modelDownloader = modelDownloader;
        buildUi();
    }

    @Override
    public boolean savePendingChanges() {
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
        refreshCounter.incrementAndGet();
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
        row = addFullWidthRow(form, gbc, row, localModelsPanel);
        addVerticalSpacer(form, gbc, row);

        reloadProviderOptions();
        refreshControlsFromSettings(true);
    }

    private JPanel createLocalModelsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.setVisible(false);

        JPanel directoryPanel = new JPanel(new BorderLayout(8, 0));
        directoryPanel.setOpaque(false);
        JLabel directoryLabel = new JLabel("Local models directory");
        directoryLabel.setPreferredSize(new Dimension(160, directoryLabel.getPreferredSize().height));
        JButton browseButton = new JButton("Browse…");
        browseButton.addActionListener(e -> browseModelDirectory());
        modelDirectoryField.addActionListener(e -> saveModelDirectory());
        directoryPanel.add(directoryLabel, BorderLayout.WEST);
        directoryPanel.add(modelDirectoryField, BorderLayout.CENTER);
        directoryPanel.add(browseButton, BorderLayout.EAST);
        panel.add(withPreferredWidth(directoryPanel, FIELD_WIDTH + 160), BorderLayout.NORTH);

        localModelsTableModel = new DefaultTableModel(new Object[] {"Model", "Selected"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 1 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1;
            }
        };
        localModelsTable = new JTable(localModelsTableModel);
        localModelsTable.setFillsViewportHeight(true);
        localModelsTable.setRowHeight(Math.max(localModelsTable.getRowHeight(), 24));
        localModelsTable.getColumnModel().getColumn(1).setMaxWidth(96);
        localModelsTable.getColumnModel().getColumn(1).setPreferredWidth(86);
        localModelsTableModel.addTableModelListener(event -> {
            if (updatingLocalModels || event.getColumn() != 1 || event.getFirstRow() < 0) {
                return;
            }
            if (Boolean.TRUE.equals(localModelsTableModel.getValueAt(event.getFirstRow(), 1))) {
                selectLocalModel(event.getFirstRow());
            } else {
                refreshLocalModelSelection(settings.resolve());
            }
        });
        localModelsTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateLocalModelButtons(settings.resolve());
            }
        });

        JScrollPane modelsScrollPane = new JScrollPane(localModelsTable);
        modelsScrollPane.setPreferredSize(new Dimension(FIELD_WIDTH + 160, 150));
        panel.add(modelsScrollPane, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.setOpaque(false);
        downloadModelButton = new JButton("Download selected model");
        downloadModelButton.addActionListener(e -> downloadSelectedLocalModel());
        deleteModelButton = new JButton("Delete selected model");
        deleteModelButton.addActionListener(e -> deleteSelectedLocalModel());
        actions.add(downloadModelButton);
        actions.add(deleteModelButton);
        localModelsHintLabel = new JLabel(" ");
        Fonts.apply(localModelsHintLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        localModelsHintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        actions.add(localModelsHintLabel);
        panel.add(actions, BorderLayout.SOUTH);

        return panel;
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
        if (refreshCatalogs && snapshot.enabled() && snapshot.available() && catalogStore.stale(snapshot.providerId())) {
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
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle("Select Speech to Text models folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(false);
        String currentPath = modelDirectoryField.getText();
        if (StringUtils.isNotBlank(currentPath)) {
            try {
                var currentDirectory = Path.of(currentPath).toFile();
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
        String rawPath = modelDirectoryField.getText();
        AtomicReference<Path> savedPath = new AtomicReference<>();
        scheduleSave(() -> savedPath.set(settings.saveModelDirectory(rawPath)), () -> {
            Path path = savedPath.get();
            if (path != null) {
                modelDirectoryField.setText(path.toString());
                localModelsHintLabel.setText("Stored under %s".formatted(path));
            }
            setStatusInfo(STATUS_SAVED);
        });
    }

    private void refreshCatalogs(boolean explicit) {
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (!snapshot.enabled() || !snapshot.available()) {
            updateAvailability(snapshot);
            return;
        }
        long refreshId = refreshCounter.incrementAndGet();
        if (explicit) {
            setStatusInfo("Refreshing Speech to Text catalogs...");
        }
        Thread.startVirtualThread(() -> {
            try {
                GroqSttEndpointResolver.Endpoint endpoint = GroqSttEndpointResolver.resolve(snapshot.baseUri().toString());
                SpeechToTextProviderContext context = new SpeechToTextProviderContext(
                        endpoint.baseUri(),
                        endpoint.transcriptionUri(),
                        CredentialSource.SYSTEM,
                        () -> refreshId != refreshCounter.get(),
                        Duration.ofSeconds(45)
                );
                List<SpeechToTextCatalogItem> models = snapshot.provider().fetchModels(context);
                catalogStore.saveModels(snapshot.providerId(), models);
                SwingUtilities.invokeLater(() -> applyCatalogRefresh(refreshId, snapshot, models, explicit));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    if (refreshId == refreshCounter.get()) {
                        setStatusError("Could not refresh %s Speech to Text models.".formatted(snapshot.provider().displayName()));
                    }
                });
            }
        });
    }

    private void applyCatalogRefresh(long refreshId, SpeechToTextSettingsSnapshot previous, List<SpeechToTextCatalogItem> models, boolean explicit) {
        if (refreshId != refreshCounter.get()) {
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
        if (!visible) {
            localModelItems = emptyList();
            updateLocalModelRows(snapshot, emptyList());
            updateLocalModelButtons(snapshot);
            return;
        }

        localModelItems = List.copyOf(models == null ? emptyList() : models);
        updateLocalModelRows(snapshot, localModelItems);
        updateLocalModelButtons(snapshot);
        Path directory = settings.modelDirectory().resolve();
        localModelsHintLabel.setText("Stored under %s".formatted(directory));
    }

    private void updateLocalModelRows(SpeechToTextSettingsSnapshot snapshot, List<SpeechToTextCatalogItem> models) {
        updatingLocalModels = true;
        try {
            localModelsTableModel.setRowCount(0);
            models.forEach(item -> localModelsTableModel.addRow(new Object[] {
                    item.label(),
                    snapshot.model() != null && Objects.equals(item.id(), snapshot.model().id())
            }));
        } finally {
            updatingLocalModels = false;
        }
    }

    private void refreshLocalModelSelection(SpeechToTextSettingsSnapshot snapshot) {
        if (!localModelsVisible(snapshot)) {
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

    private void selectLocalModel(int row) {
        if (row < 0 || row >= localModelItems.size()) {
            return;
        }
        SpeechToTextCatalogItem item = localModelItems.get(row);
        SpeechToTextSettingsSnapshot snapshot = settings.resolve();
        if (!localModelsVisible(snapshot)) {
            return;
        }
        scheduleSave(() -> settings.saveModel(snapshot.providerId(), item), () -> {
            selectCatalogItem(item);
            refreshLocalModelSelection(settings.resolve());
            setStatusInfo(STATUS_SAVED);
        });
    }

    private void updateLocalModelButtons(SpeechToTextSettingsSnapshot snapshot) {
        SpeechToTextCatalogItem selected = selectedLocalModel();
        boolean enabled = localModelsVisible(snapshot) && selected != null;
        downloadModelButton.setEnabled(enabled && snapshot.available());
        deleteModelButton.setEnabled(enabled);
    }

    private SpeechToTextCatalogItem selectedLocalModel() {
        int row = localModelsTable == null ? -1 : localModelsTable.getSelectedRow();
        if (row >= 0 && row < localModelItems.size()) {
            return localModelItems.get(row);
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

    private void deleteDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    private boolean localModelsVisible(SpeechToTextSettingsSnapshot snapshot) {
        return snapshot.enabled() && snapshot.provider() != null && snapshot.provider().supportsLocalModels();
    }

    private void updateAvailability(SpeechToTextSettingsSnapshot snapshot) {
        modelComboBox.setEnabled(snapshot.enabled());
        refreshButton.setEnabled(snapshot.enabled() && snapshot.available());
        if (!snapshot.enabled()) {
            helperLabel.setText("Speech to Text is off.");
        } else if (!snapshot.available()) {
            helperLabel.setText(StringUtils.defaultIfBlank(snapshot.statusMessage(), snapshot.provider().unavailableMessage()));
        } else if (GroqSpeechToTextProvider.ID.equals(snapshot.providerId())) {
            helperLabel.setText("Recorded audio is sent to Groq for transcription. No API key is stored by Chat4J.");
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
