package com.github.drafael.chat4j.persistence.settings;

import com.github.drafael.chat4j.chat.render.RenderMode;

public final class SettingsKeys {

    public static final String WINDOW_X = "chat4j.ui.window.x";
    public static final String WINDOW_Y = "chat4j.ui.window.y";
    public static final String WINDOW_WIDTH = "chat4j.ui.window.width";
    public static final String WINDOW_HEIGHT = "chat4j.ui.window.height";
    public static final String WINDOW_SCREEN_X = "chat4j.ui.window.screen.x";
    public static final String WINDOW_SCREEN_Y = "chat4j.ui.window.screen.y";
    public static final String WINDOW_SCREEN_WIDTH = "chat4j.ui.window.screen.width";
    public static final String WINDOW_SCREEN_HEIGHT = "chat4j.ui.window.screen.height";
    public static final String WINDOW_SCREEN_ID = "chat4j.ui.window.screen.id";
    public static final String MENU_BAR_ENABLED = "chat4j.ui.menuBar.enabled";

    public static final String CHAT_SEND_KEY = "chat4j.chat.input.sendKey";
    public static final String CHAT_AUTO_SCROLL = "chat4j.chat.behavior.autoScroll";
    public static final String CHAT_AGENT_SYSTEM_PROMPT_APPEND = "chat4j.chat.agent.systemPromptAppend";
    public static final String CHAT_RENDER_MODE = "chat4j.chat.render.mode";
    public static final String CHAT_RENDER_MODE_MARKDOWN = RenderMode.MARKDOWN.settingValue();
    public static final String CHAT_STORAGE_BACKEND_ACTIVE = "chat.storage.backend.active";
    public static final String CHAT_STORAGE_BACKEND_PENDING = "chat.storage.backend.pending";
    public static final String MODEL_FAVORITE_PREFIX = "chat4j.models.favorite.";
    public static final String MODEL_FAVORITE_DELIMITER = "::";

    public static final String PROMPT_CATALOG = "chat4j.prompts.catalog";

    public static final String WEB_AUTO_BROWSE_TOP_N = "chat4j.web.autoBrowseTopN";
    public static final String WEB_SEARCH_RESULT_COUNT = "chat4j.web.searchResultCount";

    private SettingsKeys() {
    }

}
