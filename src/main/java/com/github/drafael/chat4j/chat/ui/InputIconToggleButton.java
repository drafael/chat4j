package com.github.drafael.chat4j.chat.ui;

import javax.swing.AbstractButton;
import javax.swing.JToggleButton;
import java.awt.Graphics;
import java.util.function.BiConsumer;

public final class InputIconToggleButton extends JToggleButton {
    private final BiConsumer<Graphics, AbstractButton> backgroundPainter;

    public InputIconToggleButton(BiConsumer<Graphics, AbstractButton> backgroundPainter) {
        this.backgroundPainter = backgroundPainter;
    }

    @Override
    protected void paintComponent(Graphics g) {
        backgroundPainter.accept(g, this);
        super.paintComponent(g);
    }
}
