package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FontSelectionNormalizerTest {

    private final FontSelectionNormalizer subject = new FontSelectionNormalizer();

    @Test
    @DisplayName("Normalize app selection keeps available family and normalizes size")
    void normalizeAppSelection_whenFamilyAvailable_keepsFamilyAndNormalizesSize() {
        FontSelectionNormalizer.AppFontSelection selection = subject.normalizeAppSelection(
                "Inter",
                17,
                Set.of("System Default", "Inter")
        );

        assertThat(selection.family()).isEqualTo("Inter");
        assertThat(selection.size()).isEqualTo(AppearancePanel.normalizeAppFontSize(17));
    }

    @Test
    @DisplayName("Normalize app selection falls back to default family when unavailable")
    void normalizeAppSelection_whenFamilyUnavailable_usesDefaultFamily() {
        FontSelectionNormalizer.AppFontSelection selection = subject.normalizeAppSelection(
                "Unavailable",
                16,
                Set.of("System Default", "Inter")
        );

        assertThat(selection.family()).isEqualTo(FontSettings.DEFAULT_APP_FONT);
        assertThat(selection.size()).isEqualTo(AppearancePanel.normalizeAppFontSize(16));
    }

    @Test
    @DisplayName("Normalize code family falls back to default when unavailable")
    void normalizeCodeFamily_whenFamilyUnavailable_usesDefaultFamily() {
        String family = subject.normalizeCodeFamily("Missing", Set.of("Monospaced", "JetBrains Mono"));

        assertThat(family).isEqualTo(FontSettings.DEFAULT_CODE_FONT);
    }
}
