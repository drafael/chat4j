package com.github.drafael.chat4j.ui.components;

import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JToolBar;
import lombok.NonNull;

public final class ActionToolbar extends JToolBar {

    public ActionToolbar() {
        setFloatable(false);
        setRollover(true);
        setFocusable(false);
    }

    public IconActionButton addIconAction(@NonNull Action action, @NonNull ActionIcon icon) {
        var button = new IconActionButton(action, icon);
        add(button);
        return button;
    }

    public DropdownActionButton addDropdownAction(
            @NonNull Action action,
            @NonNull ActionIcon icon,
            @NonNull JPopupMenu popupMenu
    ) {
        var button = new DropdownActionButton(action, icon, popupMenu);
        add(button);
        return button;
    }

    public JButton addLabeledAction(@NonNull Action action) {
        var button = new JButton(action);
        var wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(button);
        add(wrapper);
        return button;
    }

    public void addGap(int width) {
        addSeparator(new Dimension(UIScale.scale(width), 0));
    }
}
