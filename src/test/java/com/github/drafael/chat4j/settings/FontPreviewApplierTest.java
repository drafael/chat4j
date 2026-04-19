package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class FontPreviewApplierTest {

    private final FontPreviewApplier subject = new FontPreviewApplier();

    @Test
    @DisplayName("Apply app font delegates to appearance font application without throwing")
    void applyAppFont_whenCalled_delegatesWithoutThrowing() {
        assertThatCode(() -> subject.applyAppFont(AppearancePanel.DEFAULT_APP_FONT, AppearancePanel.defaultAppFontSize()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Apply code font delegates to appearance code font application without throwing")
    void applyCodeFont_whenCalled_delegatesWithoutThrowing() {
        assertThatCode(() -> subject.applyCodeFont(AppearancePanel.DEFAULT_CODE_FONT))
                .doesNotThrowAnyException();
    }
}
