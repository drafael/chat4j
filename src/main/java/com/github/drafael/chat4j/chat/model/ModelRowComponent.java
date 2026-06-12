package com.github.drafael.chat4j.chat.model;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class ModelRowComponent {

    interface Listener {
        void onSelect(String providerName, String modelId);

        void onToggleFavorite(String providerName, String modelId);

        void onMouseEnter(String providerName, String modelId);
    }

    private static final int CHECK_COLUMN_WIDTH = 18;
    private static final Color STAR_ON_COLOR = new Color(255, 140, 0);
    private static final Color STAR_OFF_COLOR = new Color(140, 140, 140);
    private static final int ROW_MAX_HEIGHT = 40;
    private static final Dimension FAVORITE_LABEL_SIZE = new Dimension(26, 24);
    private static final Dimension CHECK_LABEL_SIZE = new Dimension(CHECK_COLUMN_WIDTH, 24);
    private static final Dimension CAPABILITY_LABEL_SIZE = new Dimension(24, 24);
    private static final String STAR_OUTLINE_PATH = "/icons/sidebar/star.svg";
    private static final String STAR_FILLED_PATH = "/icons/sidebar/star-filled.svg";
    private static final String IMAGE_CAPABILITY_PATH = "/icons/sidebar/eye.svg";
    private static final String REASONING_CAPABILITY_PATH = "/icons/sidebar/brain.svg";
    private static final String WEB_CAPABILITY_PATH = "/icons/input/globe.svg";
    private static final Color IMAGE_CAPABILITY_COLOR = new Color(44, 123, 255);
    private static final Color REASONING_CAPABILITY_COLOR = new Color(155, 81, 255);
    private static final Color WEB_CAPABILITY_COLOR = new Color(16, 185, 129);
    private static final Map<String, Icon> FAVORITE_ICON_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Icon> CAPABILITY_ICON_CACHE = new ConcurrentHashMap<>();

    private final JPanel panel;
    private final JLabel nameLabel;
    private final JLabel checkLabel;
    private final JLabel favoriteLabel;
    private final JLabel imageCapabilityLabel;
    private final JLabel reasoningCapabilityLabel;
    private final JLabel webCapabilityLabel;
    private final String providerName;
    private final String modelId;
    private final String displayLabel;
    private final boolean selectable;
    private int lastNameLabelWidth = -1;
    private String lastRenderedNameText;

    ModelRowComponent(
            String providerName,
            String modelId,
            String displayLabel,
            boolean selectable,
            boolean initiallyFavorite,
            boolean supportsImageInput,
            boolean supportsReasoning,
            boolean supportsNativeWebSearch,
            Listener listener
    ) {
        this.providerName = providerName;
        this.modelId = modelId;
        this.displayLabel = displayLabel;
        this.selectable = selectable;

        this.panel = buildPanel();
        this.nameLabel = buildNameLabel(displayLabel);
        this.checkLabel = buildCheckLabel();
        this.favoriteLabel = buildFavoriteLabel();
        this.imageCapabilityLabel = buildCapabilityLabel();
        this.reasoningCapabilityLabel = buildCapabilityLabel();
        this.webCapabilityLabel = buildCapabilityLabel();

        updateFavoriteState(initiallyFavorite);
        updateCapabilities(supportsImageInput, supportsReasoning, supportsNativeWebSearch);
        wireFavoriteLabel(listener);
        applyDisabledState();
        wireRow(listener);
        assemble();
        updateModelLabelText();
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
        panel.repaint();
    }

    void updateFavoriteState(boolean favorite) {
        Color offColor = colorOrDefault(UIManager.getColor("Label.foreground"), STAR_OFF_COLOR);
        Color tint = favorite ? STAR_ON_COLOR : offColor;
        int iconSize = Math.max(12, Fonts.scale(Fonts.SIZE_COMPACT));
        String iconPath = favorite ? STAR_FILLED_PATH : STAR_OUTLINE_PATH;

        favoriteLabel.setIcon(loadTintedIcon(FAVORITE_ICON_CACHE, iconPath, tint, iconSize));
        favoriteLabel.setText(null);
        favoriteLabel.setForeground(tint);
        favoriteLabel.setToolTipText(favorite ? "Remove from favorites" : "Add to favorites");
    }

    void updateCapabilities(boolean supportsImageInput, boolean supportsReasoning, boolean supportsNativeWebSearch) {
        int eyeSize = Math.max(16, Fonts.scale(Fonts.SIZE_BODY_LARGE) + 2);
        int brainSize = Math.max(14, Fonts.scale(Fonts.SIZE_BODY_LARGE));
        int webSize = Math.max(14, Fonts.scale(Fonts.SIZE_BODY_LARGE));

        imageCapabilityLabel.setIcon(loadTintedIcon(
                CAPABILITY_ICON_CACHE,
                IMAGE_CAPABILITY_PATH,
                IMAGE_CAPABILITY_COLOR,
                eyeSize));
        imageCapabilityLabel.setVisible(supportsImageInput);
        imageCapabilityLabel.setToolTipText(supportsImageInput ? "Supports Image Input Natively" : null);

        reasoningCapabilityLabel.setIcon(loadTintedIcon(
                CAPABILITY_ICON_CACHE,
                REASONING_CAPABILITY_PATH,
                REASONING_CAPABILITY_COLOR,
                brainSize));
        reasoningCapabilityLabel.setVisible(supportsReasoning);
        reasoningCapabilityLabel.setToolTipText(supportsReasoning ? "Supports Reasoning" : null);

        webCapabilityLabel.setIcon(loadTintedIcon(
                CAPABILITY_ICON_CACHE,
                WEB_CAPABILITY_PATH,
                WEB_CAPABILITY_COLOR,
                webSize));
        webCapabilityLabel.setVisible(supportsNativeWebSearch);
        webCapabilityLabel.setToolTipText(supportsNativeWebSearch ? "Supports Native Web Search" : null);
    }

    private JPanel buildPanel() {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_MAX_HEIGHT));
        row.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        row.setCursor(selectable
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());
        row.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateModelLabelText();
            }
        });
        return row;
    }

    private JLabel buildNameLabel(String displayLabel) {
        JLabel label = new JLabel(displayLabel);
        Font mono = UIManager.getFont("monospaced.font");
        int size = Fonts.scale(Fonts.SIZE_BODY);
        if (mono != null) {
            label.setFont(new Font(mono.getFamily(), Font.PLAIN, size));
        } else {
            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, size));
        }
        label.setHorizontalAlignment(SwingConstants.LEFT);
        return label;
    }

    private JLabel buildCheckLabel() {
        JLabel label = new JLabel("\u2713", SwingConstants.CENTER);
        label.setForeground(new Color(76, 175, 80));
        Fonts.apply(label, Font.BOLD, Fonts.SIZE_BODY_LARGE);
        label.setPreferredSize(CHECK_LABEL_SIZE);
        label.setMinimumSize(CHECK_LABEL_SIZE);
        label.setMaximumSize(CHECK_LABEL_SIZE);
        label.setVisible(false);
        return label;
    }

    private JLabel buildFavoriteLabel() {
        JLabel label = new JLabel();
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setVerticalAlignment(SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        label.setPreferredSize(FAVORITE_LABEL_SIZE);
        label.setMinimumSize(FAVORITE_LABEL_SIZE);
        label.setMaximumSize(FAVORITE_LABEL_SIZE);
        return label;
    }

    private void wireFavoriteLabel(Listener listener) {
        if (!selectable) {
            favoriteLabel.setEnabled(false);
            favoriteLabel.setToolTipText("%s server is unavailable".formatted(providerName));
            return;
        }

        favoriteLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
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
        panel.setToolTipText("%s server is unavailable".formatted(providerName));
    }

    private JLabel buildCapabilityLabel() {
        JLabel label = new JLabel();
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setVerticalAlignment(SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        label.setPreferredSize(CAPABILITY_LABEL_SIZE);
        label.setMinimumSize(CAPABILITY_LABEL_SIZE);
        label.setMaximumSize(CAPABILITY_LABEL_SIZE);
        label.setVisible(false);
        return label;
    }

    private void wireRow(Listener listener) {
        MouseAdapter rowMouseListener = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (!selectable) {
                    return;
                }

                listener.onMouseEnter(providerName, modelId);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (!selectable) {
                    return;
                }

                listener.onSelect(providerName, modelId);
            }
        };

        panel.addMouseListener(rowMouseListener);
        nameLabel.addMouseListener(rowMouseListener);
        checkLabel.addMouseListener(rowMouseListener);
        imageCapabilityLabel.addMouseListener(rowMouseListener);
        reasoningCapabilityLabel.addMouseListener(rowMouseListener);
        webCapabilityLabel.addMouseListener(rowMouseListener);
    }

    private void assemble() {
        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
        actions.add(checkLabel);
        actions.add(favoriteLabel);
        actions.add(imageCapabilityLabel);
        actions.add(reasoningCapabilityLabel);
        actions.add(webCapabilityLabel);

        panel.add(nameLabel, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.EAST);
    }

    private void updateModelLabelText() {
        int availableWidth = nameLabel.getWidth();
        if (availableWidth <= 0) {
            if (!Objects.equals(nameLabel.getText(), displayLabel)) {
                nameLabel.setText(displayLabel);
            }
            lastNameLabelWidth = availableWidth;
            lastRenderedNameText = displayLabel;
            return;
        }

        if (availableWidth == lastNameLabelWidth && Objects.equals(lastRenderedNameText, nameLabel.getText())) {
            return;
        }

        String clipped = clipTextToWidth(displayLabel, nameLabel.getFontMetrics(nameLabel.getFont()), availableWidth);
        if (!Objects.equals(nameLabel.getText(), clipped)) {
            nameLabel.setText(clipped);
        }
        nameLabel.setToolTipText(clipped.equals(displayLabel) ? null : displayLabel);
        lastNameLabelWidth = availableWidth;
        lastRenderedNameText = clipped;
    }

    private static String clipTextToWidth(String text, FontMetrics metrics, int maxWidth) {
        if (StringUtils.isEmpty(text)) {
            return "";
        }

        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }

        String ellipsis = "…";
        int ellipsisWidth = metrics.stringWidth(ellipsis);
        if (ellipsisWidth >= maxWidth) {
            return ellipsis;
        }

        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            String candidate = text.substring(0, mid);
            int width = metrics.stringWidth(candidate) + ellipsisWidth;
            if (width <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }

        return text.substring(0, low) + ellipsis;
    }

    private static Icon loadTintedIcon(Map<String, Icon> cache, String iconPath, Color tint, int size) {
        int rgb = tint != null ? tint.getRGB() : STAR_OFF_COLOR.getRGB();
        String cacheKey = "%s#%d#%d".formatted(iconPath, size, rgb);
        return cache.computeIfAbsent(cacheKey, key -> {
            URL url = ModelRowComponent.class.getResource(iconPath);
            if (url == null) {
                return null;
            }

            FlatSVGIcon icon = new FlatSVGIcon(url).derive(size, size);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
                Color effectiveTint = tint != null ? tint : STAR_OFF_COLOR;
                return new Color(
                        effectiveTint.getRed(),
                        effectiveTint.getGreen(),
                        effectiveTint.getBlue(),
                        color.getAlpha());
            }));
            return icon.hasFound() ? icon : null;
        });
    }

    private static Color colorOrDefault(Color candidate, Color fallback) {
        return candidate != null ? candidate : fallback;
    }
}
