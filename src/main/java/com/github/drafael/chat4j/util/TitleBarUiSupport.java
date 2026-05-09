package com.github.drafael.chat4j.util;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.net.URL;

public final class TitleBarUiSupport {

    private static final int TITLE_BAR_ICON_SIZE = 16;
    private static final int BUTTON_SIZE = 24;

    private TitleBarUiSupport() {
    }

    public static JButton createButton(Icon icon, String tooltip) {
        JButton btn = new JButton(icon);
        btn.putClientProperty("JButton.buttonType", "borderless");
        btn.setToolTipText(tooltip);
        btn.setFocusable(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        btn.setMinimumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        btn.setMaximumSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        btn.setHorizontalAlignment(SwingConstants.CENTER);
        btn.setVerticalAlignment(SwingConstants.CENTER);
        return btn;
    }

    public static Icon loadIcon(@NonNull Class<?> resourceAnchor, String resourcePath) {
        Validate.notBlank(resourcePath, "resourcePath must not be blank");

        URL url = resourceAnchor.getResource(resourcePath);
        if (url == null) {
            return null;
        }

        FlatSVGIcon icon = new FlatSVGIcon(url).derive(TITLE_BAR_ICON_SIZE, TITLE_BAR_ICON_SIZE);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
            Color foreground = component != null ? component.getForeground() : null;
            if (foreground == null) {
                foreground = UIManager.getColor("Label.foreground");
            }
            if (foreground == null) {
                foreground = new Color(90, 90, 90);
            }
            return new Color(
                    foreground.getRed(),
                    foreground.getGreen(),
                    foreground.getBlue(),
                    color.getAlpha());
        }));
        return icon.hasFound() ? icon : null;
    }
}
