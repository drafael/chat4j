package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.ImageIcon;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameSidebarToggleStateTest {

    @Test
    @DisplayName("Default sidebar toggle state starts without button and icons")
    void defaults_whenConstructed_startWithoutButtonAndIcons() {
        var subject = new MainFrameSidebarToggleState();

        assertThat(subject.sidebarToggleButton()).isNull();
        assertThat(subject.sidebarToggleFilledIcon()).isNull();
        assertThat(subject.sidebarToggleOutlineIcon()).isNull();
    }

    @Test
    @DisplayName("Setters update tracked sidebar toggle button and icon references")
    void setters_whenCalled_updateButtonAndIconReferences() {
        var subject = new MainFrameSidebarToggleState();
        var button = new JButton("Toggle Sidebar");
        Icon filledIcon = new ImageIcon();
        Icon outlineIcon = new ImageIcon();

        subject.setSidebarToggleButton(button);
        subject.setSidebarToggleFilledIcon(filledIcon);
        subject.setSidebarToggleOutlineIcon(outlineIcon);

        assertThat(subject.sidebarToggleButton()).isSameAs(button);
        assertThat(subject.sidebarToggleFilledIcon()).isSameAs(filledIcon);
        assertThat(subject.sidebarToggleOutlineIcon()).isSameAs(outlineIcon);
    }
}
