package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.webview.WebViewRuntimeStatus;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.net.URL;
import java.util.List;

import static java.util.Collections.emptyList;

public class SettingsDialog extends JDialog {

    private static final int SIDEBAR_WIDTH = 230;

    private JPanel titleBarSpacer;
    private JPanel actionBar;
    private JList<SettingsSection> sectionList;
    private List<SettingsSection> sections = emptyList();
    private boolean savingBeforeDispose;
    private final PropertyChangeListener lafChangeListener;

    private final Runnable exitAction;

    public SettingsDialog(@NonNull Frame owner, @NonNull SettingsRepo settingsRepo) {
        this(owner, settingsRepo, WebViewRuntimeStatus.jEditorPaneDefault(), () -> System.exit(0));
    }

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepo settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus
    ) {
        this(owner, settingsRepo, chatWebViewRuntimeStatus, () -> System.exit(0));
    }

    public SettingsDialog(
            @NonNull Frame owner,
            @NonNull SettingsRepo settingsRepo,
            @NonNull WebViewRuntimeStatus chatWebViewRuntimeStatus,
            @NonNull Runnable exitAction
    ) {
        super(owner, "Settings", true);
        this.exitAction = exitAction;

        configureDialog(owner);
        configureMacTitleBarIfNeeded();

        add(createSettingsShell(settingsRepo, chatWebViewRuntimeStatus), BorderLayout.CENTER);
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
        setSize(980, 680);
        setMinimumSize(new Dimension(840, 560));
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

    private JComponent createSettingsShell(SettingsRepo settingsRepo, WebViewRuntimeStatus chatWebViewRuntimeStatus) {
        sections = createSections(settingsRepo, chatWebViewRuntimeStatus);

        DefaultListModel<SettingsSection> sectionModel = new DefaultListModel<>();
        sections.forEach(sectionModel::addElement);

        sectionList = new JList<>(sectionModel);
        sectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sectionList.setFixedCellHeight(42);
        sectionList.setCellRenderer(new SettingsSectionRenderer());
        sectionList.setBorder(new EmptyBorder(6, 6, 6, 6));
        Color selectionBackground = UIManager.getColor("List.selectionBackground");
        Color selectionForeground = UIManager.getColor("List.selectionForeground");
        if (selectionBackground != null) {
            sectionList.setSelectionBackground(selectionBackground);
        }
        if (selectionForeground != null) {
            sectionList.setSelectionForeground(selectionForeground);
        }

        JScrollPane sidebar = new JScrollPane(sectionList);
        sidebar.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        sidebar.setBorder(BorderFactory.createMatteBorder(
                0,
                0,
                0,
                1,
                UIManager.getColor("Separator.foreground")
        ));

        CardLayout cardsLayout = new CardLayout();
        JPanel cardsPanel = new JPanel(cardsLayout);
        sections.forEach(section -> cardsPanel.add(section.content(), section.id()));

        sectionList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }

            SettingsSection selected = sectionList.getSelectedValue();
            if (selected != null) {
                cardsLayout.show(cardsPanel, selected.id());
            }
        });
        sectionList.setSelectedIndex(0);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, cardsPanel);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerSize(1);
        splitPane.setDividerLocation(SIDEBAR_WIDTH);
        splitPane.setResizeWeight(0);
        splitPane.setBorder(null);

        return splitPane;
    }

    private List<SettingsSection> createSections(SettingsRepo settingsRepo, WebViewRuntimeStatus chatWebViewRuntimeStatus) {
        return List.of(
                new SettingsSection("general", "General", "/icons/sidebar/settings.svg", new GeneralPanel(settingsRepo, exitAction)),
                new SettingsSection("appearance", "Appearance", "/icons/settings/palette.svg", new AppearancePanel(settingsRepo, chatWebViewRuntimeStatus)),
                new SettingsSection("providers", "Providers", "/icons/settings/cpu.svg", new ProvidersPanel(settingsRepo)),
                new SettingsSection("prompts", "Prompts", "/icons/settings/book-open.svg", new PromptsPanel(settingsRepo))
        );
    }

    private JComponent createActionBar() {
        actionBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        actionBar.add(closeButton);

        return actionBar;
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
                }
        );
    }

    private void installLifecycleCleanup() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                UIManager.removePropertyChangeListener(lafChangeListener);
            }
        });
    }

    @Override
    public void dispose() {
        SavePendingResult saveResult = savePendingPanelChangesResult();
        if (!saveResult.saved()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Prompt settings could not be saved:\n\n%s".formatted(saveResult.message()),
                    "Settings Not Saved",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }
        super.dispose();
    }

    private SavePendingResult savePendingPanelChangesResult() {
        if (savingBeforeDispose) {
            return new SavePendingResult(true, "");
        }
        savingBeforeDispose = true;
        try {
            return sections.stream()
                    .map(SettingsSection::content)
                    .filter(PromptsPanel.class::isInstance)
                    .map(PromptsPanel.class::cast)
                    .filter(panel -> !panel.savePendingChanges())
                    .findFirst()
                    .map(panel -> new SavePendingResult(false, panel.lastSaveError()))
                    .orElseGet(() -> new SavePendingResult(true, ""));
        } finally {
            savingBeforeDispose = false;
        }
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

    private static final class SettingsSectionRenderer extends DefaultListCellRenderer {

        private static final int ICON_SIZE = 20;

        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SettingsSection section) {
                label.setText(section.title());
                label.setIcon(loadSectionIcon(section.iconPath(), label.getForeground()));
            }
            if (isSelected) {
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
                label.setIcon(value instanceof SettingsSection section
                        ? loadSectionIcon(section.iconPath(), list.getSelectionForeground())
                        : null);
                label.setOpaque(true);
            }
            label.setIconTextGap(12);
            label.setBorder(new EmptyBorder(6, 12, 6, 12));
            return label;
        }

        private Icon loadSectionIcon(String iconPath, Color color) {
            URL url = SettingsDialog.class.getResource(iconPath);
            if (url == null) {
                return null;
            }
            FlatSVGIcon icon = new FlatSVGIcon(url).derive(ICON_SIZE, ICON_SIZE);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, original) -> color));
            return icon;
        }
    }

    private record SettingsSection(String id, String title, String iconPath, JComponent content) {
    }

    private record SavePendingResult(boolean saved, String message) {
    }
}
