package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.net.URL;
import java.util.Objects;

final class ThemeAwareSvgIcon implements Icon {
    private final FlatSVGIcon svg;
    private Color currentTint;

    ThemeAwareSvgIcon(URL url, int size) {
        this.svg = new FlatSVGIcon(url).derive(size, size);
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Color label = UIManager.getColor("Label.foreground");
        if (!Objects.equals(label, currentTint)) {
            Color tint = label != null ? label : new Color(90, 90, 90);
            svg.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> new Color(
                    tint.getRed(),
                    tint.getGreen(),
                    tint.getBlue(),
                    color.getAlpha()
            )));
            currentTint = label;
        }
        svg.paintIcon(c, g, x, y);
    }

    @Override
    public int getIconWidth() {
        return svg.getIconWidth();
    }

    @Override
    public int getIconHeight() {
        return svg.getIconHeight();
    }
}
