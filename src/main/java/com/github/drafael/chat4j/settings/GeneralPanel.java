package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.storage.SettingsRepo;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import java.awt.*;
import java.util.Set;

public class GeneralPanel extends AbstractSettingsPanel {

    private static final String KEY_SEND = "send.key";
    private static final String KEY_AUTO_SCROLL = "auto.scroll";
    private static final String KEY_ASSISTANT_MARKDOWN_DEFAULT = "chat.markdown.default";
    private static final String KEY_MENU_BAR_ENABLED = "menu.bar.enabled";

    private static final String SEND_ENTER = "Enter";
    private static final String SEND_CTRL_ENTER = "Ctrl+Enter";

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
                AssistantRenderMode.PREVIEW.settingValue(),
                AssistantRenderMode.MARKDOWN.settingValue()
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
                    label.setText(AssistantRenderMode.fromSettingValue(modeValue).displayName());
                }
                return label;
            }
        });
        addRow(form, gbc, row++, "Assistant display mode", markdownDefault);
        bindComboBox(
                markdownDefault,
                KEY_ASSISTANT_MARKDOWN_DEFAULT,
                AssistantRenderMode.PREVIEW.settingValue(),
                Validators.oneOf(
                        Set.of(
                                AssistantRenderMode.PREVIEW.settingValue(),
                                AssistantRenderMode.MARKDOWN.settingValue()
                        ),
                        "Invalid markdown mode"
                ),
                null
        );

        addVerticalSpacer(form, gbc, row);
    }
}
