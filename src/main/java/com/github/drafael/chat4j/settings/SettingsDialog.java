package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.util.List;

public class SettingsDialog extends JDialog {

    private JPanel titleBarSpacer;
    private JPanel actionBar;
    private final PropertyChangeListener lafChangeListener;

    public SettingsDialog(Frame owner, SettingsRepo settingsRepo) {
        super(owner, "Settings", true);

        configureDialog(owner);
        configureMacTitleBarIfNeeded();

        add(createTabbedPane(settingsRepo), BorderLayout.CENTER);
        add(createActionBar(), BorderLayout.SOUTH);

        lafChangeListener = event -> {
            if ("lookAndFeel".equals(event.getPropertyName())) {
                SwingUtilities.invokeLater(this::applyThemeStyles);
            }
        };
        UIManager.addPropertyChangeListener(lafChangeListener);

        applyThemeStyles();
        installEscapeCloseAction();
        installLifecycleCleanup();
    }

    private void configureDialog(Frame owner) {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(900, 640);
        setMinimumSize(new Dimension(760, 520));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());
    }

    private void configureMacTitleBarIfNeeded() {
        if (!SystemInfo.isMacOS) {
            return;
        }

        getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
        getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
        getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
        setTitle("");

        titleBarSpacer = new JPanel();
        titleBarSpacer.setPreferredSize(new Dimension(0, 26));
        add(titleBarSpacer, BorderLayout.NORTH);
    }

    private JComponent createActionBar() {
        actionBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionBar.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        actionBar.add(closeButton);

        return actionBar;
    }

    private JTabbedPane createTabbedPane(SettingsRepo settingsRepo) {
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.putClientProperty("JTabbedPane.tabType", "underlined");
        tabbedPane.putClientProperty("JTabbedPane.tabWidthMode", "equal");
        tabbedPane.putClientProperty("JTabbedPane.tabHeight", 36);

        for (TabSpec tab : createTabs(settingsRepo)) {
            tabbedPane.addTab(tab.title(), tab.content());
        }

        return tabbedPane;
    }

    private List<TabSpec> createTabs(SettingsRepo settingsRepo) {
        return List.of(
            new TabSpec("General", new GeneralPanel(settingsRepo)),
            new TabSpec("Appearance", new AppearancePanel(settingsRepo)),
            new TabSpec("Providers", new ProvidersPanel(settingsRepo)),
            new TabSpec("Advanced", new AdvancedPanel(settingsRepo)));
    }

    private void installEscapeCloseAction() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put(
            "close",
            new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
    }

    private void installLifecycleCleanup() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                UIManager.removePropertyChangeListener(lafChangeListener);
            }
        });
    }

    private void applyThemeStyles() {
        Color panelBackground = UIManager.getColor("Panel.background");
        if (panelBackground == null) {
            panelBackground = getBackground();
        }

        getContentPane().setBackground(panelBackground);
        if (titleBarSpacer != null) {
            titleBarSpacer.setOpaque(true);
            titleBarSpacer.setBackground(panelBackground);
        }
        if (actionBar != null) {
            actionBar.setOpaque(true);
            actionBar.setBackground(panelBackground);
        }
    }

    private record TabSpec(String title, JComponent content) {
    }
}
