package com.github.drafael.chat4j.chat.composer;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Dimension;

public final class ComposerPanel extends JPanel {
    private static final int HORIZONTAL_MARGIN = 0;
    private static final int BOTTOM_MARGIN = 0;

    private JComponent composer;

    public ComposerPanel(JComponent composer) {
        setOpaque(false);
        setLayout(null);
        setComposer(composer);
    }

    public void setComposer(JComponent composer) {
        removeAll();
        this.composer = composer;
        add(composer);
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = composer.getPreferredSize();
        return new Dimension(
                preferred.width + HORIZONTAL_MARGIN * 2,
                preferred.height + BOTTOM_MARGIN
        );
    }

    @Override
    public void doLayout() {
        Dimension preferred = composer.getPreferredSize();
        int width = Math.max(0, getWidth() - HORIZONTAL_MARGIN * 2);
        composer.setBounds(HORIZONTAL_MARGIN, 0, width, preferred.height);
    }
}
