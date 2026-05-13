package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.JPanel;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameSidebarToggleStateTest {

    @Test
    @DisplayName("Default sidebar toggle state starts without button and icons")
    void defaults_whenConstructed_startWithoutButtonAndIcons() {
        var subject = new MainFrameSidebarToggleState();

        assertThat(subject.sidebarToggleButton()).isNull();
        assertThat(subject.searchButton()).isNull();
        assertThat(subject.leftButtons()).isNull();
        assertThat(subject.rightPanel()).isNull();
        assertThat(subject.sidebarToggleFilledIcon()).isNull();
        assertThat(subject.sidebarToggleOutlineIcon()).isNull();
    }

    @Test
    @DisplayName("Setters update tracked sidebar toggle button and icon references")
    void setters_whenCalled_updateButtonAndIconReferences() {
        var subject = new MainFrameSidebarToggleState();
        var button = new JButton("Toggle Sidebar");
        var searchButton = new JButton("Search Chats");
        var leftButtons = new JPanel();
        var rightPanel = new JPanel();
        Icon filledIcon = new ImageIcon();
        Icon outlineIcon = new ImageIcon();

        subject.setSidebarToggleButton(button);
        subject.setSearchButton(searchButton);
        subject.setLeftButtons(leftButtons);
        subject.setRightPanel(rightPanel);
        subject.setSidebarToggleFilledIcon(filledIcon);
        subject.setSidebarToggleOutlineIcon(outlineIcon);

        assertThat(subject.sidebarToggleButton()).isSameAs(button);
        assertThat(subject.searchButton()).isSameAs(searchButton);
        assertThat(subject.leftButtons()).isSameAs(leftButtons);
        assertThat(subject.rightPanel()).isSameAs(rightPanel);
        assertThat(subject.sidebarToggleFilledIcon()).isSameAs(filledIcon);
        assertThat(subject.sidebarToggleOutlineIcon()).isSameAs(outlineIcon);
    }
}
