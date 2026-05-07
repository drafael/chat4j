package com.github.drafael.chat4j.util;

import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;

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

    private Fonts() {
    }

    public static Font of(int style, int designSize) {
        Font base = resolveBaseUiFont();
        int scaledSize = scale(designSize);
        if (base != null) {
            return new Font(base.getFamily(), style, scaledSize);
        }
        return new Font(Font.SANS_SERIF, style, scaledSize);
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
        if (component instanceof JLabel label) {
            return label.getText();
        }

        if (component instanceof AbstractButton button) {
            return button.getText();
        }

        if (component instanceof JTextComponent textComponent) {
            return textComponent.getText();
        }

        return null;
    }

    private static float scaleFactor() {
        Font base = resolveBaseUiFont();
        int selectedSize = base != null ? base.getSize() : DESIGN_BASE_SIZE;
        return selectedSize / (float) DESIGN_BASE_SIZE;
    }

    private static Font resolveBaseUiFont() {
        Font font = UIManager.getFont("defaultFont");
        if (font != null) {
            return font;
        }

        font = UIManager.getFont("Label.font");
        if (font != null) {
            return font;
        }

        return new Font(Font.SANS_SERIF, Font.PLAIN, DESIGN_BASE_SIZE);
    }
}
