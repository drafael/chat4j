package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.util.Fonts;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

final class ModelRowComponent {

    interface Listener {
        void onSelect(String providerName, String modelId);

        void onToggleFavorite(String providerName, String modelId);

        void onMouseEnter(String providerName, String modelId);
    }

    private static final int FAVORITE_HOTSPOT_WIDTH = 220;
    private static final Color STAR_ON_COLOR = new Color(255, 140, 0);
    private static final Color STAR_OFF_COLOR = new Color(140, 140, 140);
    private static final int ROW_MAX_HEIGHT = 40;
    private static final Dimension FAVORITE_LABEL_SIZE = new Dimension(150, 28);

    private final JPanel panel;
    private final JLabel nameLabel;
    private final JLabel checkLabel;
    private final JLabel favoriteLabel;
    private final String providerName;
    private final String modelId;
    private final boolean selectable;

    ModelRowComponent(
            String providerName,
            String modelId,
            String displayLabel,
            boolean selectable,
            boolean initiallyFavorite,
            Listener listener
    ) {
        this.providerName = providerName;
        this.modelId = modelId;
        this.selectable = selectable;

        this.panel = buildPanel();
        this.nameLabel = buildNameLabel(displayLabel);
        this.checkLabel = buildCheckLabel();
        this.favoriteLabel = buildFavoriteLabel();

        updateFavoriteState(initiallyFavorite);
        wireFavoriteLabel(listener);
        applyDisabledState();
        wireRow(listener);
        assemble();
    }

    JPanel panel() {
        return panel;
    }

    String providerName() {
        return providerName;
    }

    String modelId() {
        return modelId;
    }

    void setSelected(boolean selected) {
        checkLabel.setVisible(selected);
    }

    void setHighlighted(boolean highlighted) {
        if (!selectable) {
            return;
        }

        if (highlighted) {
            panel.setBackground(colorOrDefault(UIManager.getColor("List.selectionBackground"), new Color(220, 220, 220)));
            panel.setOpaque(true);
            nameLabel.setForeground(colorOrDefault(UIManager.getColor("List.selectionForeground"), Color.BLACK));
        } else {
            panel.setOpaque(false);
            panel.setBackground(panel.getParent() != null ? panel.getParent().getBackground() : Color.WHITE);
            nameLabel.setForeground(colorOrDefault(UIManager.getColor("Label.foreground"), Color.BLACK));
        }
    }

    void updateFavoriteState(boolean favorite) {
        favoriteLabel.setText(favorite ? "★ Fav" : "☆ Fav");
        Color offColor = colorOrDefault(UIManager.getColor("Label.foreground"), STAR_OFF_COLOR);
        favoriteLabel.setForeground(favorite ? STAR_ON_COLOR : offColor);
        favoriteLabel.setToolTipText(favorite ? "Remove from favorites" : "Add to favorites");
    }

    private JPanel buildPanel() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_MAX_HEIGHT));
        row.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        row.setCursor(selectable
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        return row;
    }

    private JLabel buildNameLabel(String displayLabel) {
        JLabel label = new JLabel(displayLabel);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 13f));
        return label;
    }

    private JLabel buildCheckLabel() {
        JLabel label = new JLabel("\u2713");
        label.setForeground(new Color(76, 175, 80));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        label.setVisible(false);
        return label;
    }

    private JLabel buildFavoriteLabel() {
        JLabel label = new JLabel();
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        label.setPreferredSize(FAVORITE_LABEL_SIZE);
        label.setMinimumSize(FAVORITE_LABEL_SIZE);
        label.setMaximumSize(FAVORITE_LABEL_SIZE);
        label.setFont(Fonts.of(Font.PLAIN, 12));
        return label;
    }

    private void wireFavoriteLabel(Listener listener) {
        if (!selectable) {
            favoriteLabel.setEnabled(false);
            favoriteLabel.setToolTipText(providerName + " server is unavailable");
            return;
        }

        favoriteLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                e.consume();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                e.consume();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                e.consume();
                listener.onToggleFavorite(providerName, modelId);
            }
        });
    }

    private void applyDisabledState() {
        if (selectable) {
            return;
        }

        nameLabel.setForeground(
                colorOrDefault(UIManager.getColor("Label.disabledForeground"), new Color(140, 140, 140)));
        panel.setToolTipText(providerName + " server is unavailable");
    }

    private void wireRow(Listener listener) {
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!selectable) {
                    return;
                }

                listener.onMouseEnter(providerName, modelId);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!selectable) {
                    return;
                }

                if (isFavoriteHotspotClick(panel, e.getPoint())
                        || isClickInside(panel, favoriteLabel, e.getPoint())) {
                    listener.onToggleFavorite(providerName, modelId);
                    return;
                }

                listener.onSelect(providerName, modelId);
            }
        });
    }

    private void assemble() {
        panel.add(nameLabel);
        panel.add(Box.createHorizontalGlue());
        panel.add(checkLabel);
        panel.add(favoriteLabel);
    }

    private static boolean isClickInside(Component container, Component target, Point containerPoint) {
        Point targetPoint = SwingUtilities.convertPoint(container, containerPoint, target);
        return target.contains(targetPoint);
    }

    private static boolean isFavoriteHotspotClick(Component container, Point containerPoint) {
        int hotspotStartX = Math.max(0, container.getWidth() - FAVORITE_HOTSPOT_WIDTH);
        return containerPoint.x >= hotspotStartX;
    }

    private static Color colorOrDefault(Color candidate, Color fallback) {
        return candidate != null ? candidate : fallback;
    }
}
