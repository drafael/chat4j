package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.*;
import java.awt.*;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModelSelectorButton extends JButton {

    private static final Map<String, String> PROVIDER_ICON_PATHS = Map.ofEntries(
            Map.entry("Anthropic", "/icons/providers/anthropic.svg"),
            Map.entry("OpenAI", "/icons/providers/openai.svg"),
            Map.entry("OpenAI Codex", "/icons/providers/codex.svg"),
            Map.entry("GitHub Copilot", "/icons/providers/githubcopilot.svg"),
            Map.entry("Google AI", "/icons/providers/google.svg"),
            Map.entry("OpenRouter", "/icons/providers/openrouter.svg"),
            Map.entry("Groq", "/icons/providers/groq.svg"),
            Map.entry("DeepSeek", "/icons/providers/deepseek.svg"),
            Map.entry("Mistral", "/icons/providers/mistral.svg"),
            Map.entry("xAI", "/icons/providers/xai.svg"),
            Map.entry("LM Studio", "/icons/providers/lmstudio.svg"),
            Map.entry("Ollama", "/icons/providers/ollama.svg")
    );
    private static final int PROVIDER_ICON_SIZE = 12;
    private static final int PROVIDER_ICON_GAP = 5;
    private static final Map<String, Icon> PROVIDER_ICON_CACHE = new ConcurrentHashMap<>();

    private String modelName = "";
    private String providerName = "";

    public ModelSelectorButton() {
        putClientProperty("JButton.buttonType", "borderless");
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFocusable(false);
        setHorizontalAlignment(SwingConstants.CENTER);
    }

    public void setSelection(String providerName, String modelName) {
        this.providerName = providerName;
        this.modelName = modelName;
        repaint();
    }

    public String getModelName() {
        return modelName;
    }

    public String getProviderName() {
        return providerName;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fmPrimary = getFontMetrics(getFont().deriveFont(Font.BOLD, 13f));
        FontMetrics fmSecondary = getFontMetrics(getFont().deriveFont(Font.PLAIN, 11f));

        int secondaryWidth = fmSecondary.stringWidth(providerName);
        if (providerIcon() != null) {
            secondaryWidth += PROVIDER_ICON_SIZE + PROVIDER_ICON_GAP;
        }

        int textW = Math.max(fmPrimary.stringWidth(modelName), secondaryWidth) + 32;
        int w = Math.max(textW, 420);
        int h = fmPrimary.getHeight() + fmSecondary.getHeight() + 8;
        return new Dimension(w, h);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font primaryFont = getFont().deriveFont(Font.BOLD, 13f);
        Font secondaryFont = getFont().deriveFont(Font.PLAIN, 11f);
        FontMetrics fmPrimary = g2.getFontMetrics(primaryFont);
        FontMetrics fmSecondary = g2.getFontMetrics(secondaryFont);

        int totalHeight = fmPrimary.getHeight() + fmSecondary.getHeight() + 2;
        int y = (getHeight() - totalHeight) / 2;

        // Model name (primary)
        g2.setFont(primaryFont);
        g2.setColor(getForeground());
        int primaryX = (getWidth() - fmPrimary.stringWidth(modelName)) / 2;
        g2.drawString(modelName, primaryX, y + fmPrimary.getAscent());

        // Chevron after model name
        int chevronX = primaryX + fmPrimary.stringWidth(modelName) + 6;
        int chevronY = y + fmPrimary.getAscent() - 4;
        g2.setFont(secondaryFont);
        g2.setColor(UIManager.getColor("Label.disabledForeground"));
        g2.drawString("\u25BE", chevronX, chevronY);

        // Provider name with icon (secondary)
        g2.setFont(secondaryFont);
        Color secondaryColor = UIManager.getColor("Label.disabledForeground");
        if (secondaryColor == null) {
            secondaryColor = getForeground();
        }
        g2.setColor(secondaryColor);

        Icon providerIcon = providerIcon();
        int providerTextWidth = fmSecondary.stringWidth(providerName);
        int providerContentWidth = providerTextWidth;
        if (providerIcon != null) {
            providerContentWidth += providerIcon.getIconWidth() + PROVIDER_ICON_GAP;
        }

        int secondaryX = (getWidth() - providerContentWidth) / 2;
        int secondaryBaseline = y + fmPrimary.getHeight() + 2 + fmSecondary.getAscent();
        if (providerIcon != null) {
            int iconY = secondaryBaseline - fmSecondary.getAscent() + (fmSecondary.getHeight() - providerIcon.getIconHeight()) / 2;
            providerIcon.paintIcon(this, g2, secondaryX, iconY);
            secondaryX += providerIcon.getIconWidth() + PROVIDER_ICON_GAP;
        }

        g2.drawString(providerName, secondaryX, secondaryBaseline);

        g2.dispose();
    }

    private Icon providerIcon() {
        String path = PROVIDER_ICON_PATHS.get(providerName);
        if (path == null || path.isBlank()) {
            return null;
        }

        return PROVIDER_ICON_CACHE.computeIfAbsent(path, iconPath -> {
            URL url = ModelSelectorButton.class.getResource(iconPath);
            if (url == null) {
                return null;
            }

            FlatSVGIcon icon = new FlatSVGIcon(url).derive(PROVIDER_ICON_SIZE, PROVIDER_ICON_SIZE);
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
                Color secondaryColor = UIManager.getColor("Label.disabledForeground");
                if (secondaryColor == null) {
                    secondaryColor = component != null ? component.getForeground() : null;
                }
                if (secondaryColor == null) {
                    secondaryColor = new Color(120, 120, 120);
                }
                return new Color(
                        secondaryColor.getRed(),
                        secondaryColor.getGreen(),
                        secondaryColor.getBlue(),
                        color.getAlpha());
            }));
            return icon.hasFound() ? icon : null;
        });
    }
}
