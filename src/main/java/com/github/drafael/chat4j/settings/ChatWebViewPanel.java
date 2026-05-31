package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.message.ChatWebViewEngine;
import com.github.drafael.chat4j.chat.message.ChatWebViewRuntimeStatus;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.Set;

import static java.util.stream.Collectors.toSet;

public class ChatWebViewPanel extends AbstractSettingsPanel {

    private final ChatWebViewRuntimeStatus runtimeStatus;
    private final JLabel configuredEngineValue = new JLabel();
    private final JLabel activeEngineValue = new JLabel();
    private final JLabel swingWebViewAvailableValue = new JLabel();
    private final JLabel swingWebViewModeValue = new JLabel();
    private final JLabel fallbackReasonValue = new JLabel();
    private final JLabel restartHint = new JLabel("Changes apply after restarting Chat4J.");

    public ChatWebViewPanel(SettingsRepo settingsRepo, ChatWebViewRuntimeStatus runtimeStatus) {
        super(settingsRepo);
        this.runtimeStatus = runtimeStatus;

        JPanel form = createFormPanel("Chat WebView");
        GridBagConstraints gbc = createFormConstraints();

        int row = 0;
        row = addSectionHeader(form, gbc, row, "Rendering Engine");

        JComboBox<String> engineComboBox = withPreferredWidth(new JComboBox<>(engineSettingValues()), 220);
        engineComboBox.setName("chatWebViewEngineComboBox");
        engineComboBox.setRenderer(new EngineRenderer());
        addRow(form, gbc, row++, "Engine", engineComboBox);
        bindComboBox(
                engineComboBox,
                SettingsKeys.CHAT_WEB_VIEW_ENGINE,
                runtimeStatus.configuredEngine().settingValue(),
                engineValidator(),
                value -> {
                    refreshConfiguredEngine(value);
                    refreshRestartHint(value);
                    setStatusInfo("Saved — restart Chat4J to apply");
                }
        );

        row = addFullWidthRow(form, gbc, row, restartHint);

        row = addSectionHeader(form, gbc, row, "Diagnostics");
        addRow(form, gbc, row++, "Configured engine", configuredEngineValue);
        addRow(form, gbc, row++, "Active engine", activeEngineValue);
        addRow(form, gbc, row++, "SwingWebView available", swingWebViewAvailableValue);
        addRow(form, gbc, row++, "SwingWebView mode", swingWebViewModeValue);
        addRow(form, gbc, row++, "Fallback reason", fallbackReasonValue);

        addVerticalSpacer(form, gbc, row);

        refreshDiagnostics(readString(
                SettingsKeys.CHAT_WEB_VIEW_ENGINE,
                runtimeStatus.configuredEngine().settingValue()
        ));
    }

    private SettingsValidator<String> engineValidator() {
        Set<String> values = Arrays.stream(ChatWebViewEngine.values())
                .map(ChatWebViewEngine::settingValue)
                .collect(toSet());
        return Validators.oneOf(values, "Invalid chat WebView engine");
    }

    private String[] engineSettingValues() {
        return Arrays.stream(ChatWebViewEngine.values())
                .map(ChatWebViewEngine::settingValue)
                .toArray(String[]::new);
    }

    private void refreshDiagnostics(String configuredValue) {
        refreshConfiguredEngine(configuredValue);
        activeEngineValue.setText(runtimeStatus.activeEngine().displayName());
        swingWebViewAvailableValue.setText(runtimeStatus.swingWebViewAvailable() ? "Yes" : "No");
        swingWebViewModeValue.setText(StringUtils.defaultIfBlank(runtimeStatus.swingWebViewMode(), "Unknown"));
        fallbackReasonValue.setText(StringUtils.defaultIfBlank(runtimeStatus.fallbackReason(), "None"));
        refreshRestartHint(configuredValue);
    }

    private void refreshConfiguredEngine(String configuredValue) {
        configuredEngineValue.setText(ChatWebViewEngine.fromSettingValue(configuredValue).displayName());
    }

    private void refreshRestartHint(String configuredValue) {
        ChatWebViewEngine configuredEngine = ChatWebViewEngine.fromSettingValue(configuredValue);
        if (runtimeStatus.hasFallback()) {
            restartHint.setText("Chat4J is using %s for this session. See diagnostics below."
                    .formatted(runtimeStatus.activeEngine().displayName()));
            return;
        }
        if (configuredEngine != runtimeStatus.activeEngine()) {
            restartHint.setText("Restart required: currently using %s.".formatted(runtimeStatus.activeEngine().displayName()));
            return;
        }
        restartHint.setText("Changes apply after restarting Chat4J.");
    }

    private static final class EngineRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof String settingValue) {
                label.setText(ChatWebViewEngine.fromSettingValue(settingValue).displayName());
            }
            return label;
        }
    }
}
