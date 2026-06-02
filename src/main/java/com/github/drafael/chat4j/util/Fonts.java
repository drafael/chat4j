package com.github.drafael.chat4j.util;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.function.Supplier;

/**
 * Font helpers that honor the currently selected UI font family and size.
 */
public final class Fonts {

    public static final int SIZE_BADGE = 9;
    public static final int SIZE_MICRO = 10;
    public static final int SIZE_SMALL = 11;
    public static final int SIZE_COMPACT = 12;
    public static final int SIZE_BODY = 13;
    public static final int SIZE_BODY_LARGE = 14;
    public static final int SIZE_SUBTITLE = 16;
    public static final int SIZE_TITLE = 17;
    public static final int SIZE_PANEL_TITLE = 18;
    public static final int SIZE_DISPLAY = 20;

    private static final int DESIGN_BASE_SIZE = SIZE_BODY;
    private static final String FONT_STYLE_PROPERTY = "chat4j.font.style";
    private static final String FONT_SIZE_PROPERTY = "chat4j.font.designSize";
    private static final ThreadLocal<Float> SCALE_FACTOR_OVERRIDE = new ThreadLocal<>();

    private Fonts() {
    }

    public static Font of(int style, int designSize) {
        Font base = resolveBaseUiFont();
        int scaledSize = scale(designSize);
        String family = base != null ? base.getFamily() : Font.SANS_SERIF;
        return new Font(family, style, scaledSize);
    }

    public static void apply(JComponent component, int style, int designSize) {
        if (component == null) {
            return;
        }

        component.putClientProperty(FONT_STYLE_PROPERTY, style);
        component.putClientProperty(FONT_SIZE_PROPERTY, designSize);
        component.setFont(resolveDisplaySafeFont(component, style, designSize));
    }

    public static void refreshComponentTreeFonts(Component root) {
        if (root == null) {
            return;
        }

        if (root instanceof JComponent component) {
            Object style = component.getClientProperty(FONT_STYLE_PROPERTY);
            Object size = component.getClientProperty(FONT_SIZE_PROPERTY);
            if (style instanceof Integer fontStyle && size instanceof Integer designSize) {
                component.setFont(resolveDisplaySafeFont(component, fontStyle, designSize));
            }
        }

        if (root instanceof JMenu menu) {
            refreshComponentTreeFonts(menu.getPopupMenu());
        }

        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                refreshComponentTreeFonts(child);
            }
        }
    }

    public static int scale(int designSize) {
        int safeDesignSize = Math.max(1, designSize);
        float factor = scaleFactor();
        return Math.max(1, Math.round(safeDesignSize * factor));
    }

    private static Font resolveDisplaySafeFont(JComponent component, int style, int designSize) {
        Font preferredFont = of(style, designSize);
        String text = extractText(component);
        if (StringUtils.isBlank(text) || preferredFont.canDisplayUpTo(text) < 0) {
            return preferredFont;
        }

        int scaledSize = scale(designSize);

        Font logicalFallback = new Font(Font.SANS_SERIF, style, scaledSize);
        if (logicalFallback.canDisplayUpTo(text) < 0) {
            return logicalFallback;
        }

        Font uiFallbackBase = resolveBaseUiFont();
        if (uiFallbackBase != null) {
            Font uiFallback = new Font(uiFallbackBase.getName(), style, scaledSize);
            if (uiFallback.canDisplayUpTo(text) < 0) {
                return uiFallback;
            }
        }

        return preferredFont;
    }

    private static String extractText(JComponent component) {
        return switch (component) {
            case null -> null;
            case JLabel label -> label.getText();
            case AbstractButton button -> button.getText();
            case JTextComponent textComponent -> textComponent.getText();
            default -> null;
        };
    }

    public static <T> T withScaleFactor(float scaleFactor, Supplier<T> action) {
        Float previous = SCALE_FACTOR_OVERRIDE.get();
        SCALE_FACTOR_OVERRIDE.set(scaleFactor);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                SCALE_FACTOR_OVERRIDE.remove();
            } else {
                SCALE_FACTOR_OVERRIDE.set(previous);
            }
        }
    }

    private static float scaleFactor() {
        Float override = SCALE_FACTOR_OVERRIDE.get();
        if (override != null) {
            return override;
        }

        Font base = resolveBaseUiFont();
        int selectedSize = base != null ? base.getSize() : DESIGN_BASE_SIZE;
        return selectedSize / (float) DESIGN_BASE_SIZE;
    }

    private static Font resolveBaseUiFont() {
        return ObjectUtils.firstNonNull(
                UIManager.getFont("defaultFont"),
                UIManager.getFont("Label.font"),
                new Font(Font.SANS_SERIF, Font.PLAIN, DESIGN_BASE_SIZE)
        );
    }
}
