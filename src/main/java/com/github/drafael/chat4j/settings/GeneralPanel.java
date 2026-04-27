package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.formdev.flatlaf.util.SystemInfo;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Arrays;
import java.util.Set;

public class GeneralPanel extends AbstractSettingsPanel {

    private static final String KEY_SEND = SettingsKeys.CHAT_SEND_KEY;
    private static final String KEY_AUTO_SCROLL = SettingsKeys.CHAT_AUTO_SCROLL;
    private static final String KEY_ASSISTANT_MARKDOWN_DEFAULT = SettingsKeys.CHAT_RENDER_MODE;
    private static final String KEY_MENU_BAR_ENABLED = SettingsKeys.MENU_BAR_ENABLED;
    private static final String KEY_AGENT_SYSTEM_PROMPT_APPEND = SettingsKeys.CHAT_AGENT_SYSTEM_PROMPT_APPEND;

    private static final String SEND_ENTER = "Enter";
    private static final String SEND_CTRL_ENTER = "Ctrl+Enter";

    public GeneralPanel(SettingsRepo settingsRepo) {
        super(settingsRepo);

        JPanel form = createFormPanel("General");
        GridBagConstraints gbc = createFormConstraints();

        int row = 0;

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

        JComboBox<String> markdownDefault = withPreferredWidth(
                new JComboBox<>(assistantModeSettingValues()),
                220
        );
        markdownDefault.setRenderer(new DefaultListCellRenderer() {
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
                    label.setText(assistantModeDisplayName(modeValue));
                }
                return label;
            }
        });
        addRow(form, gbc, row++, "Assistant display mode", markdownDefault);
        bindComboBox(
                markdownDefault,
                KEY_ASSISTANT_MARKDOWN_DEFAULT,
                AssistantRenderMode.PREVIEW.settingValue(),
                assistantModeValidator(),
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

        row = addSectionHeader(form, gbc, row, "Application");

        JComboBox<String> language = withPreferredWidth(new JComboBox<>(new String[]{"English"}), 220);
        language.setEnabled(false);
        addRow(form, gbc, row++, "Language", language);

        JCheckBox menuBarEnabled = new JCheckBox();
        row = addCheckBoxRow(form, gbc, row, menuBarEnabled, "Enable menu bar");
        bindCheckBox(menuBarEnabled, KEY_MENU_BAR_ENABLED, SystemInfo.isMacOS, null);

        addVerticalSpacer(form, gbc, row);
    }

    private static String[] assistantModeSettingValues() {
        return Arrays.stream(AssistantRenderMode.values())
                .map(AssistantRenderMode::settingValue)
                .toArray(String[]::new);
    }

    private SettingsValidator<String> assistantModeValidator() {
        return value -> {
            AssistantRenderMode mode = assistantModeFromValue(value);
            if (mode == null) {
                return ValidationResult.invalid("Invalid markdown render mode", AssistantRenderMode.PREVIEW.settingValue());
            }

            return ValidationResult.valid(mode.settingValue());
        };
    }

    private static String assistantModeDisplayName(String settingValue) {
        AssistantRenderMode mode = assistantModeFromValue(settingValue);
        return mode != null ? mode.displayName() : AssistantRenderMode.PREVIEW.displayName();
    }

    private static AssistantRenderMode assistantModeFromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }

        String normalized = value.trim();
        return Arrays.stream(AssistantRenderMode.values())
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
