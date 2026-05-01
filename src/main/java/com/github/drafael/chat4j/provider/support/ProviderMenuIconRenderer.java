package com.github.drafael.chat4j.provider.support;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ProviderMenuIconRenderer {

    private static final String SCREEN_MENU_BAR_PROPERTY = "apple.laf.useScreenMenuBar";

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

    private static final Map<ProviderMenuIconKey, Icon> PROVIDER_ICON_CACHE = new ConcurrentHashMap<>();

    private ProviderMenuIconRenderer() {
    }

    public static Icon resolve(String providerName, int size, Color tintColor, Class<?> resourceAnchor) {
        Validate.notNull(resourceAnchor, "resourceAnchor must not be null");
        Validate.isTrue(size > 0, "size must be positive, got: %d", size);

        String iconPath = PROVIDER_ICON_PATHS.get(providerName);
        if (StringUtils.isBlank(iconPath)) {
            return null;
        }

        Color effectiveTint = opaqueColor(tintColor, new Color(60, 60, 60));
        boolean screenMenuBarEnabled = Boolean.parseBoolean(System.getProperty(SCREEN_MENU_BAR_PROPERTY, "false"));
        ProviderMenuIconKey cacheKey = new ProviderMenuIconKey(
                iconPath,
                size,
                effectiveTint.getRGB(),
                screenMenuBarEnabled
        );
        return PROVIDER_ICON_CACHE.computeIfAbsent(cacheKey, key -> {
            URL url = resourceAnchor.getResource(key.path());
            if (url == null) {
                return null;
            }

            Color iconColor = new Color(key.fallbackRgb(), true);
            if (key.screenMenuBarEnabled()) {
                return multiResolutionIcon(url, key.size(), iconColor);
            }

            FlatSVGIcon icon = svgIcon(url, key.size(), iconColor);
            return icon != null && icon.hasFound() ? icon : null;
        });
    }

    public static Color opaqueColor(Color candidate, Color fallback) {
        return candidate == null
                ? fallback
                : new Color(candidate.getRed(), candidate.getGreen(), candidate.getBlue());
    }

    public static void clearCache() {
        PROVIDER_ICON_CACHE.clear();
    }

    private static Icon multiResolutionIcon(URL url, int size, Color iconColor) {
        BufferedImage oneX = renderImage(url, size, iconColor);
        if (oneX == null) {
            return null;
        }

        BufferedImage twoX = renderImage(url, size * 2, iconColor);
        if (twoX == null) {
            return new ImageIcon(oneX);
        }

        Image image = new BaseMultiResolutionImage(oneX, twoX);
        return new ImageIcon(image);
    }

    private static BufferedImage renderImage(URL url, int size, Color iconColor) {
        FlatSVGIcon icon = svgIcon(url, size, iconColor);
        if (icon == null || !icon.hasFound()) {
            return null;
        }

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }

        return image;
    }

    private static FlatSVGIcon svgIcon(URL url, int size, Color iconColor) {
        FlatSVGIcon icon = new FlatSVGIcon(url).derive(size, size);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> new Color(
                iconColor.getRed(),
                iconColor.getGreen(),
                iconColor.getBlue(),
                color.getAlpha())
        ));
        return icon;
    }

    private record ProviderMenuIconKey(String path, int size, int fallbackRgb, boolean screenMenuBarEnabled) {
    }
}
