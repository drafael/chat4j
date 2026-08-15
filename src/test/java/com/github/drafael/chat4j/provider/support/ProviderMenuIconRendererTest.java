package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

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
    @DisplayName("Together mark uses optically balanced bounds")
    void resolve_whenProviderIsTogether_returnsOpticallyBalancedIcon() throws Exception {
        var resolved = new AtomicReference<Icon>();
        var paintedBounds = new AtomicReference<Rectangle>();
        SwingUtilities.invokeAndWait(() -> {
            Icon icon = ProviderMenuIconRenderer.resolve("Together", 16, Color.GRAY, ProviderMenuIconRenderer.class);
            resolved.set(icon);
            var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                icon.paintIcon(null, graphics, 0, 0);
            } finally {
                graphics.dispose();
            }
            int minX = image.getWidth();
            int minY = image.getHeight();
            int maxX = -1;
            int maxY = -1;
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) {
                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            paintedBounds.set(new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1));
        });

        assertThat(resolved.get()).isNotNull();
        assertThat(paintedBounds.get().width).isBetween(10, 12);
        assertThat(paintedBounds.get().height).isBetween(10, 12);
        assertThat(paintedBounds.get().x).isBetween(2, 4);
        assertThat(paintedBounds.get().y).isBetween(2, 4);
    }

    @Test
    @DisplayName("Together mark follows both light and dark theme foreground colors")
    void resolve_whenTogetherTintChanges_rendersRequestedThemeColor() throws Exception {
        Color lightThemeTint = new Color(45, 45, 45);
        Color darkThemeTint = new Color(235, 235, 235);
        var lightThemePixel = new AtomicReference<Color>();
        var darkThemePixel = new AtomicReference<Color>();

        SwingUtilities.invokeAndWait(() -> {
            Icon lightIcon = ProviderMenuIconRenderer.resolve(
                    "Together",
                    16,
                    lightThemeTint,
                    ProviderMenuIconRenderer.class
            );
            Icon darkIcon = ProviderMenuIconRenderer.resolve(
                    "Together",
                    16,
                    darkThemeTint,
                    ProviderMenuIconRenderer.class
            );
            lightThemePixel.set(firstOpaquePixelColor(lightIcon));
            darkThemePixel.set(firstOpaquePixelColor(darkIcon));
        });

        assertThat(lightThemePixel.get()).isEqualTo(lightThemeTint);
        assertThat(darkThemePixel.get()).isEqualTo(darkThemeTint);
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

    private Color firstOpaquePixelColor(Icon icon) {
        var image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }

        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                Color color = new Color(image.getRGB(x, y), true);
                if (color.getAlpha() == 255) {
                    return color;
                }
            }
        }
        throw new AssertionError("Expected the icon to contain an opaque pixel");
    }
}
