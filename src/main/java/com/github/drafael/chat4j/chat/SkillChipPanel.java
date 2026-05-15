package com.github.drafael.chat4j.chat;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Function;

final class SkillChipPanel extends JPanel {
    private static final int ARC = 14;

    private final Function<Boolean, Color> backgroundResolver;
    private final Function<Boolean, Color> borderResolver;
    private boolean hovered;

    SkillChipPanel(Function<Boolean, Color> backgroundResolver, Function<Boolean, Color> borderResolver) {
        this.backgroundResolver = backgroundResolver;
        this.borderResolver = borderResolver;
        setOpaque(false);
    }

    void setHovered(boolean hovered) {
        if (this.hovered == hovered) {
            return;
        }
        this.hovered = hovered;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color fill = backgroundResolver.apply(hovered);
        g2.setColor(fill);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();
        super.paintComponent(g);
    }

    @Override
    protected void paintBorder(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color border = borderResolver.apply(hovered);
        g2.setColor(border);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();
    }
}
