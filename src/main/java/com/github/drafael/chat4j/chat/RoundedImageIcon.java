package com.github.drafael.chat4j.chat;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

final class RoundedImageIcon implements Icon {

    private final BufferedImage image;
    private final int size;
    private final int arc;

    RoundedImageIcon(Path path, int size) {
        this(load(path), size);
    }

    RoundedImageIcon(BufferedImage image, int size) {
        this.image = image;
        this.size = size;
        this.arc = Math.max(4, size / 4);
    }

    boolean hasImage() {
        return image != null;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        if (image == null) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        Shape clip = new RoundRectangle2D.Float(x, y, size, size, arc, arc);
        g2.setClip(clip);
        drawCentered(g2, x, y);
        g2.dispose();
    }

    private void drawCentered(Graphics2D g2, int x, int y) {
        int srcW = image.getWidth();
        int srcH = image.getHeight();
        double scale = Math.max((double) size / srcW, (double) size / srcH);
        int drawW = (int) Math.round(srcW * scale);
        int drawH = (int) Math.round(srcH * scale);
        int drawX = x + (size - drawW) / 2;
        int drawY = y + (size - drawH) / 2;
        g2.drawImage(image, drawX, drawY, drawW, drawH, null);
    }

    @Override
    public int getIconWidth() {
        return size;
    }

    @Override
    public int getIconHeight() {
        return size;
    }

    private static BufferedImage load(Path path) {
        if (path == null) {
            return null;
        }
        File file = path.toFile();
        if (!file.exists()) {
            return null;
        }
        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            return null;
        }
    }
}
