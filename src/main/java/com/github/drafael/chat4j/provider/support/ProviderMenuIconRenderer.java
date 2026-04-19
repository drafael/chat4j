package com.github.drafael.chat4j.provider.support;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import javax.swing.Icon;
import java.awt.Color;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ProviderMenuIconRenderer {

    private static final int PROVIDER_ICON_ALPHA_MIN = 210;
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
        ProviderMenuIconKey cacheKey = new ProviderMenuIconKey(iconPath, size, effectiveTint.getRGB());
        return PROVIDER_ICON_CACHE.computeIfAbsent(cacheKey, key -> {
            URL url = resourceAnchor.getResource(key.path());
            if (url == null) {
                return null;
            }

            Color iconColor = new Color(key.fallbackRgb(), true);
            FlatSVGIcon icon = new FlatSVGIcon(url).derive(key.size(), key.size());
            icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> {
                int alpha = color.getAlpha() == 0 ? 0 : Math.max(color.getAlpha(), PROVIDER_ICON_ALPHA_MIN);
                return new Color(iconColor.getRed(), iconColor.getGreen(), iconColor.getBlue(), alpha);
            }));
            return icon.hasFound() ? icon : null;
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

    private record ProviderMenuIconKey(String path, int size, int fallbackRgb) {
    }
}
