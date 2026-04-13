package com.github.drafael.chat4j.chat;

import javax.swing.*;
import java.awt.*;

record ChatTheme(
        String textColor,
        String mutedTextColor,
        String linkColor,
        String surfaceBg,
        String separatorColor,
        String codeBg,
        String codeBorder,
        String codeHeaderBg,
        String inlineCodeBg,
        String codeText,
        String langColor,
        String userBubbleBg,
        String baseFontFamily,
        String monoFontFamily,
        int baseFontSize,
        int monoFontSize
) {

    private static final String BASE_FONT_FALLBACK =
            "-apple-system, BlinkMacSystemFont, '.AppleSystemUIFont', 'Helvetica Neue', 'Apple Color Emoji', 'Segoe UI Emoji', 'Noto Color Emoji', sans-serif";
    private static final String MONO_FONT_FALLBACK =
            "Monaco, Menlo, Consolas, 'Courier New', monospace";

    static ChatTheme fromCurrentLookAndFeel() {
        boolean isDark = detectDarkMode();

        Color fallbackPanel = isDark ? new Color(36, 36, 38) : new Color(250, 250, 252);
        Color fallbackText = isDark ? new Color(212, 212, 212) : new Color(29, 29, 31);
        Color fallbackBorder = isDark ? new Color(72, 72, 74) : new Color(224, 224, 228);

        Color panelBg = uiColor("Panel.background", fallbackPanel);
        Color text = uiColor("Label.foreground", fallbackText);
        Color border = uiColor("Component.borderColor", fallbackBorder);
        Color separator = uiColor("Separator.foreground", border);
        Color link = uiColor("Component.linkColor",
                uiColor("Component.accentColor", blend(text, panelBg, 0.35f)));

        Color codeBgColor = uiColor("TextArea.background", blend(panelBg, text, isDark ? 0.08f : 0.02f));
        Color codeTextColor = uiColor("TextArea.foreground", text);
        Color codeHeaderBgColor = shiftBrightness(codeBgColor, isDark ? 0.06f : -0.05f);
        Color inlineCodeBgColor = shiftBrightness(codeBgColor, isDark ? 0.08f : -0.03f);
        Color langColorValue = blend(codeTextColor, panelBg, 0.45f);
        Color mutedText = uiColor("Label.disabledForeground", blend(text, panelBg, 0.40f));
        Color surface = uiColor("TextField.background", blend(panelBg, text, isDark ? 0.04f : 0.015f));
        Color userBubble = resolveUserBubbleColor(panelBg, isDark);

        Font defaultFont = UIManager.getFont("defaultFont");
        Font monoFont = UIManager.getFont("monospaced.font");
        if (monoFont == null) {
            monoFont = UIManager.getFont("TextArea.font");
        }

        String baseFontFamily = cssFontStack(defaultFont, BASE_FONT_FALLBACK);
        String monoFontFamily = cssFontStack(monoFont, MONO_FONT_FALLBACK);
        int baseFontSize = defaultFont != null ? defaultFont.getSize() : 13;
        int monoFontSize = monoFont != null ? monoFont.getSize() : 12;

        return new ChatTheme(
                toHex(text),
                toHex(mutedText),
                toHex(link),
                toHex(surface),
                toHex(separator),
                toHex(codeBgColor),
                toHex(border),
                toHex(codeHeaderBgColor),
                toHex(inlineCodeBgColor),
                toHex(codeTextColor),
                toHex(langColorValue),
                toHex(userBubble),
                baseFontFamily,
                monoFontFamily,
                baseFontSize,
                monoFontSize
        );
    }

    private static Color resolveUserBubbleColor(Color panelBg, boolean isDark) {
        if (panelBg == null) {
            return isDark ? new Color(58, 58, 60) : new Color(239, 239, 241);
        }

        float[] hsb = Color.RGBtoHSB(panelBg.getRed(), panelBg.getGreen(), panelBg.getBlue(), null);
        float brightness = clamp(hsb[2] + (isDark ? 0.10f : -0.04f));
        float saturation = clamp(hsb[1] + (isDark ? -0.02f : 0.02f));
        return Color.getHSBColor(hsb[0], saturation, brightness);
    }

    private static boolean detectDarkMode() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg != null) {
            float[] hsb = Color.RGBtoHSB(bg.getRed(), bg.getGreen(), bg.getBlue(), null);
            return hsb[2] <= 0.5f;
        }
        return false;
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private static Color blend(Color primary, Color secondary, float secondaryWeight) {
        float weight = Math.max(0f, Math.min(1f, secondaryWeight));
        float primaryWeight = 1f - weight;
        int red = Math.round(primary.getRed() * primaryWeight + secondary.getRed() * weight);
        int green = Math.round(primary.getGreen() * primaryWeight + secondary.getGreen() * weight);
        int blue = Math.round(primary.getBlue() * primaryWeight + secondary.getBlue() * weight);
        return new Color(red, green, blue);
    }

    private static Color shiftBrightness(Color color, float delta) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        float brightness = Math.max(0f, Math.min(1f, hsb[2] + delta));
        return Color.getHSBColor(hsb[0], hsb[1], brightness);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String cssFontStack(Font font, String fallbackStack) {
        if (font == null || font.getFamily() == null || font.getFamily().isBlank()) {
            return fallbackStack;
        }

        String family = font.getFamily().replace("'", "");
        return "'" + family + "', " + fallbackStack;
    }

    private static String toHex(Color color) {
        return "#%02x%02x%02x".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }
}
