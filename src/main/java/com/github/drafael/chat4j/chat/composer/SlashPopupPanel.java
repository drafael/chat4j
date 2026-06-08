package com.github.drafael.chat4j.chat.composer;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.function.Supplier;

final class SlashPopupPanel extends JPanel {
    private static final int DECORATION_INSET = 1;

    private final Supplier<Color> fillSupplier;
    private final Supplier<Color> strokeSupplier;
    private Color fill;
    private Color stroke;

    SlashPopupPanel(Supplier<Color> fillSupplier, Supplier<Color> strokeSupplier) {
        super(new BorderLayout());
        this.fillSupplier = fillSupplier;
        this.strokeSupplier = strokeSupplier;
        setOpaque(false);
        applyThemeBorder();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (fillSupplier != null && strokeSupplier != null) {
            applyThemeBorder();
        }
    }

    int decorationInset() {
        return DECORATION_INSET;
    }

    void applyThemeBorder() {
        fill = fillSupplier.get();
        stroke = strokeSupplier.get();
        setBackground(fill);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(
                DECORATION_INSET,
                DECORATION_INSET,
                DECORATION_INSET,
                DECORATION_INSET
        ));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        Color background = fill == null ? fillSupplier.get() : fill;
        Color border = stroke == null ? strokeSupplier.get() : stroke;
        g2.setColor(background);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(border);
        g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        g2.dispose();
    }
}
