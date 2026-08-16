package com.github.drafael.chat4j.ui.components;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.Color;
import java.net.URL;
import javax.swing.Icon;
import javax.swing.UIManager;

import static java.util.Objects.requireNonNull;

public enum ActionIcon {
    ADD("/icons/actions/add.svg"),
    REMOVE("/icons/actions/remove.svg"),
    EDIT("/icons/actions/edit.svg"),
    MOVE_UP("/icons/actions/arrow-up.svg"),
    MOVE_DOWN("/icons/actions/arrow-down.svg");

    private final String resourcePath;

    ActionIcon(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    Icon load(int size) {
        URL resource = requireNonNull(
                ActionIcon.class.getResource(resourcePath),
                "Missing action icon resource: %s".formatted(resourcePath)
        );
        FlatSVGIcon icon = new FlatSVGIcon(resource).derive(size, size);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, original) -> {
            Color fallback = UIManager.getColor("Label.foreground");
            Color tint = component != null && component.getForeground() != null
                    ? component.getForeground()
                    : fallback;
            if (tint == null) {
                tint = original;
            }
            return new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), original.getAlpha());
        }));
        return icon;
    }
}
