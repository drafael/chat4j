package com.github.drafael.chat4j.ui.components;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.ImageIcon;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ListTableActionComponentsTest {

    @Test
    @DisplayName("Action panel attaches its toolbar above or below the data view")
    void constructor_whenPlacementChanges_attachesToolbarToRequestedEdge() throws Exception {
        ListTableActionPanel topSubject = callOnEdt(() -> new ListTableActionPanel(
                new JList<>(),
                new ActionToolbar(),
                ToolbarPlacement.TOP,
                "No items"
        ));
        ListTableActionPanel bottomSubject = callOnEdt(() -> new ListTableActionPanel(
                new JTable(),
                new ActionToolbar(),
                ToolbarPlacement.BOTTOM,
                "No rows"
        ));
        try {
            runOnEdt(() -> {
                BorderLayout topLayout = (BorderLayout) topSubject.getLayout();
                BorderLayout bottomLayout = (BorderLayout) bottomSubject.getLayout();

                assertThat(topLayout.getLayoutComponent(BorderLayout.NORTH)).isSameAs(topSubject.toolbar());
                assertThat(topLayout.getLayoutComponent(BorderLayout.SOUTH)).isNull();
                assertThat(bottomLayout.getLayoutComponent(BorderLayout.SOUTH)).isSameAs(bottomSubject.toolbar());
                assertThat(bottomLayout.getLayoutComponent(BorderLayout.NORTH)).isNull();

                SwingUtilities.updateComponentTreeUI(topSubject);
                var scrollBorder = topSubject.scrollPane().getBorder();
                var borderInsets = scrollBorder.getBorderInsets(topSubject.scrollPane());
                assertThat(borderInsets.top).isZero();
                assertThat(borderInsets.left).isZero();
                assertThat(borderInsets.bottom).isZero();
                assertThat(borderInsets.right).isZero();
            });
        } finally {
            runOnEdt(() -> {
                topSubject.removeNotify();
                bottomSubject.removeNotify();
            });
            flushEdt();
        }
    }

    @Test
    @DisplayName("Icon actions retain Swing behavior and accessible metadata without visible text")
    void addIconAction_whenActionChanges_propagatesBehaviorAndEnabledState() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        Action action = new AbstractAction("Add item") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                invoked.set(true);
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, "Add a new item");
        IconActionButton subject = callOnEdt(() -> new IconActionButton(action, ActionIcon.ADD));
        try {
            runOnEdt(() -> {
                assertThat(subject.getText()).isNull();
                assertThat(subject.getIcon()).isNotNull();
                assertThat(subject.getToolTipText()).isEqualTo("Add a new item");
                assertThat(subject.getAccessibleContext().getAccessibleName()).isEqualTo("Add item");
                assertThat(subject.getAccessibleContext().getAccessibleDescription()).isEqualTo("Add a new item");
                assertThat(subject.getPreferredSize().width).isEqualTo(subject.getPreferredSize().height);
                var configuredIcon = subject.getIcon();

                subject.doClick();
                action.putValue(Action.SMALL_ICON, new ImageIcon());
                action.putValue(Action.LARGE_ICON_KEY, new ImageIcon());
                action.putValue(Action.NAME, "Create item");
                action.putValue(Action.SHORT_DESCRIPTION, "Create another item");
                action.setEnabled(false);

                assertThat(invoked).isTrue();
                assertThat(subject.isEnabled()).isFalse();
                assertThat(subject.getText()).isNull();
                assertThat(subject.getIcon()).isSameAs(configuredIcon);
                assertThat(subject.getToolTipText()).isEqualTo("Create another item");
                assertThat(subject.getAccessibleContext().getAccessibleName()).isEqualTo("Create item");
                assertThat(subject.getAccessibleContext().getAccessibleDescription())
                        .isEqualTo("Create another item");
            });
        } finally {
            runOnEdt(subject::removeNotify);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Every standard action provides a scalable SVG icon")
    void constructor_forEveryActionIcon_loadsSvgResource() throws Exception {
        IconActionButton[] subjects = callOnEdt(() -> java.util.Arrays.stream(ActionIcon.values())
                .map(icon -> new IconActionButton(noOpAction(icon.name()), icon))
                .toArray(IconActionButton[]::new));
        try {
            runOnEdt(() -> assertThat(subjects)
                    .allSatisfy(subject -> {
                        assertThat(subject.getIcon()).isNotNull();
                        assertThat(subject.getIcon().getIconWidth()).isPositive();
                        assertThat(subject.getIcon().getIconHeight()).isPositive();
                    }));
        } finally {
            runOnEdt(() -> java.util.Arrays.stream(subjects).forEach(Component::removeNotify));
            flushEdt();
        }
    }

    @Test
    @DisplayName("List empty state follows row changes and model replacement")
    void emptyState_whenListModelChanges_tracksCurrentModelOnly() throws Exception {
        DefaultListModel<String> originalModel = callOnEdt(DefaultListModel::new);
        JList<String> list = callOnEdt(() -> new JList<>(originalModel));
        ListTableActionPanel subject = callOnEdt(() -> new ListTableActionPanel(
                list,
                new ActionToolbar(),
                ToolbarPlacement.BOTTOM,
                "No items"
        ));
        try {
            runOnEdt(() -> {
                assertThat(subject.getAccessibleContext().getAccessibleDescription()).isEqualTo("No items");
                originalModel.addElement("First");
                assertThat(subject.getAccessibleContext().getAccessibleDescription()).isNull();

                var replacementModel = new DefaultListModel<String>();
                list.setModel(replacementModel);
                assertThat(subject.getAccessibleContext().getAccessibleDescription()).isEqualTo("No items");
                replacementModel.addElement("Replacement");
                assertThat(subject.getAccessibleContext().getAccessibleDescription()).isNull();

                originalModel.clear();
                assertThat(subject.getAccessibleContext().getAccessibleDescription()).isNull();
            });
        } finally {
            runOnEdt(subject::removeNotify);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Table empty state keeps the table header installed")
    void emptyState_whenTableRowsChange_preservesHeaderAndUpdatesDescription() throws Exception {
        DefaultTableModel model = callOnEdt(() -> new DefaultTableModel(new Object[] {"Name"}, 0));
        JTable table = callOnEdt(() -> new JTable(model));
        ListTableActionPanel subject = callOnEdt(() -> new ListTableActionPanel(
                table,
                new ActionToolbar(),
                ToolbarPlacement.TOP,
                "No rows"
        ));
        try {
            runOnEdt(() -> {
                assertThat(subject.scrollPane().getColumnHeader().getView()).isSameAs(table.getTableHeader());
                assertThat(subject.getAccessibleContext().getAccessibleDescription()).isEqualTo("No rows");
                subject.setSize(320, 180);
                subject.doLayout();
                subject.scrollPane().doLayout();
                var image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    subject.paint(graphics);
                } finally {
                    graphics.dispose();
                }

                model.addRow(new Object[] {"Value"});
                assertThat(subject.getAccessibleContext().getAccessibleDescription()).isNull();
                model.setRowCount(0);
                assertThat(subject.getAccessibleContext().getAccessibleDescription()).isEqualTo("No rows");
            });
        } finally {
            runOnEdt(subject::removeNotify);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Removing and reattaching the panel restores one empty-state listener")
    void addNotify_afterRemoval_reattachesModelListenerOnce() throws Exception {
        DefaultListModel<String> model = callOnEdt(DefaultListModel::new);
        ListTableActionPanel subject = callOnEdt(() -> new ListTableActionPanel(
                new JList<>(model),
                new ActionToolbar(),
                ToolbarPlacement.BOTTOM,
                "No items"
        ));
        try {
            runOnEdt(() -> {
                int attachedCount = model.getListDataListeners().length;
                subject.removeNotify();
                int detachedCount = model.getListDataListeners().length;
                subject.addNotify();
                int reattachedCount = model.getListDataListeners().length;

                assertThat(detachedCount).isEqualTo(attachedCount - 1);
                assertThat(reattachedCount).isEqualTo(attachedCount);
            });
        } finally {
            runOnEdt(subject::removeNotify);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Dropdown action opens its configured popup below a displayed button")
    void doClick_whenDropdownIsDisplayed_opensConfiguredPopup() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        var popupShown = new AtomicBoolean();
        JPopupMenu popup = callOnEdt(() -> new JPopupMenu() {
            @Override
            public void show(Component invoker, int x, int y) {
                popupShown.set(true);
            }
        });
        JFrame frame = callOnEdt(JFrame::new);
        Action dropdownAction = callOnEdt(() -> noOpAction("Add item"));
        DropdownActionButton subject = callOnEdt(() -> new DropdownActionButton(
                dropdownAction,
                ActionIcon.ADD,
                popup
        ));
        try {
            runOnEdt(() -> {
                frame.add(subject);
                frame.pack();
                frame.setVisible(true);
                var configuredIcon = subject.getIcon();
                dropdownAction.putValue(Action.SMALL_ICON, new ImageIcon());
                dropdownAction.putValue(Action.LARGE_ICON_KEY, new ImageIcon());
                subject.doClick();

                assertThat(subject.getIcon()).isSameAs(configuredIcon);
                assertThat(popupShown).isTrue();
            });
        } finally {
            runOnEdt(() -> {
                popup.setVisible(false);
                frame.dispose();
            });
            flushEdt();
        }
    }

    private static Action noOpAction(String name) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
            }
        };
    }

    private void runOnEdt(Runnable action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private void flushEdt() throws Exception {
        runOnEdt(() -> {
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }
}
