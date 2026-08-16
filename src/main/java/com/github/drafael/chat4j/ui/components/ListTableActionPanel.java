package com.github.drafael.chat4j.ui.components;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import javax.swing.BorderFactory;
import javax.swing.JLayer;
import javax.swing.JList;
import javax.swing.ListModel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.plaf.LayerUI;
import javax.swing.table.TableModel;
import lombok.NonNull;

import static java.util.Objects.requireNonNullElse;

public final class ListTableActionPanel extends JPanel {

    private final ActionToolbar toolbar;
    private final ToolbarPlacement placement;
    private final JList<?> list;
    private final JTable table;
    private final JScrollPane scrollPane;
    private final JLayer<JScrollPane> contentLayer;
    private final String emptyStateText;
    private final ListDataListener listDataListener = new ListDataListener() {
        @Override
        public void intervalAdded(ListDataEvent event) {
            refreshEmptyState();
        }

        @Override
        public void intervalRemoved(ListDataEvent event) {
            refreshEmptyState();
        }

        @Override
        public void contentsChanged(ListDataEvent event) {
            refreshEmptyState();
        }
    };
    private final TableModelListener tableModelListener = this::tableModelChanged;
    private final PropertyChangeListener modelPropertyListener = this::modelChanged;
    private boolean observingModel;

    public ListTableActionPanel(
            @NonNull JList<?> list,
            @NonNull ActionToolbar toolbar,
            @NonNull ToolbarPlacement placement,
            String emptyStateText
    ) {
        this(list, null, toolbar, placement, emptyStateText);
    }

    public ListTableActionPanel(
            @NonNull JTable table,
            @NonNull ActionToolbar toolbar,
            @NonNull ToolbarPlacement placement,
            String emptyStateText
    ) {
        this(null, table, toolbar, placement, emptyStateText);
    }

    private ListTableActionPanel(
            JList<?> list,
            JTable table,
            ActionToolbar toolbar,
            ToolbarPlacement placement,
            String emptyStateText
    ) {
        super(new BorderLayout());
        this.list = list;
        this.table = table;
        this.toolbar = toolbar;
        this.placement = placement;
        this.emptyStateText = requireNonNullElse(emptyStateText, "");
        scrollPane = new JScrollPane(list != null ? list : table);
        if (table != null) {
            scrollPane.setColumnHeaderView(table.getTableHeader());
        }
        contentLayer = new JLayer<>(scrollPane, new EmptyStateLayerUI());
        add(toolbar, placement == ToolbarPlacement.TOP ? BorderLayout.NORTH : BorderLayout.SOUTH);
        add(contentLayer, BorderLayout.CENTER);
        applyBorders();
        attachModelObservation();
        refreshEmptyState();
    }

    public JScrollPane scrollPane() {
        return scrollPane;
    }

    public ActionToolbar toolbar() {
        return toolbar;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        attachModelObservation();
        refreshEmptyState();
    }

    @Override
    public void removeNotify() {
        detachModelObservation();
        super.removeNotify();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (toolbar != null && scrollPane != null) {
            applyBorders();
        }
    }

    private void applyBorders() {
        Border scrollBorder = UIManager.getBorder("ScrollPane.border");
        setBorder(scrollBorder != null ? scrollBorder : BorderFactory.createLineBorder(borderColor()));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        Color separator = borderColor();
        toolbar.setBorder(placement == ToolbarPlacement.TOP
                ? BorderFactory.createMatteBorder(0, 0, 1, 0, separator)
                : BorderFactory.createMatteBorder(1, 0, 0, 0, separator));
    }

    private Color borderColor() {
        Color color = UIManager.getColor("Component.borderColor");
        if (color == null) {
            color = UIManager.getColor("Separator.foreground");
        }
        return color != null ? color : Color.GRAY;
    }

    private void attachModelObservation() {
        if (observingModel || emptyStateText.isBlank()) {
            return;
        }
        observingModel = true;
        if (list != null) {
            list.addPropertyChangeListener("model", modelPropertyListener);
            list.getModel().addListDataListener(listDataListener);
        } else {
            table.addPropertyChangeListener("model", modelPropertyListener);
            table.getModel().addTableModelListener(tableModelListener);
        }
    }

    private void detachModelObservation() {
        if (!observingModel) {
            return;
        }
        observingModel = false;
        if (list != null) {
            list.removePropertyChangeListener("model", modelPropertyListener);
            list.getModel().removeListDataListener(listDataListener);
        } else {
            table.removePropertyChangeListener("model", modelPropertyListener);
            table.getModel().removeTableModelListener(tableModelListener);
        }
    }

    private void modelChanged(PropertyChangeEvent event) {
        if (list != null) {
            if (event.getOldValue() instanceof ListModel<?> oldModel) {
                oldModel.removeListDataListener(listDataListener);
            }
            list.getModel().addListDataListener(listDataListener);
        } else {
            if (event.getOldValue() instanceof TableModel oldModel) {
                oldModel.removeTableModelListener(tableModelListener);
            }
            table.getModel().addTableModelListener(tableModelListener);
        }
        refreshEmptyState();
    }

    private void tableModelChanged(TableModelEvent event) {
        refreshEmptyState();
    }

    private boolean isEmpty() {
        return list != null ? list.getModel().getSize() == 0 : table.getModel().getRowCount() == 0;
    }

    private void refreshEmptyState() {
        boolean empty = !emptyStateText.isBlank() && isEmpty();
        getAccessibleContext().setAccessibleDescription(empty ? emptyStateText : null);
        contentLayer.repaint();
    }

    private final class EmptyStateLayerUI extends LayerUI<JScrollPane> {
        @Override
        public void paint(Graphics graphics, javax.swing.JComponent component) {
            super.paint(graphics, component);
            if (emptyStateText.isBlank() || !isEmpty() || !(component instanceof JLayer<?>)) {
                return;
            }
            JViewport viewport = scrollPane.getViewport();
            Rectangle bounds = viewport.getBounds();
            Font font = requireNonNullElse(UIManager.getFont("Label.font"), component.getFont());
            Color foreground = requireNonNullElse(
                    UIManager.getColor("Label.disabledForeground"),
                    component.getForeground()
            );
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setFont(font);
                copy.setColor(foreground);
                FontMetrics metrics = copy.getFontMetrics();
                int x = bounds.x + Math.max(0, (bounds.width - metrics.stringWidth(emptyStateText)) / 2);
                int y = bounds.y + Math.max(metrics.getAscent(), (bounds.height - metrics.getHeight()) / 2 + metrics.getAscent());
                copy.clip(bounds);
                copy.drawString(emptyStateText, x, y);
            } finally {
                copy.dispose();
            }
        }
    }
}
