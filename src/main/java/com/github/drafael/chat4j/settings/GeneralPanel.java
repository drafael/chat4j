package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

public class GeneralPanel extends AbstractSettingsPanel {

    private static final String KEY_SEND = SettingsKeys.CHAT_SEND_KEY;
    private static final String KEY_AUTO_SCROLL = SettingsKeys.CHAT_AUTO_SCROLL;
    private static final String KEY_ASSISTANT_MARKDOWN_DEFAULT = SettingsKeys.CHAT_RENDER_MODE;
    private static final String KEY_MENU_BAR_ENABLED = SettingsKeys.MENU_BAR_ENABLED;

    private static final String SEND_ENTER = "Enter";
    private static final String SEND_CTRL_ENTER = "Ctrl+Enter";
    private static final String MARKDOWN_MODE_PREVIEW = SettingsKeys.CHAT_RENDER_MODE_PREVIEW;
    private static final String MARKDOWN_MODE_MARKDOWN = SettingsKeys.CHAT_RENDER_MODE_MARKDOWN;

    public GeneralPanel(SettingsRepo settingsRepo) {
        super(settingsRepo);

        JPanel form = createFormPanel("General");
        GridBagConstraints gbc = createFormConstraints();

        int row = 0;

        JComboBox<String> language = new JComboBox<>(new String[]{"English"});
        addRow(form, gbc, row++, "Language", language);

        JComboBox<String> sendKey = new JComboBox<>(new String[]{SEND_ENTER, SEND_CTRL_ENTER});
        addRow(form, gbc, row++, "Send message with", sendKey);
        bindComboBox(
                sendKey,
                KEY_SEND,
                SEND_ENTER,
                Validators.oneOf(Set.of(SEND_ENTER, SEND_CTRL_ENTER), "Invalid send key option"),
                null
        );

        JCheckBox autoScroll = new JCheckBox();
        addRow(form, gbc, row++, "Scroll chat to bottom", autoScroll);
        bindCheckBox(autoScroll, KEY_AUTO_SCROLL, true, null);

        JCheckBox menuBarEnabled = new JCheckBox();
        addRow(form, gbc, row++, "Enable menu bar", menuBarEnabled);
        bindCheckBox(menuBarEnabled, KEY_MENU_BAR_ENABLED, SystemInfo.isMacOS, null);

        JComboBox<String> markdownDefault = new JComboBox<>(new String[]{
                MARKDOWN_MODE_PREVIEW,
                MARKDOWN_MODE_MARKDOWN
        });
        markdownDefault.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list,
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
                    label.setText(MARKDOWN_MODE_MARKDOWN.equalsIgnoreCase(modeValue) ? "MARKDOWN" : "PREVIEW");
                }
                return label;
            }
        });
        addRow(form, gbc, row++, "Assistant display mode", markdownDefault);
        bindComboBox(
                markdownDefault,
                KEY_ASSISTANT_MARKDOWN_DEFAULT,
                MARKDOWN_MODE_PREVIEW,
                Validators.oneOf(
                        Set.of(MARKDOWN_MODE_PREVIEW, MARKDOWN_MODE_MARKDOWN),
                        "Invalid markdown render mode"
                ),
                null
        );

        addVerticalSpacer(form, gbc, row);
    }
}
