package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.db.ChatStorageSettings;
import com.github.drafael.chat4j.persistence.db.PersistenceBackendConfig;
import com.github.drafael.chat4j.persistence.db.StorageBackend;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Arrays;
import java.util.Set;
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
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class GeneralPanel extends AbstractSettingsPanel {

    private static final String SEND_ENTER = ChatBehaviorSettings.SEND_ENTER;
    private static final String SEND_CTRL_ENTER = ChatBehaviorSettings.SEND_CTRL_ENTER;

    private final Runnable exitAction;
    private final ChatBehaviorSettings chatBehaviorSettings;
    private final RenderModeSettings renderModeSettings;
    private final AgentModeSettings agentModeSettings;
    private final ChatStorageSettings chatStorageSettings;
    private final StorageRestartPrompt storageRestartPrompt;

    public GeneralPanel(SettingsRepository settingsRepo) {
        this(settingsRepo, () -> System.exit(0));
    }

    public GeneralPanel(SettingsRepository settingsRepo, Runnable exitAction) {
        this(
                settingsRepo,
                exitAction,
                new ChatBehaviorSettings(settingsRepo),
                new RenderModeSettings(settingsRepo),
                new AgentModeSettings(settingsRepo),
                new ChatStorageSettings(settingsRepo),
                null
        );
    }

    GeneralPanel(
            SettingsRepository settingsRepo,
            Runnable exitAction,
            ChatBehaviorSettings chatBehaviorSettings,
            RenderModeSettings renderModeSettings,
            AgentModeSettings agentModeSettings,
            ChatStorageSettings chatStorageSettings,
            StorageRestartPrompt storageRestartPrompt
    ) {
        super(settingsRepo);
        this.exitAction = exitAction == null ? () -> System.exit(0) : exitAction;
        this.chatBehaviorSettings = chatBehaviorSettings;
        this.renderModeSettings = renderModeSettings;
        this.agentModeSettings = agentModeSettings;
        this.chatStorageSettings = chatStorageSettings;
        this.storageRestartPrompt = storageRestartPrompt == null ? this::showStorageBackendChangePrompt : storageRestartPrompt;

        JPanel form = createFormPanel("General");
        GridBagConstraints gbc = createFormConstraints();

        int row = 0;

        JCheckBox menuBarEnabled = new JCheckBox();
        menuBarEnabled.setName("menuBarEnabledCheckBox");
        row = addCheckBoxRow(form, gbc, row, menuBarEnabled, "Enable menu bar");
        bindTypedCheckBox(
                menuBarEnabled,
                () -> chatBehaviorSettings.menuBarEnabled(SystemInfo.isMacOS),
                chatBehaviorSettings::persistMenuBarEnabled,
                SystemInfo.isMacOS,
                "menu bar"
        );

        row = addSectionHeader(form, gbc, row, "Chat Behavior");

        JComboBox<String> sendKey = withPreferredWidth(new JComboBox<>(new String[]{SEND_ENTER, SEND_CTRL_ENTER}), 220);
        sendKey.setName("sendKeyComboBox");
        addRow(form, gbc, row++, "Send message with", sendKey);
        bindTypedComboBox(
                sendKey,
                chatBehaviorSettings::sendKey,
                chatBehaviorSettings::persistSendKey,
                SEND_ENTER,
                Validators.oneOf(Set.of(SEND_ENTER, SEND_CTRL_ENTER), "Invalid send key option"),
                "send key"
        );

        JCheckBox autoScroll = new JCheckBox();
        autoScroll.setName("autoScrollCheckBox");
        row = addCheckBoxRow(form, gbc, row, autoScroll, "Scroll chat to bottom");
        bindTypedCheckBox(
                autoScroll,
                chatBehaviorSettings::autoScrollEnabled,
                chatBehaviorSettings::persistAutoScrollEnabled,
                true,
                "auto-scroll"
        );

        JComboBox<String> renderModeDefault = withPreferredWidth(
                new JComboBox<>(renderModeSettingValues()),
                220
        );
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
        bindTypedComboBox(
                renderModeDefault,
                renderModeSettings::readDefaultModeValue,
                renderModeSettings::persistDefaultModeValue,
                RenderMode.PREVIEW.settingValue(),
                renderModeValidator(),
                "message display mode"
        );
        row = addSectionHint(form, gbc, row, "Chat settings are applied immediately.");

        row = addSectionHeader(form, gbc, row, "Agent Mode");

        JTextArea agentPromptAppendArea = new JTextArea(6, 40);
        agentPromptAppendArea.setName("agentSystemPromptAppendArea");
        agentPromptAppendArea.setLineWrap(true);
        agentPromptAppendArea.setWrapStyleWord(true);
        agentPromptAppendArea.setText(readPromptAppend());

        JScrollPane agentPromptScrollPane = new JScrollPane(agentPromptAppendArea);
        agentPromptScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        agentPromptScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        agentPromptScrollPane.setPreferredSize(new Dimension(420, 130));

        addRow(form, gbc, row++, "Prompt addendum", agentPromptScrollPane);

        persistPromptAppendArea(agentPromptAppendArea);

        row = addSectionHint(form, gbc, row, "Prompt addendum is appended to the default Agent Mode system prompt.");

        row = addSectionHeader(form, gbc, row, "Storage");

        JComboBox<StorageBackend> storageBackend = withPreferredWidth(
                new JComboBox<>(StorageBackend.values()),
                220
        );
        storageBackend.setName("storageBackendComboBox");
        addRow(form, gbc, row++, "Chat storage", storageBackend);
        bindStorageBackend(storageBackend);
        row = addSectionHint(form, gbc, row, "Changing storage requires a restart. Existing chats will be migrated automatically.");

        addVerticalSpacer(form, gbc, row);
    }

    private void bindTypedCheckBox(
            JCheckBox checkBox,
            Supplier<Boolean> reader,
            Consumer<Boolean> writer,
            boolean defaultValue,
            String settingName
    ) {
        checkBox.setSelected(readTypedSetting(reader, defaultValue, settingName));

        checkBox.addActionListener(e -> {
            boolean selected = checkBox.isSelected();
            try {
                writer.accept(selected);
                setStatusInfo(STATUS_SAVED);
            } catch (Exception ex) {
                setStatusError("Failed to save %s setting".formatted(settingName));
            }
        });
    }

    private void bindTypedComboBox(
            JComboBox<String> comboBox,
            Supplier<String> reader,
            Consumer<String> writer,
            String defaultValue,
            SettingsValidator<String> validator,
            String settingName
    ) {
        String storedValue = readTypedSetting(reader, defaultValue, settingName);
        ValidationResult<String> initialResult = validate(validator, storedValue);
        String initialValue = initialResult.valid() ? initialResult.normalizedValue() : defaultValue;

        if (!initialResult.valid()) {
            setStatusError(initialResult.message());
            persistTypedSetting(writer, initialValue, settingName);
        }

        comboBox.setSelectedItem(initialValue);

        AtomicBoolean updating = new AtomicBoolean(false);
        AtomicReference<String> lastValidValue = new AtomicReference<>(initialValue);

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
                comboBox.setSelectedItem(lastValidValue.get());
                updating.set(false);
                setStatusError(result.message());
                return;
            }

            String normalizedValue = result.normalizedValue();
            if (!persistTypedSetting(writer, normalizedValue, settingName)) {
                updating.set(true);
                comboBox.setSelectedItem(lastValidValue.get());
                updating.set(false);
                return;
            }

            lastValidValue.set(normalizedValue);
            setStatusInfo(STATUS_SAVED);

            if (!normalizedValue.equals(rawValue)) {
                updating.set(true);
                comboBox.setSelectedItem(normalizedValue);
                updating.set(false);
            }
        });
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

    private <T> boolean persistTypedSetting(Consumer<T> writer, T value, String settingName) {
        try {
            writer.accept(value);
            return true;
        } catch (Exception e) {
            setStatusError("Failed to save %s setting".formatted(settingName));
            return false;
        }
    }

    private void bindStorageBackend(JComboBox<StorageBackend> storageBackend) {
        PersistenceBackendConfig config = readStorageConfig();
        StorageBackend activeBackend = config.activeBackend();
        StorageBackend selectedBackend = config.pendingMigrationTarget().orElse(activeBackend);
        AtomicBoolean updating = new AtomicBoolean(true);
        storageBackend.setSelectedItem(selectedBackend);
        updating.set(false);

        storageBackend.addActionListener(e -> {
            if (updating.get()) {
                return;
            }

            Object selected = storageBackend.getSelectedItem();
            if (!(selected instanceof StorageBackend backend)) {
                return;
            }

            if (backend == activeBackend) {
                try {
                    chatStorageSettings.requestBackend(backend);
                    setStatusInfo(STATUS_SAVED);
                } catch (Exception ex) {
                    setStatusError("Failed to save chat storage setting");
                    updating.set(true);
                    storageBackend.setSelectedItem(selectedBackend);
                    updating.set(false);
                }
                return;
            }

            try {
                chatStorageSettings.requestBackend(backend);
                setStatusInfo("Saved — restart required");
            } catch (Exception ex) {
                setStatusError("Failed to save chat storage setting");
                updating.set(true);
                storageBackend.setSelectedItem(selectedBackend);
                updating.set(false);
                return;
            }

            RestartRequiredDialog.Choice choice = storageRestartPrompt.show(activeBackend, backend);

            if (choice == RestartRequiredDialog.Choice.EXIT_NOW) {
                exitAction.run();
                return;
            }
            if (choice == RestartRequiredDialog.Choice.CANCEL) {
                try {
                    chatStorageSettings.requestBackend(activeBackend);
                    updating.set(true);
                    storageBackend.setSelectedItem(activeBackend);
                    updating.set(false);
                    setStatusInfo(STATUS_SAVED);
                } catch (Exception ex) {
                    setStatusError("Failed to save chat storage setting");
                }
            }
        });
    }

    private RestartRequiredDialog.Choice showStorageBackendChangePrompt(StorageBackend activeBackend, StorageBackend selectedBackend) {
        return RestartRequiredDialog.show(
                this,
                "Chat storage will switch from %s to %s after you reopen Chat4J. Existing chats will be migrated automatically."
                        .formatted(activeBackend.displayName(), selectedBackend.displayName())
        );
    }

    private PersistenceBackendConfig readStorageConfig() {
        try {
            return chatStorageSettings.load();
        } catch (Exception e) {
            setStatusError("Failed to read chat storage setting");
            return new PersistenceBackendConfig(PersistenceBackendConfig.DEFAULT_BACKEND, null);
        }
    }

    private String readPromptAppend() {
        try {
            return agentModeSettings.resolveSystemPromptAppend();
        } catch (Exception e) {
            setStatusError("Failed to read prompt addendum setting");
            return "";
        }
    }

    private String[] renderModeSettingValues() {
        return Arrays.stream(RenderMode.values())
                .map(RenderMode::settingValue)
                .toArray(String[]::new);
    }

    private SettingsValidator<String> renderModeValidator() {
        return value -> renderModeSettings.normalizeSettingValue(value)
                .map(ValidationResult::valid)
                .orElseGet(() -> ValidationResult.invalid("Invalid markdown render mode", RenderMode.PREVIEW.settingValue()));
    }

    private String renderModeDisplayName(String settingValue) {
        return renderModeSettings.parseMode(settingValue)
                .map(RenderMode::displayName)
                .orElse(RenderMode.PREVIEW.displayName());
    }

    private void persistPromptAppendArea(JTextArea textArea) {
        Runnable persist = () -> {
            try {
                agentModeSettings.persistSystemPromptAppend(textArea.getText());
                setStatusInfo(STATUS_SAVED);
            } catch (Exception e) {
                setStatusError("Failed to save prompt addendum setting");
            }
        };

        textArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                persist.run();
            }
        });

        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                persistLater(persist);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                persistLater(persist);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                persistLater(persist);
            }
        });
    }

    private void persistLater(Runnable persist) {
        if (SwingUtilities.isEventDispatchThread()) {
            persist.run();
            return;
        }

        SwingUtilities.invokeLater(persist);
    }

    @FunctionalInterface
    interface StorageRestartPrompt {
        RestartRequiredDialog.Choice show(StorageBackend activeBackend, StorageBackend selectedBackend);
    }
}
