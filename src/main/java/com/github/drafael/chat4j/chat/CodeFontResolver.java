package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.util.Fonts;

import javax.swing.*;
import java.awt.*;

public final class CodeFontResolver {

    private CodeFontResolver() {
    }

    public static int resolveCodeFontSize() {
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
}
