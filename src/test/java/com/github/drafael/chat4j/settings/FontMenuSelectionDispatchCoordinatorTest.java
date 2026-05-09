package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JRadioButtonMenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FontMenuSelectionDispatchCoordinatorTest {

    @Test
    @DisplayName("Refresh composes previous selection state and delegates to refresh action")
    void refresh_whenCalled_composesPreviousStateAndDelegates() {
        var capturedPrevious = new AtomicReference<FontMenuSelectionSynchronizer.FontMenuSelectionState>();
        var subject = new FontMenuSelectionDispatchCoordinator((
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                previousSelection,
                fontMenuBuilt
        ) -> {
            capturedPrevious.set(previousSelection);
            return new FontMenuSelectionSynchronizer.FontMenuSelectionState("JetBrains Mono", 16, "Menlo");
        });

        Map<String, JRadioButtonMenuItem> appFonts = new LinkedHashMap<>();
        Map<Integer, JRadioButtonMenuItem> appSizes = new LinkedHashMap<>();
        Map<String, JRadioButtonMenuItem> codeFonts = new LinkedHashMap<>();

        FontMenuSelectionSynchronizer.FontMenuSelectionState result = subject.refresh(
                appFonts,
                appSizes,
                codeFonts,
                "Inter",
                14,
                "Monaco",
                true
        );

        assertThat(capturedPrevious.get()).isEqualTo(
                new FontMenuSelectionSynchronizer.FontMenuSelectionState("Inter", 14, "Monaco")
        );
        assertThat(result).isEqualTo(
                new FontMenuSelectionSynchronizer.FontMenuSelectionState("JetBrains Mono", 16, "Menlo")
        );
    }

    @Test
    @DisplayName("Refresh validates required arguments")
    void refresh_whenArgumentMissing_throwsException() {
        var subject = new FontMenuSelectionDispatchCoordinator((
                appFontMenuItemsByFamily,
                appFontSizeMenuItemsBySize,
                codeFontMenuItemsByFamily,
                previousSelection,
                fontMenuBuilt
        ) -> previousSelection);

        assertThatThrownBy(() -> subject.refresh(
                null,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                null,
                null,
                null,
                false
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("appFontMenuItemsByFamily");

        assertThatThrownBy(() -> new FontMenuSelectionDispatchCoordinator(
                (FontMenuSelectionDispatchCoordinator.RefreshAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refreshAction");
    }
}
