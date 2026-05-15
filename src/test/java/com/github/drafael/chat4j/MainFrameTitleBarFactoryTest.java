package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameTitleBarFactoryTest {

    private final MainFrameTitleBarFactory subject = new MainFrameTitleBarFactory();

    @Test
    @DisplayName("Title bar centers model selector and wires button actions")
    void create_whenActionsProvided_buildsTitleBarAndDispatchesActions() {
        var toggleInvoked = new AtomicBoolean();
        var searchButton = new AtomicReference<JButton>();
        var newChatInvoked = new AtomicBoolean();
        var modelSelector = new JLabel("model");
        var renderModeControls = new JLabel("render controls");

        MainFrameTitleBarFactory.TitleBar titleBar = subject.create(
                MainFrame.class,
                modelSelector,
                renderModeControls,
                () -> toggleInvoked.set(true),
                searchButton::set,
                () -> newChatInvoked.set(true)
        );

        titleBar.sidebarToggleButton().doClick();
        JButton search = findButton(titleBar.panel(), "Search Chats");
        JButton newChat = findButton(titleBar.panel(), "New Chat");
        assertThat(titleBar.leftButtons()).isNotNull();
        assertThat(titleBar.rightPanel()).isNotNull();
        assertThat(titleBar.searchButton()).isSameAs(search);
        assertThat(search).isNotNull();
        assertThat(newChat).isNotNull();

        search.doClick();
        newChat.doClick();

        assertThat(titleBar.panel().isAncestorOf(modelSelector)).isTrue();
        assertThat(titleBar.rightPanel().isAncestorOf(renderModeControls)).isTrue();
        assertThat(findLabel(titleBar.panel(), "Chat4J")).isNull();
        assertThat(toggleInvoked).isTrue();
        assertThat(searchButton).hasValue(search);
        assertThat(newChatInvoked).isTrue();
    }

    private JLabel findLabel(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) {
                return label;
            }
            if (component instanceof Container childContainer) {
                JLabel label = findLabel(childContainer, text);
                if (label != null) {
                    return label;
                }
            }
        }
        return null;
    }

    private JButton findButton(Container container, String tooltip) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && tooltip.equals(button.getToolTipText())) {
                return button;
            }
            if (component instanceof Container childContainer) {
                JButton button = findButton(childContainer, tooltip);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }
}
