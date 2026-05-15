package com.github.drafael.chat4j.chat;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;

final class ChatFadeOverlay extends JComponent {
    enum Direction {
        TOP,
        BOTTOM
    }

    private final Direction direction;
    private final float opacity;

    ChatFadeOverlay(Direction direction, float opacity) {
        this.direction = direction;
        this.opacity = Math.max(0f, Math.min(1f, opacity));
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        Color background = resolveChatBackground();
        int alpha = Math.round(255 * opacity);
        Color opaque = new Color(background.getRed(), background.getGreen(), background.getBlue(), alpha);
        Color transparent = new Color(background.getRed(), background.getGreen(), background.getBlue(), 0);

        Color start = direction == Direction.TOP ? opaque : transparent;
        Color end = direction == Direction.TOP ? transparent : opaque;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        LinearGradientPaint fade = new LinearGradientPaint(
                new Point2D.Float(0, 0),
                new Point2D.Float(0, h),
                new float[] { 0f, 1f },
                new Color[] { start, end }
        );
        g2.setPaint(fade);
        g2.fillRect(0, 0, w, h);
        g2.dispose();
    }

    @Override
    public boolean contains(int x, int y) {
        return false;
    }

    private Color resolveChatBackground() {
        Color color = UIManager.getColor("Panel.background");
        if (color == null) {
            color = getBackground();
        }
        return color != null ? color : new Color(245, 245, 245);
    }
}
