package com.github.drafael.chat4j.chat.ui;

import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import org.apache.commons.lang3.ObjectUtils;

public final class InputComposerShellPanel extends JPanel {
    private final int arc;

    public InputComposerShellPanel(int arc) {
        this.arc = arc;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color background = ObjectUtils.firstNonNull(
                UIManager.getColor("TextArea.background"),
                UIManager.getColor("Panel.background"),
                getBackground()
        );

        g2.setColor(background);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        g2.dispose();

        super.paintComponent(g);
    }

    @Override
    protected void paintBorder(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color border = ObjectUtils.firstNonNull(
                UIManager.getColor("Component.borderColor"),
                UIManager.getColor("Separator.foreground"),
                new Color(180, 180, 180)
        );

        g2.setColor(border);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        g2.dispose();
    }

    @Override
    public boolean isOpaque() {
        return false;
    }
}
