package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderMenuIconRendererTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("apple.laf.useScreenMenuBar");
        ProviderMenuIconRenderer.clearCache();
    }

    @Test
    @DisplayName("Resolve returns null for unknown provider")
    void resolve_whenProviderIsUnknown_returnsNull() {
        Icon icon = ProviderMenuIconRenderer.resolve("Unknown", 16, Color.GRAY, ProviderMenuIconRenderer.class);

        assertThat(icon).isNull();
    }

    @Test
    @DisplayName("Resolve returns cached icon instance for same parameters")
    void resolve_whenParametersMatch_returnsCachedInstance() {
        Icon first = ProviderMenuIconRenderer.resolve("OpenAI", 16, Color.GRAY, ProviderMenuIconRenderer.class);
        Icon second = ProviderMenuIconRenderer.resolve("OpenAI", 16, Color.GRAY, ProviderMenuIconRenderer.class);

        assertThat(first).isNotNull();
        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("Resolve rejects non-positive icon sizes")
    void resolve_whenSizeIsNotPositive_throwsException() {
        assertThatThrownBy(() -> ProviderMenuIconRenderer.resolve("OpenAI", 0, Color.GRAY, ProviderMenuIconRenderer.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size must be positive");
    }

    @Test
    @DisplayName("Resolve returns retina-friendly image icon when screen menu bar is enabled")
    void resolve_whenScreenMenuBarEnabled_returnsImageIcon() {
        System.setProperty("apple.laf.useScreenMenuBar", "true");

        Icon icon = ProviderMenuIconRenderer.resolve("OpenAI", 16, Color.GRAY, ProviderMenuIconRendererTest.class);

        assertThat(icon).isInstanceOf(ImageIcon.class);
    }

    @Test
    @DisplayName("Opaque color returns fallback for null and strips alpha for non-null")
    void opaqueColor_whenCalled_returnsOpaqueColorOrFallback() {
        Color fallback = new Color(11, 22, 33);

        Color fromNull = ProviderMenuIconRenderer.opaqueColor(null, fallback);
        Color fromAlphaColor = ProviderMenuIconRenderer.opaqueColor(new Color(10, 20, 30, 40), fallback);

        assertThat(fromNull).isEqualTo(fallback);
        assertThat(fromAlphaColor.getRed()).isEqualTo(10);
        assertThat(fromAlphaColor.getGreen()).isEqualTo(20);
        assertThat(fromAlphaColor.getBlue()).isEqualTo(30);
        assertThat(fromAlphaColor.getAlpha()).isEqualTo(255);
    }
}
