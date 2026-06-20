package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.db.PersistenceBackendConfig;
import com.github.drafael.chat4j.persistence.db.StorageBackend;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.commons.lang3.StringUtils;

public class GeneralPanel extends AbstractSettingsPanel {

    private static final String KEY_SEND = SettingsKeys.CHAT_SEND_KEY;
    private static final String KEY_AUTO_SCROLL = SettingsKeys.CHAT_AUTO_SCROLL;
    private static final String KEY_RENDER_MODE_DEFAULT = SettingsKeys.CHAT_RENDER_MODE;
    private static final String KEY_MENU_BAR_ENABLED = SettingsKeys.MENU_BAR_ENABLED;
    private static final String KEY_AGENT_SYSTEM_PROMPT_APPEND = SettingsKeys.CHAT_AGENT_SYSTEM_PROMPT_APPEND;

    private static final String SEND_ENTER = "Enter";
    private static final String SEND_CTRL_ENTER = "Ctrl+Enter";

    private final Runnable exitAction;

    public GeneralPanel(SettingsRepository settingsRepo) {
        this(settingsRepo, () -> System.exit(0));
    }

    public GeneralPanel(SettingsRepository settingsRepo, Runnable exitAction) {
        super(settingsRepo);
        this.exitAction = exitAction == null ? () -> System.exit(0) : exitAction;

        JPanel form = createFormPanel("General");
        GridBagConstraints gbc = createFormConstraints();

        int row = 0;

        JCheckBox menuBarEnabled = new JCheckBox();
        row = addCheckBoxRow(form, gbc, row, menuBarEnabled, "Enable menu bar");
        bindCheckBox(menuBarEnabled, KEY_MENU_BAR_ENABLED, SystemInfo.isMacOS, null);

        row = addSectionHeader(form, gbc, row, "Chat Behavior");

        JComboBox<String> sendKey = withPreferredWidth(new JComboBox<>(new String[]{SEND_ENTER, SEND_CTRL_ENTER}), 220);
        addRow(form, gbc, row++, "Send message with", sendKey);
        bindComboBox(
                sendKey,
                KEY_SEND,
                SEND_ENTER,
                Validators.oneOf(Set.of(SEND_ENTER, SEND_CTRL_ENTER), "Invalid send key option"),
                null
        );

        JCheckBox autoScroll = new JCheckBox();
        row = addCheckBoxRow(form, gbc, row, autoScroll, "Scroll chat to bottom");
        bindCheckBox(autoScroll, KEY_AUTO_SCROLL, true, null);

        JComboBox<String> renderModeDefault = withPreferredWidth(
                new JComboBox<>(renderModeSettingValues()),
                220
        );
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
        bindComboBox(
                renderModeDefault,
                KEY_RENDER_MODE_DEFAULT,
                RenderMode.PREVIEW.settingValue(),
                renderModeValidator(),
                null
        );
        row = addSectionHint(form, gbc, row, "Chat settings are applied immediately.");

        row = addSectionHeader(form, gbc, row, "Agent Mode");

        JTextArea agentPromptAppendArea = new JTextArea(6, 40);
        agentPromptAppendArea.setName("agentSystemPromptAppendArea");
        agentPromptAppendArea.setLineWrap(true);
        agentPromptAppendArea.setWrapStyleWord(true);
        agentPromptAppendArea.setText(readString(KEY_AGENT_SYSTEM_PROMPT_APPEND, ""));

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
        addRow(form, gbc, row++, "Chat storage", storageBackend);
        bindStorageBackend(storageBackend);
        row = addSectionHint(form, gbc, row, "Changing storage requires a restart. Existing chats will be migrated automatically.");

        addVerticalSpacer(form, gbc, row);
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
                removeSetting(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING);
                setStatusInfo(STATUS_SAVED);
                return;
            }

            writeSetting(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING, backend.settingValue());
            setStatusInfo("Saved — restart required");
            RestartRequiredDialog.Choice choice = showStorageBackendChangePrompt(activeBackend, backend);

            if (choice == RestartRequiredDialog.Choice.EXIT_NOW) {
                exitAction.run();
                return;
            }
            if (choice == RestartRequiredDialog.Choice.CANCEL) {
                removeSetting(SettingsKeys.CHAT_STORAGE_BACKEND_PENDING);
                updating.set(true);
                storageBackend.setSelectedItem(activeBackend);
                updating.set(false);
                setStatusInfo(STATUS_SAVED);
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
            return PersistenceBackendConfig.load(settingsRepo());
        } catch (Exception e) {
            setStatusError("Failed to read chat storage setting");
            return new PersistenceBackendConfig(PersistenceBackendConfig.DEFAULT_BACKEND, null);
        }
    }

    private static String[] renderModeSettingValues() {
        return Arrays.stream(RenderMode.values())
                .map(RenderMode::settingValue)
                .toArray(String[]::new);
    }

    private SettingsValidator<String> renderModeValidator() {
        return value -> {
            RenderMode mode = renderModeFromValue(value);
            if (mode == null) {
                return ValidationResult.invalid("Invalid markdown render mode", RenderMode.PREVIEW.settingValue());
            }

            return ValidationResult.valid(mode.settingValue());
        };
    }

    private static String renderModeDisplayName(String settingValue) {
        RenderMode mode = renderModeFromValue(settingValue);
        return mode != null ? mode.displayName() : RenderMode.PREVIEW.displayName();
    }

    private static RenderMode renderModeFromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }

        String normalized = value.trim();
        return Arrays.stream(RenderMode.values())
                .filter(mode -> mode.settingValue().equalsIgnoreCase(normalized) || mode.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
    }

    private void persistPromptAppendArea(JTextArea textArea) {
        Runnable persist = () -> {
            String value = textArea.getText();
            if (StringUtils.isBlank(value)) {
                removeSetting(KEY_AGENT_SYSTEM_PROMPT_APPEND);
            } else {
                writeSetting(KEY_AGENT_SYSTEM_PROMPT_APPEND, value);
            }
            setStatusInfo(STATUS_SAVED);
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
                persist.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                persist.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                persist.run();
            }
        });
    }

}
