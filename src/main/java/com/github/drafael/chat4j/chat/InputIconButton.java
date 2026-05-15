package com.github.drafael.chat4j.chat;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import java.awt.Graphics;
import java.util.function.BiConsumer;

final class InputIconButton extends JButton {
    private final BiConsumer<Graphics, AbstractButton> backgroundPainter;

    InputIconButton(BiConsumer<Graphics, AbstractButton> backgroundPainter) {
        this.backgroundPainter = backgroundPainter;
    }

    @Override
    protected void paintComponent(Graphics g) {
        backgroundPainter.accept(g, this);
        super.paintComponent(g);
    }
}
