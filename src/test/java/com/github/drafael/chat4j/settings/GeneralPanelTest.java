package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;
import com.github.drafael.chat4j.persistence.db.ChatStorageSettings;
import com.github.drafael.chat4j.persistence.db.StorageBackend;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class GeneralPanelTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Prompt addendum updates persist to Agent Mode system prompt setting")
    void updatePromptAddendum_whenTextChanges_persistsSetting() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-panel-agent-prompt-append");
        GeneralPanel subject = new GeneralPanel(settingsRepo);

        JTextArea promptArea = findComponentByName(subject, "agentSystemPromptAppendArea", JTextArea.class);
        SwingUtilities.invokeAndWait(() -> promptArea.setText("Always include key files in summaries."));

        assertThat(settingsRepo.get("chat4j.chat.agent.systemPromptAppend"))
                .contains("Always include key files in summaries.");
    }

    @Test
    @DisplayName("Prompt addendum save failures show an error without reporting saved")
    void updatePromptAddendum_whenSaveFails_showsErrorOnly() throws Exception {
        var settingsRepo = new ThrowingSettingsRepo(false);
        GeneralPanel subject = new GeneralPanel(settingsRepo);

        JTextArea promptArea = findComponentByName(subject, "agentSystemPromptAppendArea", JTextArea.class);
        SwingUtilities.invokeAndWait(() -> promptArea.setText("Always include key files in summaries."));

        assertThat(subject.statusLabel().getText()).contains("Failed to save prompt addendum setting");
    }

    @Test
    @DisplayName("Invalid initial send-key value is normalized and persisted to the default")
    void constructor_whenSendKeyInvalid_normalizesAndPersistsDefault() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-panel-invalid-send-key");
        settingsRepo.put("chat4j.chat.input.sendKey", "Space");

        new GeneralPanel(settingsRepo);

        assertThat(settingsRepo.get("chat4j.chat.input.sendKey")).contains("Enter");
    }

    @Test
    @DisplayName("Invalid initial render mode value is normalized and persisted to preview")
    void constructor_whenRenderModeInvalid_normalizesAndPersistsPreview() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("general-panel-invalid-render-mode");
        settingsRepo.put("chat4j.chat.render.mode", "side-by-side");

        new GeneralPanel(settingsRepo);

        assertThat(settingsRepo.get("chat4j.chat.render.mode")).contains(RenderMode.PREVIEW.settingValue());
    }

    @Test
    @DisplayName("Combo binding save failures show an error without reporting saved")
    void updateSendKey_whenSaveFails_showsErrorOnly() throws Exception {
        var settingsRepo = new ThrowingSettingsRepo(false);
        GeneralPanel subject = new GeneralPanel(settingsRepo);
        JComboBox<String> sendKey = findComponentByName(subject, "sendKeyComboBox", JComboBox.class);

        SwingUtilities.invokeAndWait(() -> sendKey.setSelectedItem(ChatBehaviorSettings.SEND_CTRL_ENTER));

        assertThat(subject.statusLabel().getText())
                .contains("Failed to save send key setting")
                .doesNotContain("Saved");
        assertThat(sendKey.getSelectedItem()).isEqualTo(ChatBehaviorSettings.SEND_ENTER);
    }

    @Test
    @DisplayName("Checkbox binding save failures show an error without reporting saved")
    void updateAutoScroll_whenSaveFails_showsErrorOnly() throws Exception {
        var settingsRepo = new ThrowingSettingsRepo(false);
        GeneralPanel subject = new GeneralPanel(settingsRepo);
        JCheckBox autoScroll = findComponentByName(subject, "autoScrollCheckBox", JCheckBox.class);

        SwingUtilities.invokeAndWait(autoScroll::doClick);

        assertThat(subject.statusLabel().getText())
                .contains("Failed to save auto-scroll setting")
                .doesNotContain("Saved");
    }

    @Test
    @DisplayName("Failed pending storage writes do not open restart prompt or report saved")
    void updateStorageBackend_whenPendingWriteFails_doesNotPromptOrShowSaved() throws Exception {
        var promptCalled = new AtomicBoolean(false);
        var settingsRepo = new ThrowingSettingsRepo(false);
        var subject = new GeneralPanel(
                settingsRepo,
                () -> {},
                new ChatBehaviorSettings(settingsRepo),
                new RenderModeSettings(settingsRepo),
                new AgentModeSettings(settingsRepo),
                new ChatStorageSettings(settingsRepo),
                (activeBackend, selectedBackend) -> {
                    promptCalled.set(true);
                    return RestartRequiredDialog.Choice.LATER;
                }
        );
        JComboBox<StorageBackend> storageBackend = findComponentByName(
                subject,
                "storageBackendComboBox",
                JComboBox.class
        );

        SwingUtilities.invokeAndWait(() -> storageBackend.setSelectedItem(StorageBackend.H2));

        assertThat(promptCalled).isFalse();
        assertThat(subject.statusLabel().getText()).contains("Failed to save chat storage setting");
    }

    private SettingsRepository settingsRepo(String testName) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(testName)));
    }

    private <T extends Component> T findComponentByName(Container root, String name, Class<T> type) {
        T found = findComponentByNameOrNull(root, name, type);
        assertThat(found).isNotNull();
        return found;
    }

    private <T extends Component> T findComponentByNameOrNull(Container root, String name, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName()) && type.isInstance(component)) {
                return type.cast(component);
            }

            if (component instanceof Container container) {
                T found = findComponentByNameOrNull(container, name, type);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private final boolean failReads;

        private ThrowingSettingsRepo(boolean failReads) {
            super(Path.of("unused-general-panel.properties"));
            this.failReads = failReads;
        }

        @Override
        public Optional<String> get(String key) {
            if (failReads) {
                throw new IllegalStateException("forced failure");
            }
            return Optional.empty();
        }

        @Override
        public void put(String key, String value) {
            throw new IllegalStateException("forced failure");
        }

        @Override
        public void remove(String key) {
            throw new IllegalStateException("forced failure");
        }
    }
}
