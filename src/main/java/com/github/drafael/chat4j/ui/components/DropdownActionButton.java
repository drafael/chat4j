package com.github.drafael.chat4j.ui.components;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.util.PopupMenuSupport;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.net.URL;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import lombok.NonNull;

import static java.util.Objects.requireNonNull;

public final class DropdownActionButton extends IconActionButton {

    private static final int DISCLOSURE_SIZE = 5;
    private final JPopupMenu popupMenu;

    public DropdownActionButton(
            @NonNull Action action,
            @NonNull ActionIcon icon,
            @NonNull JPopupMenu popupMenu
    ) {
        super(action, icon);
        this.popupMenu = PopupMenuSupport.configureNativeSafePopup(popupMenu);
        setConfiguredIcon(new DisclosureIcon(getIcon(), loadDisclosureIcon()));
        addActionListener(event -> showPopupMenu());
    }

    private void showPopupMenu() {
        if (isEnabled() && isShowing()) {
            SwingUtilities.updateComponentTreeUI(popupMenu);
            popupMenu.show(this, 0, getHeight());
        }
    }

    private Icon loadDisclosureIcon() {
        String resourcePath = "/icons/actions/disclosure.svg";
        URL resource = requireNonNull(
                DropdownActionButton.class.getResource(resourcePath),
                "Missing action icon resource: %s".formatted(resourcePath)
        );
        FlatSVGIcon icon = new FlatSVGIcon(resource).derive(DISCLOSURE_SIZE, DISCLOSURE_SIZE);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, original) -> {
            var tint = component != null && component.getForeground() != null
                    ? component.getForeground()
                    : original;
            return new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), original.getAlpha());
        }));
        return icon;
    }

    private record DisclosureIcon(Icon actionIcon, Icon disclosureIcon) implements Icon {

        private static final int DISCLOSURE_OVERHANG = 2;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            actionIcon.paintIcon(component, graphics, x, y);
            int disclosureX = x + actionIcon.getIconWidth() - DISCLOSURE_OVERHANG;
            int disclosureY = y + actionIcon.getIconHeight() - disclosureIcon.getIconHeight();
            disclosureIcon.paintIcon(component, graphics, disclosureX, disclosureY);
        }

        @Override
        public int getIconWidth() {
            return actionIcon.getIconWidth() + disclosureIcon.getIconWidth() - DISCLOSURE_OVERHANG;
        }

        @Override
        public int getIconHeight() {
            return actionIcon.getIconHeight();
        }
    }
}
