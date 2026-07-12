package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.persistence.settings.SettingsStorageException;
import java.awt.Color;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.UIManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractSettingsPanelValidatorsTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("HTTP URL validator accepts valid https URL and trims whitespace")
    void httpUrl_whenValidHttpsUrl_returnsNormalizedValue() {
        var result = AbstractSettingsPanel.Validators.httpUrl("invalid")
                .validate("  https://api.example.com/v1  ");

        assertThat(result.valid()).isTrue();
        assertThat(result.normalizedValue()).isEqualTo("https://api.example.com/v1");
    }

    @Test
    @DisplayName("HTTP URL validator rejects unsupported scheme")
    void httpUrl_whenSchemeIsUnsupported_returnsInvalidResult() {
        var result = AbstractSettingsPanel.Validators.httpUrl("invalid")
                .validate("ftp://example.com");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("invalid");
    }

    @Test
    @DisplayName("One-of validator rejects value outside allowed set")
    void oneOf_whenValueIsNotAllowed_returnsInvalidResult() {
        var result = AbstractSettingsPanel.Validators.oneOf(Set.of("Enter", "Ctrl+Enter"), "invalid option")
                .validate("Shift+Enter");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("invalid option");
    }

    @Test
    @DisplayName("Trim-non-blank validator rejects blank values")
    void trimNonBlank_whenValueIsBlank_returnsInvalidResult() {
        var result = AbstractSettingsPanel.Validators.trimNonBlank("required")
                .validate("   ");

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).isEqualTo("required");
    }

    @Test
    @DisplayName("Message box colors use FlatLaf tooltip foreground and background")
    void messageBoxColors_whenTooltipColorsConfigured_useTooltipColorPair() {
        Object previousBackground = UIManager.get("ToolTip.background");
        Object previousForeground = UIManager.get("ToolTip.foreground");
        Color expectedBackground = new Color(30, 32, 33);
        Color expectedForeground = new Color(187, 187, 187);
        try {
            UIManager.put("ToolTip.background", expectedBackground);
            UIManager.put("ToolTip.foreground", expectedForeground);
            var subject = new TestSettingsPanel(new SettingsRepository(tempDir.resolve("settings.properties")));

            assertThat(subject.infoBackground()).isEqualTo(expectedBackground);
            assertThat(subject.warningBackground()).isEqualTo(expectedBackground);
            assertThat(subject.infoTitleForeground()).isEqualTo(expectedForeground);
            assertThat(subject.warningTitleForeground()).isEqualTo(expectedForeground);
            assertThat(subject.messageForeground()).isEqualTo(expectedForeground);
        } finally {
            UIManager.put("ToolTip.background", previousBackground);
            UIManager.put("ToolTip.foreground", previousForeground);
        }
    }

    @Test
    @DisplayName("Status errors render as escaped wrapping HTML")
    void setStatusError_whenMessageIsLong_rendersEscapedWrappingHtml() {
        var subject = new TestSettingsPanel(new SettingsRepository(tempDir.resolve("settings.properties")));
        subject.createFormPanel("Test");
        subject.statusLabel().getParent().setSize(1400, 40);

        subject.showError("Problem <details> & this message should wrap instead of being clipped while preserving every word in the status message");

        assertThat(subject.statusLabel().getText())
                .startsWith("<html><body style='width:900px'>")
                .contains("Problem &lt;details&gt; &amp; this message should wrap instead of being clipped while preserving every")
                .contains("<br>")
                .contains("word in the status message");
    }

    @Test
    @DisplayName("Checkbox binding does not apply changes when persistence fails")
    void bindCheckBox_whenWriteFails_revertsSelectionAndSkipsCallback() {
        var subject = new TestSettingsPanel(new FailingSettingsRepository(tempDir.resolve("settings.properties")));
        var checkBox = new JCheckBox();
        var applied = new AtomicBoolean(false);
        subject.bindCheckBox(checkBox, "chat4j.test.enabled", false, value -> applied.set(true));

        checkBox.doClick();

        assertThat(checkBox.isSelected()).isFalse();
        assertThat(applied.get()).isFalse();
        assertThat(subject.statusLabel().getText()).contains("Failed to save setting: chat4j.test.enabled");
    }

    @Test
    @DisplayName("Combo box binding does not apply changes when persistence fails")
    void bindComboBox_whenWriteFails_revertsSelectionAndSkipsCallback() {
        var subject = new TestSettingsPanel(new FailingSettingsRepository(tempDir.resolve("settings.properties")));
        var comboBox = new JComboBox<>(new String[] {"old", "new"});
        var applied = new AtomicReference<String>();
        subject.bindComboBox(comboBox, "chat4j.test.choice", "old", AbstractSettingsPanel.Validators.oneOf(Set.of("old", "new"), "invalid"), applied::set);

        comboBox.setSelectedItem("new");

        assertThat(comboBox.getSelectedItem()).isEqualTo("old");
        assertThat(applied.get()).isNull();
        assertThat(subject.statusLabel().getText()).contains("Failed to save setting: chat4j.test.choice");
    }

    @Test
    @DisplayName("Text field binding does not apply changes when persistence fails")
    void bindTextField_whenWriteFails_revertsTextAndSkipsCallback() {
        var subject = new TestSettingsPanel(new FailingSettingsRepository(tempDir.resolve("settings.properties")));
        var textField = new JTextField();
        var applied = new AtomicReference<String>();
        subject.bindTextField(textField, "chat4j.test.text", "old", AbstractSettingsPanel.Validators.trimNonBlank("required"), applied::set);

        textField.setText("new");
        textField.postActionEvent();

        assertThat(textField.getText()).isEqualTo("old");
        assertThat(applied.get()).isNull();
        assertThat(subject.statusLabel().getText()).contains("Failed to save setting: chat4j.test.text");
    }

    @Test
    @DisplayName("Remove helper reports failure to callers")
    void removeSetting_whenRemoveFails_returnsFalseAndKeepsErrorStatus() {
        var subject = new TestSettingsPanel(new FailingSettingsRepository(tempDir.resolve("settings.properties")));

        boolean removed = subject.removeTestSetting("chat4j.test.text");

        assertThat(removed).isFalse();
        assertThat(subject.statusLabel().getText()).contains("Failed to remove setting: chat4j.test.text");
    }

    private static final class TestSettingsPanel extends AbstractSettingsPanel {
        private TestSettingsPanel(SettingsRepository settingsRepo) {
            super(settingsRepo);
        }

        private void showError(String message) {
            setStatusError(message);
        }

        private boolean removeTestSetting(String key) {
            return removeSetting(key);
        }

        private Color infoBackground() {
            return infoBoxBackground();
        }

        private Color warningBackground() {
            return warningBoxBackground();
        }

        private Color infoTitleForeground() {
            return infoBoxTitleForeground();
        }

        private Color warningTitleForeground() {
            return warningBoxTitleForeground();
        }

        private Color messageForeground() {
            return messageBoxForeground();
        }
    }

    private static final class FailingSettingsRepository extends SettingsRepository {
        private FailingSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            throw new SettingsStorageException("failed", new IllegalStateException("test failure"));
        }

        @Override
        public void remove(String key) {
            throw new SettingsStorageException("failed", new IllegalStateException("test failure"));
        }
    }
}
