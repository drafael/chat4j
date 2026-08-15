package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.db.ChatStorageSettings;
import com.github.drafael.chat4j.persistence.db.PersistenceBackendConfig;
import com.github.drafael.chat4j.persistence.db.StorageBackend;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import static java.util.stream.Collectors.toSet;

public class GeneralPanel extends AbstractSettingsPanel implements AsyncPendingSettingsSaveParticipant {

    private static final String SEND_ENTER = ChatBehaviorSettings.SEND_ENTER;
    private static final String SEND_CTRL_ENTER = ChatBehaviorSettings.SEND_CTRL_ENTER;

    private final Runnable exitAction;
    private final ChatBehaviorSettings chatBehaviorSettings;
    private final RenderModeSettings renderModeSettings;
    private final ChatStorageSettings chatStorageSettings;
    private final StorageRestartPrompt storageRestartPrompt;
    private final SettingsWriteQueue writeQueue = new SettingsWriteQueue("general-settings-save-");
    private final Map<String, SaveRequest> latestRequests = new HashMap<>();
    private final Map<String, SaveRequest> failedRequests = new HashMap<>();
    private boolean disposed;
    private String lastSaveError = "";
    private DeferredStorageAction deferredStorageAction;

    public GeneralPanel(SettingsRepository settingsRepo) {
        this(settingsRepo, () -> System.exit(0));
    }

    public GeneralPanel(SettingsRepository settingsRepo, Runnable exitAction) {
        this(settingsRepo, exitAction, new ChatBehaviorSettings(settingsRepo), new RenderModeSettings(settingsRepo),
                new ChatStorageSettings(settingsRepo), null);
    }

    GeneralPanel(
            SettingsRepository settingsRepo,
            Runnable exitAction,
            ChatBehaviorSettings chatBehaviorSettings,
            RenderModeSettings renderModeSettings,
            ChatStorageSettings chatStorageSettings,
            StorageRestartPrompt storageRestartPrompt
    ) {
        super(settingsRepo);
        this.exitAction = exitAction == null ? () -> System.exit(0) : exitAction;
        this.chatBehaviorSettings = chatBehaviorSettings;
        this.renderModeSettings = renderModeSettings;
        this.chatStorageSettings = chatStorageSettings;
        this.storageRestartPrompt = storageRestartPrompt == null ? this::showStorageBackendChangePrompt : storageRestartPrompt;

        JPanel form = createFormPanel("General");
        GridBagConstraints gbc = createFormConstraints();
        int row = 0;

        JCheckBox menuBarEnabled = new JCheckBox();
        menuBarEnabled.setName("menuBarEnabledCheckBox");
        row = addCheckBoxRow(form, gbc, row, menuBarEnabled, "Enable menu bar");
        bindTypedCheckBox(menuBarEnabled, () -> chatBehaviorSettings.menuBarEnabled(SystemInfo.isMacOS),
                chatBehaviorSettings::persistMenuBarEnabled, SystemInfo.isMacOS, "menu bar");

        row = addSectionHeader(form, gbc, row, "Chat Behavior");
        JComboBox<String> sendKey = withPreferredWidth(new JComboBox<>(new String[]{SEND_ENTER, SEND_CTRL_ENTER}), 220);
        sendKey.setName("sendKeyComboBox");
        addRow(form, gbc, row++, "Send message with", sendKey);
        bindTypedComboBox(sendKey, chatBehaviorSettings::sendKey, chatBehaviorSettings::persistSendKey, SEND_ENTER,
                Validators.oneOf(Set.of(SEND_ENTER, SEND_CTRL_ENTER), "Invalid send key option"), "send key");

        JCheckBox autoScroll = new JCheckBox();
        autoScroll.setName("autoScrollCheckBox");
        row = addCheckBoxRow(form, gbc, row, autoScroll, "Scroll chat to bottom");
        bindTypedCheckBox(autoScroll, chatBehaviorSettings::autoScrollEnabled,
                chatBehaviorSettings::persistAutoScrollEnabled, true, "auto-scroll");

        JComboBox<String> renderModeDefault = withPreferredWidth(new JComboBox<>(renderModeSettingValues()), 220);
        renderModeDefault.setName("renderModeDefaultComboBox");
        renderModeDefault.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    JList<?> list,
                    Object value,
                    int index,
                    boolean isSelected,
                    boolean cellHasFocus
            ) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list,
                        value,
                        index,
                        isSelected,
                        cellHasFocus
                );
                if (value instanceof String modeValue) {
                    label.setText(renderModeDisplayName(modeValue));
                }
                return label;
            }
        });
        addRow(form, gbc, row++, "Message display mode", renderModeDefault);
        bindTypedComboBox(renderModeDefault, renderModeSettings::readDefaultModeValue,
                renderModeSettings::persistDefaultModeValue, RenderMode.PREVIEW.settingValue(), renderModeValidator(),
                "message display mode");
        row = addSectionHint(form, gbc, row, "Chat settings are applied immediately.");

        row = addSectionHeader(form, gbc, row, "Storage");
        JComboBox<StorageBackend> storageBackend = withPreferredWidth(new JComboBox<>(StorageBackend.values()), 220);
        storageBackend.setName("storageBackendComboBox");
        addRow(form, gbc, row++, "Chat storage", storageBackend);
        bindStorageBackend(storageBackend);
        row = addSectionHint(form, gbc, row, "Changing storage requires a restart. Existing chats will be migrated automatically.");
        addVerticalSpacer(form, gbc, row);
    }

    private void bindTypedCheckBox(JCheckBox checkBox, Supplier<Boolean> reader, Consumer<Boolean> writer,
            boolean defaultValue, String settingName) {
        boolean initialValue = readTypedSetting(reader, defaultValue, settingName);
        checkBox.setSelected(initialValue);
        checkBox.addActionListener(e -> {
            boolean selected = checkBox.isSelected();
            enqueueSave(
                    settingName,
                    () -> writer.accept(selected),
                    () -> {
                    },
                    () -> setStatusInfo(STATUS_SAVED)
            );
        });
    }

    private void bindTypedComboBox(JComboBox<String> comboBox, Supplier<String> reader, Consumer<String> writer,
            String defaultValue, SettingsValidator<String> validator, String settingName) {
        String storedValue = readTypedSetting(reader, defaultValue, settingName);
        ValidationResult<String> initialResult = validate(validator, storedValue);
        String initialValue = initialResult.valid() ? initialResult.normalizedValue() : defaultValue;
        comboBox.setSelectedItem(initialValue);
        AtomicBoolean updating = new AtomicBoolean();
        AtomicReference<String> persistedValue = new AtomicReference<>(initialValue);
        if (!initialResult.valid()) {
            setStatusError(initialResult.message());
            enqueueSave(
                    settingName,
                    () -> writer.accept(initialValue),
                    () -> persistedValue.set(initialValue),
                    () -> {
                    }
            );
        }
        comboBox.addActionListener(e -> {
            if (updating.get()) {
                return;
            }
            Object selected = comboBox.getSelectedItem();
            if (!(selected instanceof String rawValue)) {
                return;
            }
            ValidationResult<String> result = validate(validator, rawValue);
            if (!result.valid()) {
                updating.set(true);
                comboBox.setSelectedItem(persistedValue.get());
                updating.set(false);
                setStatusError(result.message());
                return;
            }
            String value = result.normalizedValue();
            enqueueSave(
                    settingName,
                    () -> writer.accept(value),
                    () -> persistedValue.set(value),
                    () -> {
                        if (!value.equals(rawValue)) {
                            updating.set(true);
                            comboBox.setSelectedItem(value);
                            updating.set(false);
                        }
                        setStatusInfo(STATUS_SAVED);
                    }
            );
        });
    }

    private void bindStorageBackend(JComboBox<StorageBackend> comboBox) {
        PersistenceBackendConfig config = readStorageConfig();
        StorageBackend activeBackend = config.activeBackend();
        StorageBackend initialBackend = config.pendingMigrationTarget().orElse(activeBackend);
        AtomicBoolean updating = new AtomicBoolean(true);
        comboBox.setSelectedItem(initialBackend);
        updating.set(false);
        comboBox.addActionListener(e -> {
            if (updating.get()) {
                return;
            }
            Object selected = comboBox.getSelectedItem();
            if (!(selected instanceof StorageBackend backend)) {
                return;
            }
            enqueueStorageSave(comboBox, updating, activeBackend, backend);
        });
    }

    private void enqueueStorageSave(
            JComboBox<StorageBackend> comboBox,
            AtomicBoolean updating,
            StorageBackend activeBackend,
            StorageBackend backend
    ) {
        deferredStorageAction = null;
        enqueueSave(
                "chat storage",
                () -> chatStorageSettings.requestBackend(backend),
                () -> {
                },
                () -> {
                    if (backend == activeBackend) {
                        setStatusInfo(STATUS_SAVED);
                        return;
                    }
                    Runnable storageAction = () -> showStorageRestartPrompt(
                            comboBox,
                            updating,
                            activeBackend,
                            backend
                    );
                    if (failedRequests.isEmpty()) {
                        deferredStorageAction = null;
                        storageAction.run();
                    } else {
                        deferredStorageAction = new DeferredStorageAction(storageAction);
                    }
                }
        );
    }

    private void showStorageRestartPrompt(
            JComboBox<StorageBackend> comboBox,
            AtomicBoolean updating,
            StorageBackend activeBackend,
            StorageBackend backend
    ) {
        setStatusInfo("Saved — restart required");
        RestartRequiredDialog.Choice choice = storageRestartPrompt.show(activeBackend, backend);
        if (choice == RestartRequiredDialog.Choice.EXIT_NOW) {
            exitAction.run();
        } else if (choice == RestartRequiredDialog.Choice.CANCEL) {
            enqueueSave(
                    "chat storage",
                    () -> chatStorageSettings.requestBackend(activeBackend),
                    () -> {
                    },
                    () -> {
                        updating.set(true);
                        comboBox.setSelectedItem(activeBackend);
                        updating.set(false);
                        setStatusInfo(STATUS_SAVED);
                    }
            );
        }
    }

    private void enqueueSave(String target, Runnable mutation, Runnable onDurableSuccess, Runnable onCurrentSuccess) {
        enqueueSave(target, mutation, onDurableSuccess, onCurrentSuccess, false);
    }

    private void enqueueSave(
            String target,
            Runnable mutation,
            Runnable onDurableSuccess,
            Runnable onCurrentSuccess,
            boolean retry
    ) {
        if (disposed) {
            return;
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        SaveRequest request = new SaveRequest(mutation, onDurableSuccess, onCurrentSuccess, completion);
        latestRequests.put(target, request);
        if (!retry) {
            failedRequests.remove(target);
            refreshLastSaveError();
        }
        writeQueue.submit(mutation).whenComplete((ignored, error) ->
                SwingUtilities.invokeLater(() -> finishSave(target, request, error)));
    }

    private void finishSave(String target, SaveRequest request, Throwable writeError) {
        Error writeFatalError = SettingsWriteQueue.fatalError(writeError);
        Throwable callbackError = null;
        Error deferredFatalError = null;
        try {
            if (writeError == null) {
                request.onDurableSuccess().run();
            }
            boolean current = latestRequests.get(target) == request;
            if (current) {
                if (writeError == null) {
                    failedRequests.remove(target);
                    refreshLastSaveError();
                    if (!disposed) {
                        try {
                            request.onCurrentSuccess().run();
                        } catch (Throwable t) {
                            callbackError = t;
                            showFollowUpFailure(target);
                        }
                    }
                } else {
                    failedRequests.put(target, request);
                    refreshLastSaveError();
                }
                if (!disposed && !failedRequests.isEmpty()) {
                    setStatusError(lastSaveError);
                } else if (!disposed) {
                    deferredFatalError = runDeferredStorageAction();
                }
            }
        } catch (Throwable t) {
            callbackError = t;
            if (latestRequests.get(target) == request) {
                if (writeError == null) {
                    failedRequests.remove(target);
                    refreshLastSaveError();
                    if (!disposed) {
                        showFollowUpFailure(target);
                    }
                } else {
                    failedRequests.put(target, request);
                    refreshLastSaveError();
                    if (!disposed) {
                        setStatusError(lastSaveError);
                    }
                }
            }
        } finally {
            request.completion().complete(null);
        }
        if (writeFatalError != null) {
            throw writeFatalError;
        }
        if (callbackError instanceof Error error && !(error instanceof LinkageError)) {
            throw error;
        }
        if (deferredFatalError != null) {
            throw deferredFatalError;
        }
    }

    private void showFollowUpFailure(String target) {
        if (failedRequests.isEmpty()) {
            setStatusError("%s was saved, but the follow-up action failed".formatted(target));
        }
    }

    @Override
    public CompletableFuture<Boolean> savePendingChangesAsync() {
        List.copyOf(failedRequests.entrySet()).forEach(entry -> {
            SaveRequest request = entry.getValue();
            enqueueSave(
                    entry.getKey(),
                    request.mutation(),
                    request.onDurableSuccess(),
                    request.onCurrentSuccess(),
                    true
            );
        });
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        awaitStableSaves(result);
        return result;
    }

    private Error runDeferredStorageAction() {
        DeferredStorageAction deferred = deferredStorageAction;
        if (deferred == null) {
            return null;
        }
        try {
            deferred.action().run();
            if (deferredStorageAction == deferred) {
                deferredStorageAction = null;
            }
            return null;
        } catch (Throwable t) {
            if (deferredStorageAction == deferred) {
                deferredStorageAction = null;
            }
            if (!disposed) {
                setStatusError("Chat storage was saved, but the restart prompt failed");
            }
            return t instanceof Error error && !(error instanceof LinkageError) ? error : null;
        }
    }

    private void refreshLastSaveError() {
        lastSaveError = failedRequests.keySet().stream()
                .findFirst()
                .map(target -> "Failed to save %s setting".formatted(target))
                .orElse("");
    }

    @Override
    public String lastSaveError() {
        return lastSaveError;
    }

    @Override
    public String settingsSectionName() {
        return "General settings";
    }

    void disposePanel() {
        if (disposed) {
            return;
        }
        disposed = true;
        deferredStorageAction = null;
        writeQueue.close();
        disposeSettingsPanel();
    }

    private void awaitStableSaves(CompletableFuture<Boolean> result) {
        Set<CompletableFuture<Void>> observed = latestRequests.values().stream()
                .map(SaveRequest::completion)
                .collect(toSet());
        CompletableFuture.allOf(observed.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                SwingUtilities.invokeLater(() -> {
                    Set<CompletableFuture<Void>> current = latestRequests.values().stream()
                            .map(SaveRequest::completion)
                            .collect(toSet());
                    if (!current.equals(observed)) {
                        awaitStableSaves(result);
                    } else {
                        result.complete(failedRequests.isEmpty());
                    }
                }));
    }

    private <T> T readTypedSetting(Supplier<T> reader, T defaultValue, String settingName) {
        try {
            T value = reader.get();
            return value != null ? value : defaultValue;
        } catch (Exception e) {
            setStatusError("Failed to read %s setting".formatted(settingName));
            return defaultValue;
        }
    }

    private PersistenceBackendConfig readStorageConfig() {
        try {
            return chatStorageSettings.load();
        } catch (Exception e) {
            setStatusError("Failed to read chat storage setting");
            return new PersistenceBackendConfig(PersistenceBackendConfig.DEFAULT_BACKEND, null);
        }
    }

    private RestartRequiredDialog.Choice showStorageBackendChangePrompt(StorageBackend activeBackend, StorageBackend selectedBackend) {
        return RestartRequiredDialog.show(this,
                "Chat storage will switch from %s to %s after you reopen Chat4J. Existing chats will be migrated automatically."
                        .formatted(activeBackend.displayName(), selectedBackend.displayName()));
    }

    private String[] renderModeSettingValues() {
        return Arrays.stream(RenderMode.values()).map(RenderMode::settingValue).toArray(String[]::new);
    }

    private SettingsValidator<String> renderModeValidator() {
        return value -> renderModeSettings.normalizeSettingValue(value).map(ValidationResult::valid)
                .orElseGet(() -> ValidationResult.invalid("Invalid markdown render mode", RenderMode.PREVIEW.settingValue()));
    }

    private String renderModeDisplayName(String settingValue) {
        return renderModeSettings.parseMode(settingValue).map(RenderMode::displayName).orElse(RenderMode.PREVIEW.displayName());
    }

    private record SaveRequest(
            Runnable mutation,
            Runnable onDurableSuccess,
            Runnable onCurrentSuccess,
            CompletableFuture<Void> completion
    ) {
    }

    private record DeferredStorageAction(Runnable action) {
    }

    @FunctionalInterface
    interface StorageRestartPrompt {
        RestartRequiredDialog.Choice show(StorageBackend activeBackend, StorageBackend selectedBackend);
    }
}
