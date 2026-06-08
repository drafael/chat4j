package com.github.drafael.chat4j.chat.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.function.Supplier;

public final class HoverRoundButton extends JButton {
    private final int arc;
    private final Supplier<Color> hoverBackgroundSupplier;

    public HoverRoundButton(int size, int arc, Supplier<Color> hoverBackgroundSupplier) {
        this.arc = arc;
        this.hoverBackgroundSupplier = hoverBackgroundSupplier;
        putClientProperty("JButton.buttonType", "toolBarButton");
        setMargin(new Insets(0, 0, 0, 0));
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        setContentAreaFilled(false);
        setFocusPainted(false);
        setFocusable(false);
        setRolloverEnabled(true);
        Dimension buttonSize = new Dimension(size, size);
        setPreferredSize(buttonSize);
        setMinimumSize(buttonSize);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (getModel().isRollover()) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hoverBackgroundSupplier.get());
            g2.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, arc, arc);
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
