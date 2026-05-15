package com.github.drafael.chat4j.chat;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

final class InputChipPanel extends JPanel {
    private static final int ARC = 10;

    private final Supplier<Color> backgroundSupplier;
    private final Function<Color, Color> borderResolver;
    private final BiFunction<Color, Float, Color> colorAdjuster;
    private final float hoverBackgroundDelta;
    private final float hoverBorderDelta;
    private boolean hovered;

    InputChipPanel(
            Supplier<Color> backgroundSupplier,
            Function<Color, Color> borderResolver,
            BiFunction<Color, Float, Color> colorAdjuster,
            float hoverBackgroundDelta,
            float hoverBorderDelta
    ) {
        this.backgroundSupplier = backgroundSupplier;
        this.borderResolver = borderResolver;
        this.colorAdjuster = colorAdjuster;
        this.hoverBackgroundDelta = hoverBackgroundDelta;
        this.hoverBorderDelta = hoverBorderDelta;
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

        Color background = chipBackground();
        g2.setColor(background);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();
        super.paintComponent(g);
    }

    @Override
    protected void paintBorder(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color background = chipBackground();
        Color border = borderResolver.apply(background);
        if (hovered) {
            border = colorAdjuster.apply(border, hoverBorderDelta);
        }

        g2.setColor(border);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
        g2.dispose();
    }

    private Color chipBackground() {
        Color background = backgroundSupplier.get();
        return hovered ? colorAdjuster.apply(background, hoverBackgroundDelta) : background;
    }
}
