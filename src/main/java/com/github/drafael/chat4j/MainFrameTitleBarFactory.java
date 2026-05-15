package com.github.drafael.chat4j;

import com.github.drafael.chat4j.util.TitleBarUiSupport;
import lombok.NonNull;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.function.Consumer;

public class MainFrameTitleBarFactory {

    public TitleBar create(
            @NonNull Class<?> resourceAnchor,
            @NonNull JComponent modelSelector,
            @NonNull JComponent renderModeControls,
            @NonNull Runnable toggleSidebarAction,
            @NonNull Consumer<JButton> searchAction,
            @NonNull Runnable newChatAction
    ) {
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setOpaque(false);
        titleBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        leftButtons.setOpaque(false);
        leftButtons.setBorder(BorderFactory.createEmptyBorder(0, 78, 0, 0));

        Icon sidebarToggleFilledIcon = TitleBarUiSupport.loadIcon(resourceAnchor, "/icons/titlebar/panel-left-filled.svg");
        Icon sidebarToggleOutlineIcon = TitleBarUiSupport.loadIcon(resourceAnchor, "/icons/titlebar/panel-left.svg");
        JButton sidebarToggleButton = TitleBarUiSupport.createButton(sidebarToggleFilledIcon, "Toggle Sidebar");
        sidebarToggleButton.addActionListener(e -> toggleSidebarAction.run());
        leftButtons.add(sidebarToggleButton);

        JButton searchButton = TitleBarUiSupport.createButton(
                TitleBarUiSupport.loadIcon(resourceAnchor, "/icons/titlebar/search.svg"),
                "Search Chats"
        );
        searchButton.addActionListener(e -> searchAction.accept(searchButton));
        leftButtons.add(searchButton);

        JButton newChatButton = TitleBarUiSupport.createButton(
                TitleBarUiSupport.loadIcon(resourceAnchor, "/icons/titlebar/square-pen.svg"),
                "New Chat"
        );
        newChatButton.addActionListener(e -> newChatAction.run());
        leftButtons.add(newChatButton);

        titleBar.add(leftButtons, BorderLayout.WEST);

        JPanel centerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
        centerPanel.setOpaque(false);
        centerPanel.add(modelSelector);
        titleBar.add(centerPanel, BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 4));
        rightPanel.setOpaque(false);
        rightPanel.add(renderModeControls);
        titleBar.add(rightPanel, BorderLayout.EAST);

        return new TitleBar(
                titleBar,
                leftButtons,
                rightPanel,
                sidebarToggleButton,
                searchButton,
                sidebarToggleFilledIcon,
                sidebarToggleOutlineIcon
        );
    }

    public record TitleBar(
            JPanel panel,
            JPanel leftButtons,
            JPanel rightPanel,
            JButton sidebarToggleButton,
            JButton searchButton,
            Icon sidebarToggleFilledIcon,
            Icon sidebarToggleOutlineIcon
    ) {
    }
}
