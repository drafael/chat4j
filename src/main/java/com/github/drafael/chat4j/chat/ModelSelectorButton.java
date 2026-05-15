package com.github.drafael.chat4j.chat;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import java.awt.*;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int PROVIDER_ICON_SIZE = 14;
    private static final int PROVIDER_ICON_GAP = 6;
    private static final int PROVIDER_MODEL_GAP = 10;
    private static final int PROVIDER_ICON_BASELINE_OFFSET = 1;
    private static final int MIN_BUTTON_WIDTH = 180;
    private static final int FALLBACK_MAX_BUTTON_WIDTH = 720;
    private static final double MAX_TITLE_BAR_WIDTH_RATIO = 0.60;
    private static final int HORIZONTAL_CONTENT_PADDING = 24;
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
        setToolTipText("%s %s".formatted(this.providerName, this.modelName).trim());
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

        int chevronWidth = fmSecondary.stringWidth("\u25BE");
        int textW = providerContentWidth(fmSecondary) + modelContentWidth(fmPrimary, chevronWidth)
                + HORIZONTAL_CONTENT_PADDING;
        int w = Math.max(textW, MIN_BUTTON_WIDTH);
        w = Math.min(w, maxAllowedWidth());
        int h = Math.max(fmPrimary.getHeight(), fmSecondary.getHeight()) + 10;
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

        Color secondaryColor = UIManager.getColor("Label.disabledForeground");
        if (secondaryColor == null) {
            secondaryColor = getForeground();
        }

        int contentWidth = Math.max(1, getWidth() - HORIZONTAL_CONTENT_PADDING);
        int chevronWidth = fmSecondary.stringWidth("\u25BE");
        int providerWidth = providerContentWidth(fmSecondary);
        int modelMaxWidth = Math.max(1, contentWidth - providerWidth - chevronWidth - 6);
        String modelText = clipText(modelName, fmPrimary, modelMaxWidth);
        int modelWidth = fmPrimary.stringWidth(modelText);
        int totalWidth = providerWidth + modelWidth + chevronWidth + 6;
        int x = Math.max(HORIZONTAL_CONTENT_PADDING / 2, (getWidth() - totalWidth) / 2);
        int primaryBaseline = (getHeight() - fmPrimary.getHeight()) / 2 + fmPrimary.getAscent();
        int secondaryBaseline = primaryBaseline - (fmPrimary.getAscent() - fmSecondary.getAscent()) / 2;

        g2.setFont(secondaryFont);
        g2.setColor(secondaryColor);
        x = paintProvider(g2, fmSecondary, x, secondaryBaseline);

        g2.setFont(primaryFont);
        g2.setColor(getForeground());
        g2.drawString(modelText, x, primaryBaseline);
        x += modelWidth + 6;

        g2.setFont(secondaryFont);
        g2.setColor(secondaryColor);
        g2.drawString("\u25BE", x, secondaryBaseline - 1);

        g2.dispose();
    }

    private int providerContentWidth(FontMetrics metrics) {
        if (StringUtils.isBlank(providerName)) {
            return 0;
        }

        int width = metrics.stringWidth(providerName) + PROVIDER_MODEL_GAP;
        Icon providerIcon = providerIcon(providerIconSize(metrics));
        if (providerIcon != null) {
            width += providerIcon.getIconWidth() + PROVIDER_ICON_GAP;
        }
        return width;
    }

    private int modelContentWidth(FontMetrics metrics, int chevronWidth) {
        return metrics.stringWidth(modelName) + chevronWidth + 6;
    }

    private int paintProvider(Graphics2D g2, FontMetrics metrics, int x, int baseline) {
        if (StringUtils.isBlank(providerName)) {
            return x;
        }

        Icon providerIcon = providerIcon(providerIconSize(metrics));
        if (providerIcon != null) {
            int iconY = baseline - metrics.getAscent()
                    + (metrics.getHeight() - providerIcon.getIconHeight()) / 2
                    + PROVIDER_ICON_BASELINE_OFFSET;
            providerIcon.paintIcon(this, g2, x, iconY);
            x += providerIcon.getIconWidth() + PROVIDER_ICON_GAP;
        }

        g2.drawString(providerName, x, baseline);
        return x + metrics.stringWidth(providerName) + PROVIDER_MODEL_GAP;
    }

    private int maxAllowedWidth() {
        int max = FALLBACK_MAX_BUTTON_WIDTH;
        if (getParent() != null && getParent().getWidth() > 0) {
            max = Math.max(MIN_BUTTON_WIDTH, getParent().getWidth() - 12);
        }

        int titleBarWidth = titleBarWidth();
        if (titleBarWidth > 0) {
            int titleBarMax = (int) Math.floor(titleBarWidth * MAX_TITLE_BAR_WIDTH_RATIO);
            max = Math.min(max, Math.max(MIN_BUTTON_WIDTH, titleBarMax));
        }

        return Math.max(MIN_BUTTON_WIDTH, max);
    }

    private int titleBarWidth() {
        Container parent = getParent();
        if (parent != null && parent.getParent() != null && parent.getParent().getWidth() > 0) {
            return parent.getParent().getWidth();
        }

        Window window = SwingUtilities.getWindowAncestor(this);
        return window != null ? window.getWidth() : 0;
    }

    private static String clipText(String text, FontMetrics metrics, int maxWidth) {
        if (StringUtils.isEmpty(text) || metrics.stringWidth(text) <= maxWidth) {
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
        return Fonts.scale(PROVIDER_ICON_SIZE);
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
