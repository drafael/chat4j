package com.github.drafael.chat4j.ui.components;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.Dimension;
import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JButton;
import lombok.NonNull;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;

public class IconActionButton extends JButton {

    private static final int BUTTON_SIZE = 28;
    private static final int ICON_SIZE = 16;
    private Icon configuredIcon;

    public IconActionButton(@NonNull Action action, @NonNull ActionIcon icon) {
        super(action);
        setText(null);
        setConfiguredIcon(icon.load(ICON_SIZE));
        putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        Dimension size = new Dimension(UIScale.scale(BUTTON_SIZE), UIScale.scale(BUTTON_SIZE));
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        configureMetadata(action);
    }

    @Override
    protected void actionPropertyChanged(Action action, String propertyName) {
        super.actionPropertyChanged(action, propertyName);
        if (action == null) {
            setText(null);
            setToolTipText(null);
            getAccessibleContext().setAccessibleName(null);
            getAccessibleContext().setAccessibleDescription(null);
            return;
        }
        if (Action.NAME.equals(propertyName) || Action.SHORT_DESCRIPTION.equals(propertyName)) {
            setText(null);
            configureMetadata(action);
        } else if ((Action.SMALL_ICON.equals(propertyName) || Action.LARGE_ICON_KEY.equals(propertyName))
                && configuredIcon != null) {
            setIcon(configuredIcon);
        }
    }

    protected final void setConfiguredIcon(@NonNull Icon icon) {
        configuredIcon = icon;
        setIcon(configuredIcon);
    }

    private void configureMetadata(Action action) {
        String actionName = action.getValue(Action.NAME) instanceof String value ? value : "";
        String description = action.getValue(Action.SHORT_DESCRIPTION) instanceof String value
                ? value
                : actionName;
        String accessibleName = defaultIfBlank(actionName, description);
        setToolTipText(defaultIfBlank(description, accessibleName));
        getAccessibleContext().setAccessibleName(accessibleName);
        getAccessibleContext().setAccessibleDescription(defaultIfBlank(description, accessibleName));
    }
}
