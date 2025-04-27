package com.github.drafael.chat4j.util;

import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import java.awt.*;

/**
 * Cross-platform font factory.
 * <p>
 * On macOS HiDPI, deriveFont() breaks composite fonts causing missing characters.
 * We use .AppleSystemUIFont directly on macOS, and FlatLaf's default font on other platforms.
 * See docs/macos-hidpi-font-rendering.md for details.
 */
public final class Fonts {

    private Fonts() {}

    public static Font of(int style, int size) {
        if (SystemInfo.isMacOS) {
            return new Font(".AppleSystemUIFont", style, size);
        }
        Font base = UIManager.getFont("defaultFont");
        if (base != null) {
            return base.deriveFont(style, (float) size);
        }
        return new Font(Font.SANS_SERIF, style, size);
    }
}
