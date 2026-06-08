package com.github.drafael.chat4j.chat.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.net.URL;

public class JumpToLatestButton extends JButton {

    private static final int BUTTON_SIZE = 40;
    private static final int ICON_SIZE = 16;
    private static final int RING_GAP = 2;
    private static final int RING_THICKNESS = 2;
    private static final int RING_ARC_SWEEP = 90;
    private static final int RING_ROTATION_STEP = 8;
    private static final int RING_FRAME_DELAY_MS = 60;

    private final Timer ringTimer;
    private boolean streaming;
    private int ringAngle;

    public JumpToLatestButton() {
        setFocusable(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Jump to latest");
        setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        setMinimumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        setMaximumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        setMargin(new Insets(0, 0, 0, 0));
        setIcon(loadArrowIcon());
        setHorizontalAlignment(SwingConstants.CENTER);
        setVerticalAlignment(SwingConstants.CENTER);

        ringTimer = new Timer(RING_FRAME_DELAY_MS, e -> {
            ringAngle = (ringAngle + RING_ROTATION_STEP) % 360;
            repaint();
        });
        ringTimer.setRepeats(true);
    }

    public void setStreaming(boolean streaming) {
        if (this.streaming == streaming) {
            return;
        }

        this.streaming = streaming;
        if (streaming) {
            ringAngle = 0;
            ringTimer.start();
        } else {
            ringTimer.stop();
        }
        repaint();
    }

    public boolean isStreaming() {
        return streaming;
    }

    @Override
    public void removeNotify() {
        ringTimer.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        int size = Math.min(getWidth(), getHeight());
        int inset = streaming ? RING_GAP + RING_THICKNESS : 1;
        int diameter = size - inset * 2;
        int x = (getWidth() - diameter) / 2;
        int y = (getHeight() - diameter) / 2;

        Ellipse2D fill = new Ellipse2D.Float(x, y, diameter, diameter);
        g2.setColor(resolveFillColor());
        g2.fill(fill);

        g2.setColor(resolveBorderColor());
        g2.setStroke(new BasicStroke(1f));
        g2.draw(fill);

        if (streaming) {
            paintRing(g2, size);
        }

        g2.dispose();
        super.paintComponent(g);
    }

    private void paintRing(Graphics2D g2, int size) {
        int ringDiameter = size - RING_THICKNESS;
        int ringX = (getWidth() - ringDiameter) / 2;
        int ringY = (getHeight() - ringDiameter) / 2;

        Color trackColor = resolveRingTrackColor();
        if (trackColor != null) {
            g2.setColor(trackColor);
            g2.setStroke(new BasicStroke(RING_THICKNESS, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(new Ellipse2D.Float(ringX, ringY, ringDiameter, ringDiameter));
        }

        g2.setColor(resolveRingColor());
        g2.setStroke(new BasicStroke(RING_THICKNESS, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Arc2D.Float(ringX, ringY, ringDiameter, ringDiameter, ringAngle, RING_ARC_SWEEP, Arc2D.OPEN));
    }

    private Color resolveFillColor() {
        Color color = UIManager.getColor("Button.background");
        if (color == null) {
            color = UIManager.getColor("Panel.background");
        }
        return color != null ? color : new Color(235, 235, 235);
    }

    private Color resolveBorderColor() {
        Color color = UIManager.getColor("Button.borderColor");
        if (color == null) {
            color = UIManager.getColor("Component.borderColor");
        }
        return color != null ? color : new Color(200, 200, 200);
    }

    private Color resolveRingColor() {
        Color color = UIManager.getColor("ProgressBar.foreground");
        if (color == null) {
            color = UIManager.getColor("Component.accentColor");
        }
        return color != null ? color : new Color(70, 130, 230);
    }

    private Color resolveRingTrackColor() {
        Color base = UIManager.getColor("ProgressBar.background");
        if (base == null) {
            base = UIManager.getColor("Component.borderColor");
        }
        if (base == null) {
            return null;
        }
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 96);
    }

    private Icon loadArrowIcon() {
        URL url = JumpToLatestButton.class.getResource("/icons/chat/arrow-down.svg");
        if (url == null) {
            return UIManager.getIcon("Tree.collapsedIcon");
        }

        FlatSVGIcon icon = new FlatSVGIcon(url).derive(ICON_SIZE, ICON_SIZE);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
            Color tint = UIManager.getColor("Label.foreground");
            if (tint == null) {
                tint = new Color(60, 60, 60);
            }
            return new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), color.getAlpha());
        }));
        return icon.hasFound() ? icon : UIManager.getIcon("Tree.collapsedIcon");
    }
}
