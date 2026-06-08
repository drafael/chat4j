package com.github.drafael.chat4j.chat.composer;

import com.github.drafael.chat4j.util.Fonts;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Supplier;

final class SkillBadgeLabel extends JLabel {
    private static final int ARC = 10;
    private static final int WIDTH = 48;
    private static final int HEIGHT = 28;

    private final Supplier<Color> defaultFillSupplier;
    private final Supplier<Color> defaultBorderSupplier;
    private Color fill;
    private Color border;

    SkillBadgeLabel(Supplier<Color> defaultFillSupplier, Supplier<Color> defaultBorderSupplier) {
        super("SKILL", SwingConstants.CENTER);
        this.defaultFillSupplier = defaultFillSupplier;
        this.defaultBorderSupplier = defaultBorderSupplier;
        Fonts.apply(this, Font.BOLD, Fonts.SIZE_MICRO);
        setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        Dimension size = new Dimension(WIDTH, HEIGHT);
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        setOpaque(false);
    }

    void setColors(Color fill, Color border, Color foreground) {
        this.fill = fill;
        this.border = border;
        setForeground(foreground);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(fill == null ? defaultFillSupplier.get() : fill);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();

        super.paintComponent(g);
    }

    @Override
    protected void paintBorder(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(border == null ? defaultBorderSupplier.get() : border);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();
    }
}
