package com.github.drafael.chat4j.chat.ui;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.List;

final class WrappingChipsPanel extends JPanel {
    private final int maxWrapWidth;

    WrappingChipsPanel(int maxWrapWidth, int gap) {
        this.maxWrapWidth = maxWrapWidth;
        setOpaque(false);
        setLayout(new WrappingChipsLayout(maxWrapWidth, gap));
        setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    @Override
    public Dimension getPreferredSize() {
        return ((WrappingChipsLayout) getLayout()).preferredLayoutSize(this);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(maxWrapWidth, preferred.height);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        int clampedWidth = Math.min(maxWrapWidth, Math.max(1, width));
        super.setBounds(x + Math.max(0, (width - clampedWidth) / 2), y, clampedWidth, height);
    }

    private static final class WrappingChipsLayout implements LayoutManager {
        private final int maxWrapWidth;
        private final int gap;

        private WrappingChipsLayout(int maxWrapWidth, int gap) {
            this.maxWrapWidth = maxWrapWidth;
            this.gap = gap;
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            synchronized (parent.getTreeLock()) {
                return layoutSize(parent, maxWrapWidth, false);
            }
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            synchronized (parent.getTreeLock()) {
                int wrapWidth = Math.max(1, Math.min(maxWrapWidth, parent.getWidth()));
                layoutSize(parent, wrapWidth, true);
            }
        }

        private Dimension layoutSize(Container parent, int wrapWidth, boolean applyBounds) {
            Insets insets = parent.getInsets();
            int availableWidth = Math.max(1, wrapWidth - insets.left - insets.right);
            List<Component> row = new ArrayList<>();
            int rowWidth = 0;
            int rowHeight = 0;
            int y = insets.top + gap;
            int widestRow = 0;

            for (Component component : parent.getComponents()) {
                if (!component.isVisible()) {
                    continue;
                }

                Dimension size = component.getPreferredSize();
                int nextWidth = row.isEmpty() ? size.width : rowWidth + gap + size.width;
                if (!row.isEmpty() && nextWidth > availableWidth) {
                    layoutRow(parent, row, rowWidth, rowHeight, y, applyBounds);
                    widestRow = Math.max(widestRow, rowWidth);
                    y += rowHeight + gap;
                    row.clear();
                    rowWidth = 0;
                    rowHeight = 0;
                }

                if (!row.isEmpty()) {
                    rowWidth += gap;
                }
                row.add(component);
                rowWidth += size.width;
                rowHeight = Math.max(rowHeight, size.height);
            }

            if (!row.isEmpty()) {
                layoutRow(parent, row, rowWidth, rowHeight, y, applyBounds);
                widestRow = Math.max(widestRow, rowWidth);
                y += rowHeight + gap;
            }

            int width = Math.min(maxWrapWidth, Math.max(widestRow + insets.left + insets.right, availableWidth));
            return new Dimension(width, y + insets.bottom);
        }

        private void layoutRow(Container parent, List<Component> row, int rowWidth, int rowHeight, int y, boolean applyBounds) {
            if (!applyBounds) {
                return;
            }

            int x = Math.max(0, (parent.getWidth() - rowWidth) / 2);
            for (Component component : row) {
                Dimension size = component.getPreferredSize();
                component.setBounds(x, y + (rowHeight - size.height) / 2, size.width, size.height);
                x += size.width + gap;
            }
        }
    }
}
