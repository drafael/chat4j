package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.util.Fonts;

import javax.swing.*;
import java.awt.*;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;

public class ModelSelectorButton extends JButton {

    private static final Map<String, String> PROVIDER_ICON_PATHS = Map.ofEntries(
            Map.entry("Anthropic", "/icons/providers/anthropic.svg"),
            Map.entry("OpenAI", "/icons/providers/openai.svg"),
            Map.entry("Perplexity", "/icons/providers/perplexity.svg"),
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
    private static final int PROVIDER_ICON_GAP = 5;
    private static final int MIN_BUTTON_WIDTH = 320;
    private static final int MAX_BUTTON_WIDTH = 760;
    private static final int HORIZONTAL_CONTENT_PADDING = 32;
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
        this.providerName = providerName != null ? providerName : "";
        this.modelName = modelName != null ? modelName : "";
        setToolTipText("%s\n%s".formatted(this.modelName, this.providerName));
        revalidate();
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
        Font primaryFont = modelNameFont();
        Font secondaryFont = providerNameFont();
        FontMetrics fmPrimary = getFontMetrics(primaryFont);
        FontMetrics fmSecondary = getFontMetrics(secondaryFont);

        int providerIconSize = providerIconSize(fmSecondary);
        Icon providerIcon = providerIcon(providerIconSize);

        int secondaryWidth = fmSecondary.stringWidth(providerName);
        if (providerIcon != null) {
            secondaryWidth += providerIcon.getIconWidth() + PROVIDER_ICON_GAP;
        }

        int textW = Math.max(fmPrimary.stringWidth(modelName), secondaryWidth) + HORIZONTAL_CONTENT_PADDING;
        int w = Math.max(textW, MIN_BUTTON_WIDTH);
        w = Math.min(w, maxAllowedWidth());
        int h = fmPrimary.getHeight() + fmSecondary.getHeight() + 8;
        return new Dimension(w, h);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font primaryFont = modelNameFont();
        Font secondaryFont = providerNameFont();
        FontMetrics fmPrimary = g2.getFontMetrics(primaryFont);
        FontMetrics fmSecondary = g2.getFontMetrics(secondaryFont);

        int totalHeight = fmPrimary.getHeight() + fmSecondary.getHeight() + 2;
        int y = (getHeight() - totalHeight) / 2;

        int contentWidth = Math.max(1, getWidth() - HORIZONTAL_CONTENT_PADDING);

        // Model name (primary)
        g2.setFont(primaryFont);
        g2.setColor(getForeground());
        int chevronWidth = fmSecondary.stringWidth("\u25BE");
        String modelText = clipText(modelName, fmPrimary, Math.max(1, contentWidth - chevronWidth - 8));
        int primaryX = (getWidth() - (fmPrimary.stringWidth(modelText) + chevronWidth + 6)) / 2;
        g2.drawString(modelText, primaryX, y + fmPrimary.getAscent());

        // Chevron after model name
        int chevronX = primaryX + fmPrimary.stringWidth(modelText) + 6;
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

        int providerIconSize = providerIconSize(fmSecondary);
        Icon providerIcon = providerIcon(providerIconSize);
        int providerTextMaxWidth = contentWidth;
        if (providerIcon != null) {
            providerTextMaxWidth -= providerIcon.getIconWidth() + PROVIDER_ICON_GAP;
        }

        String providerText = clipText(providerName, fmSecondary, Math.max(1, providerTextMaxWidth));
        int providerTextWidth = fmSecondary.stringWidth(providerText);
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

        g2.drawString(providerText, secondaryX, secondaryBaseline);

        g2.dispose();
    }

    private int maxAllowedWidth() {
        int max = MAX_BUTTON_WIDTH;
        if (getParent() != null && getParent().getWidth() > 0) {
            max = Math.min(max, Math.max(MIN_BUTTON_WIDTH, getParent().getWidth() - 12));
        } else {
            Window window = SwingUtilities.getWindowAncestor(this);
            if (window != null && window.getWidth() > 0) {
                max = Math.min(max, Math.max(MIN_BUTTON_WIDTH, window.getWidth() - 280));
            }
        }

        return Math.max(MIN_BUTTON_WIDTH, max);
    }

    private static String clipText(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || text.isEmpty() || metrics.stringWidth(text) <= maxWidth) {
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
            int width = metrics.stringWidth(text.substring(0, mid)) + ellipsisWidth;
            if (width <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }

        return text.substring(0, low) + ellipsis;
    }

    private Font modelNameFont() {
        Font mono = UIManager.getFont("monospaced.font");
        int size = Fonts.scale(Fonts.SIZE_BODY);
        if (mono == null) {
            return new Font(Font.MONOSPACED, Font.BOLD, size);
        }

        return new Font(mono.getFamily(), Font.BOLD, size);
    }

    private Font providerNameFont() {
        return Fonts.of(Font.PLAIN, Fonts.SIZE_COMPACT);
    }

    private static int providerIconSize(FontMetrics metrics) {
        return Math.max(10, metrics.getHeight() - 1);
    }

    private Icon providerIcon(int size) {
        String path = PROVIDER_ICON_PATHS.get(providerName);
        if (StringUtils.isBlank(path)) {
            return null;
        }

        String key = "%s#%d".formatted(path, size);
        return PROVIDER_ICON_CACHE.computeIfAbsent(key, iconPathWithSize -> {
            URL url = ModelSelectorButton.class.getResource(path);
            if (url == null) {
                return null;
            }

            FlatSVGIcon icon = new FlatSVGIcon(url).derive(size, size);
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
