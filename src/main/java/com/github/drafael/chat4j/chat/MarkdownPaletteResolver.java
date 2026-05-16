package com.github.drafael.chat4j.chat;

import javax.swing.*;
import java.awt.*;
import org.apache.commons.lang3.StringUtils;

public final class MarkdownPaletteResolver {

    private static final Color DARK_PANEL_FALLBACK = new Color(36, 36, 38);
    private static final Color LIGHT_PANEL_FALLBACK = new Color(250, 250, 252);
    private static final Color DARK_TEXT_FALLBACK = new Color(212, 212, 212);
    private static final Color LIGHT_TEXT_FALLBACK = new Color(29, 29, 31);
    private static final Color DARK_BORDER_FALLBACK = new Color(72, 72, 74);
    private static final Color LIGHT_BORDER_FALLBACK = new Color(224, 224, 228);

    private static final String BASE_FONT_FALLBACK_STACK =
            "-apple-system, '.AppleSystemUIFont', 'Helvetica Neue', sans-serif";
    private static final String BASE_FONT_FALLBACK_FACE = "-apple-system, sans-serif";
    private static final String MONO_FONT_FALLBACK_STACK =
            "Monaco, Menlo, Consolas, 'Courier New', monospace";

    private MarkdownPaletteResolver() {
    }

    public static Palette resolve(boolean isDark) {
        Fonts fonts = resolveFonts();
        Colors colors = resolveColors(isDark);

        return new Palette(
                fonts.baseFamily(),
                fonts.baseFamilyAttr(),
                fonts.monoFamily(),
                fonts.monoFamilyAttr(),
                toHex(colors.text()),
                toHex(colors.mutedText()),
                toHex(colors.link()),
                toHex(colors.surface()),
                toHex(colors.separator()),
                toHex(colors.codeBg()),
                toHex(colors.border()),
                toHex(colors.codeHeaderBg()),
                toHex(colors.inlineCodeBg()),
                toHex(colors.codeText()),
                toHex(colors.langColor()));
    }

    private static Fonts resolveFonts() {
        Font defaultFont = UIManager.getFont("defaultFont");
        Font monospaceFont = UIManager.getFont("monospaced.font");
        if (monospaceFont == null) {
            monospaceFont = UIManager.getFont("TextArea.font");
        }
        return new Fonts(
                cssFontStack(defaultFont, BASE_FONT_FALLBACK_STACK),
                htmlFontFace(defaultFont, BASE_FONT_FALLBACK_FACE),
                cssFontStack(monospaceFont, MONO_FONT_FALLBACK_STACK),
                htmlFontFace(monospaceFont, MONO_FONT_FALLBACK_STACK));
    }

    private static Colors resolveColors(boolean isDark) {
        Color fallbackPanel = isDark ? DARK_PANEL_FALLBACK : LIGHT_PANEL_FALLBACK;
        Color fallbackText = isDark ? DARK_TEXT_FALLBACK : LIGHT_TEXT_FALLBACK;
        Color fallbackBorder = isDark ? DARK_BORDER_FALLBACK : LIGHT_BORDER_FALLBACK;

        Color panelBg = uiColor("Panel.background", fallbackPanel);
        Color text = uiColor("Label.foreground", fallbackText);
        Color border = uiColor("Component.borderColor", fallbackBorder);
        Color separator = uiColor("Separator.foreground", border);
        Color link = uiColor("Component.linkColor", uiColor("Component.accentColor", blend(text, panelBg, 0.35f)));

        Color codeBg = uiColor("TextArea.background", blend(panelBg, text, isDark ? 0.08f : 0.02f));
        Color codeText = uiColor("TextArea.foreground", text);
        Color codeHeaderBg = shiftBrightness(codeBg, isDark ? 0.06f : -0.05f);
        Color inlineCodeBg = shiftBrightness(codeBg, isDark ? 0.08f : -0.03f);
        Color langColor = blend(codeText, panelBg, 0.45f);
        Color mutedText = uiColor("Label.disabledForeground", blend(text, panelBg, 0.40f));
        Color surface = uiColor("TextField.background", blend(panelBg, text, isDark ? 0.04f : 0.015f));

        return new Colors(
                text,
                mutedText,
                link,
                surface,
                separator,
                codeBg,
                border,
                codeHeaderBg,
                inlineCodeBg,
                codeText,
                langColor);
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

    private static String cssFontStack(Font font, String fallbackStack) {
        if (font == null || StringUtils.isBlank(font.getFamily())) {
            return fallbackStack;
        }
        return "'%s', %s".formatted(font.getFamily().replace("'", ""), fallbackStack);
    }

    private static String htmlFontFace(Font font, String fallbackFace) {
        if (font == null || StringUtils.isBlank(font.getFamily())) {
            return fallbackFace;
        }
        return font.getFamily().replace("\"", "");
    }

    private static String toHex(Color color) {
        return "#%02x%02x%02x".formatted(color.getRed(), color.getGreen(), color.getBlue());
    }

    private record Fonts(
            String baseFamily,
            String baseFamilyAttr,
            String monoFamily,
            String monoFamilyAttr) {
    }

    private record Colors(
            Color text,
            Color mutedText,
            Color link,
            Color surface,
            Color separator,
            Color codeBg,
            Color border,
            Color codeHeaderBg,
            Color inlineCodeBg,
            Color codeText,
            Color langColor) {
    }
}
