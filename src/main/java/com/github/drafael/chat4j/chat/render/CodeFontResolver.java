package com.github.drafael.chat4j.chat.render;

import com.github.drafael.chat4j.util.Fonts;

import javax.swing.*;
import java.awt.*;
import java.util.function.Supplier;

public final class CodeFontResolver {

    private static final ThreadLocal<Integer> RESOLVED_CODE_FONT_SIZE = new ThreadLocal<>();

    private CodeFontResolver() {
    }

    public static int resolveCodeFontSize() {
        Integer resolvedCodeFontSize = RESOLVED_CODE_FONT_SIZE.get();
        if (resolvedCodeFontSize != null) {
            return resolvedCodeFontSize;
        }

        Font monoFont = UIManager.getFont("monospaced.font");
        if (monoFont == null) {
            monoFont = UIManager.getFont("TextArea.font");
        }

        int bodyFontSize = Fonts.scale(Fonts.SIZE_SMALL);
        if (monoFont == null) {
            return bodyFontSize;
        }

        JLabel probe = new JLabel();
        int targetBodyHeight = probe.getFontMetrics(Fonts.of(Font.PLAIN, Fonts.SIZE_SMALL)).getHeight();

        for (int candidateSize = bodyFontSize; candidateSize >= 1; candidateSize--) {
            Font candidateFont = monoFont.deriveFont((float) candidateSize);
            int candidateHeight = probe.getFontMetrics(candidateFont).getHeight();
            if (candidateHeight <= targetBodyHeight) {
                return candidateSize;
            }
        }

        return 1;
    }

    public static <T> T withResolvedCodeFontSize(int codeFontSize, Supplier<T> action) {
        Integer previous = RESOLVED_CODE_FONT_SIZE.get();
        RESOLVED_CODE_FONT_SIZE.set(codeFontSize);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                RESOLVED_CODE_FONT_SIZE.remove();
            } else {
                RESOLVED_CODE_FONT_SIZE.set(previous);
            }
        }
    }
}
