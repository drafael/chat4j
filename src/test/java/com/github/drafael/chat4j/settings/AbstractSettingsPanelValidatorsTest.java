package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

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

    private static final class TestSettingsPanel extends AbstractSettingsPanel {
        private TestSettingsPanel(SettingsRepository settingsRepo) {
            super(settingsRepo);
        }

        private void showError(String message) {
            setStatusError(message);
        }
    }
}
