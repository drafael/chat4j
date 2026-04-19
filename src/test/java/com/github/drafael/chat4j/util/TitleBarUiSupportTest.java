package com.github.drafael.chat4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.SwingConstants;
import java.awt.Cursor;
import java.awt.Dimension;

import static org.assertj.core.api.Assertions.assertThat;

class TitleBarUiSupportTest {

    @Test
    @DisplayName("Create button configures title-bar specific UI properties")
    void createButton_whenCalled_appliesTitleBarButtonConfiguration() {
        JButton button = TitleBarUiSupport.createButton(null, "Toggle Sidebar");

        assertThat(button.getClientProperty("JButton.buttonType")).isEqualTo("borderless");
        assertThat(button.getToolTipText()).isEqualTo("Toggle Sidebar");
        assertThat(button.isFocusable()).isFalse();
        assertThat(button.getCursor().getType()).isEqualTo(Cursor.HAND_CURSOR);
        assertThat(button.getPreferredSize()).isEqualTo(new Dimension(24, 24));
        assertThat(button.getMinimumSize()).isEqualTo(new Dimension(24, 24));
        assertThat(button.getMaximumSize()).isEqualTo(new Dimension(24, 24));
        assertThat(button.getHorizontalAlignment()).isEqualTo(SwingConstants.CENTER);
        assertThat(button.getVerticalAlignment()).isEqualTo(SwingConstants.CENTER);
    }

    @Test
    @DisplayName("Load icon returns icon for existing resource and null for missing resource")
    void loadIcon_whenResourceExistsOrMissing_returnsExpectedValue() {
        Icon existing = TitleBarUiSupport.loadIcon(TitleBarUiSupport.class, "/icons/titlebar/search.svg");
        Icon missing = TitleBarUiSupport.loadIcon(TitleBarUiSupport.class, "/icons/titlebar/does-not-exist.svg");

        assertThat(existing).isNotNull();
        assertThat(missing).isNull();
    }
}
