package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.chat.render.RenderMode;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

public final class SettingsKeys {

    public static final String THEME_NAME = "chat4j.ui.theme.name";
    public static final String THEME_ACCENT = "chat4j.ui.theme.accent";

    public static final String APP_FONT_FAMILY = "chat4j.ui.font.app.family";
    public static final String APP_FONT_SIZE = "chat4j.ui.font.app.size";
    public static final String CODE_FONT_FAMILY = "chat4j.ui.font.code.family";

    public static final String WINDOW_X = "chat4j.ui.window.x";
    public static final String WINDOW_Y = "chat4j.ui.window.y";
    public static final String WINDOW_WIDTH = "chat4j.ui.window.width";
    public static final String WINDOW_HEIGHT = "chat4j.ui.window.height";
    public static final String MENU_BAR_ENABLED = "chat4j.ui.menuBar.enabled";

    public static final String CHAT_SEND_KEY = "chat4j.chat.input.sendKey";
    public static final String CHAT_AUTO_SCROLL = "chat4j.chat.behavior.autoScroll";
    public static final String CHAT_AGENT_SYSTEM_PROMPT_APPEND = "chat4j.chat.agent.systemPromptAppend";
    public static final String CHAT_RENDER_MODE = "chat4j.chat.render.mode";
    public static final String CHAT_RENDER_MODE_MARKDOWN = RenderMode.MARKDOWN.settingValue();
    public static final String CHAT_STORAGE_BACKEND_ACTIVE = "chat.storage.backend.active";
    public static final String CHAT_STORAGE_BACKEND_PENDING = "chat.storage.backend.pending";
    public static final String WEBVIEW_ENGINE = "chat4j.chat.webView.engine";

    public static final String PROVIDER_PREFIX = "chat4j.provider.";
    public static final String PROVIDER_ENABLED_SUFFIX = ".enabled";
    public static final String PROVIDER_BASE_URL_SUFFIX = ".baseUrl";

    public static final String MODEL_FAVORITE_PREFIX = "chat4j.models.favorite.";
    public static final String MODEL_FAVORITE_DELIMITER = "::";

    public static final String PROMPT_CATALOG = "chat4j.prompts.catalog";

    public static final String WEB_AUTO_BROWSE_TOP_N = "chat4j.web.autoBrowseTopN";
    public static final String WEB_SEARCH_RESULT_COUNT = "chat4j.web.searchResultCount";

    public static final String SETTINGS_DB_TO_PROPERTIES_MIGRATION_MARKER = "chat4j.migration.settingsDbToProperties";

    private SettingsKeys() {
    }

    public static String providerEnabledKey(String providerName) {
        return "%s%s%s".formatted(PROVIDER_PREFIX, providerSlug(providerName), PROVIDER_ENABLED_SUFFIX);
    }

    public static String providerBaseUrlKey(String providerName) {
        return "%s%s%s".formatted(PROVIDER_PREFIX, providerSlug(providerName), PROVIDER_BASE_URL_SUFFIX);
    }

    public static String modelFavoritePrefixForProvider(String providerName) {
        return "%s%s%s".formatted(MODEL_FAVORITE_PREFIX, providerSlug(providerName), MODEL_FAVORITE_DELIMITER);
    }

    public static String providerSlug(String providerName) {
        String normalized = StringUtils.defaultString(providerName).trim().toLowerCase(Locale.ROOT);
        String slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return StringUtils.defaultIfBlank(slug, "unknown");
    }
}
