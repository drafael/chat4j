package com.github.drafael.chat4j.chat.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.github.drafael.chat4j.chat.ChatPanel;
import com.github.drafael.chat4j.util.Fonts;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

public final class ChatEmptyStatePanel extends JPanel {
    private static final int ICON_SIZE = 72;
    private static final int CHIP_GAP = 8;
    private static final int CHIPS_MAX_WIDTH = 720;

    private final List<ChatPanel.PromptQuickAction> promptQuickActions;
    private final EmptyStateActions actions;

    public ChatEmptyStatePanel(List<ChatPanel.PromptQuickAction> promptQuickActions, EmptyStateActions actions) {
        this.promptQuickActions = List.copyOf(promptQuickActions);
        this.actions = actions;
        setLayout(new GridBagLayout());
        setOpaque(false);
        add(createContent());
    }

    private JPanel createContent() {
        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 24));

        BufferedImage source = loadLogoSource();
        if (source != null) {
            JLabel logo = new JLabel() {
                @Override
                public void updateUI() {
                    super.updateUI();
                    setIcon(new LogoIcon(tintLogo(source), ICON_SIZE));
                }
            };
            logo.setIcon(new LogoIcon(tintLogo(source), ICON_SIZE));
            logo.setAlignmentX(Component.CENTER_ALIGNMENT);
            content.add(logo);
            content.add(Box.createVerticalStrut(18));
        }

        JLabel title = new JLabel("How can I help?");
        Fonts.apply(title, Font.BOLD, Fonts.SIZE_DISPLAY);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(title);

        JLabel subtitle = new JLabel("Chat, search the web, or run an agent task against a project.");
        Fonts.apply(subtitle, Font.PLAIN, Fonts.SIZE_BODY);
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(Box.createVerticalStrut(8));
        content.add(subtitle);
        content.add(Box.createVerticalStrut(20));

        JPanel suggestions = new WrappingChipsPanel(CHIPS_MAX_WIDTH, CHIP_GAP);
        suggestions.setOpaque(false);
        suggestions.add(createSuggestionChip("Review codebase", actions.enableAgentMode()));
        suggestions.add(createSuggestionChip("Explain selected file", actions.openAttachmentPicker()));
        suggestions.add(createSuggestionChip("Draft commit message", actions.enableAgentMode()));
        suggestions.add(createSuggestionChip("Search the web", actions.enableWebSearch()));
        promptQuickActions.stream()
                .map(this::createPromptActionChip)
                .forEach(suggestions::add);
        content.add(suggestions);

        return content;
    }

    private JButton createSuggestionChip(String text, Runnable action) {
        return createEmptyStateChip(text, true, () -> {
            actions.setInputText().accept(text);
            if (action != null) {
                action.run();
            }
            actions.requestInputFocus().run();
        });
    }

    private JButton createPromptActionChip(ChatPanel.PromptQuickAction promptQuickAction) {
        return createEmptyStateChip(promptQuickAction.title(), true, () -> {
            promptQuickAction.action().run();
            actions.requestInputFocus().run();
        });
    }

    private JButton createEmptyStateChip(String text, boolean primary, Runnable action) {
        JButton chip = new JButton(text);
        chip.putClientProperty("JButton.buttonType", "roundRect");
        chip.putClientProperty(FlatClientProperties.STYLE, "focusWidth:0;innerFocusWidth:0;arc:999");
        chip.setFocusable(false);
        chip.setMargin(primary ? new Insets(6, 12, 6, 12) : new Insets(4, 10, 4, 10));
        Fonts.apply(chip, Font.PLAIN, primary ? Fonts.SIZE_BODY : Fonts.SIZE_SMALL);
        FontMetrics metrics = chip.getFontMetrics(chip.getFont());
        Insets margin = chip.getMargin();
        Dimension size = new Dimension(
                metrics.stringWidth(text) + margin.left + margin.right + 18,
                metrics.getHeight() + margin.top + margin.bottom + 6
        );
        chip.setPreferredSize(size);
        chip.setMinimumSize(size);
        chip.addActionListener(e -> action.run());
        return chip;
    }

    private static BufferedImage loadLogoSource() {
        URL url = ChatEmptyStatePanel.class.getResource("/icons/icon.png");
        if (url == null) {
            return null;
        }
        try {
            return ImageIO.read(url);
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage tintLogo(BufferedImage source) {
        Color base = UIManager.getColor("Label.foreground");
        if (base == null) {
            base = new Color(128, 128, 128);
        }
        int fgRgb = base.getRGB() & 0xFFFFFF;

        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage tinted = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = source.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    continue;
                }
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int luminance = (r * 299 + g * 587 + b * 114) / 1000;
                int newAlpha;
                if (luminance <= 80) {
                    newAlpha = a;
                } else if (luminance >= 180) {
                    newAlpha = 0;
                } else {
                    newAlpha = a * (180 - luminance) / 100;
                }
                if (newAlpha == 0) {
                    continue;
                }
                tinted.setRGB(x, y, (newAlpha << 24) | fgRgb);
            }
        }
        return tinted;
    }

    private static final class LogoIcon implements Icon {
        private final BufferedImage source;
        private final int size;

        LogoIcon(BufferedImage source, int size) {
            this.source = source;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(source, x, y, size, size, null);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }
}
