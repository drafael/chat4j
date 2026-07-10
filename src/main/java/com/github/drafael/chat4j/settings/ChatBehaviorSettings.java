package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;

public final class ChatBehaviorSettings {

    public static final String SEND_ENTER = "Enter";
    public static final String SEND_CTRL_ENTER = "Ctrl+Enter";

    private static final String KEY_SEND = "chat4j.chat.input.sendKey";
    private static final String KEY_AUTO_SCROLL = "chat4j.chat.behavior.autoScroll";
    private static final String KEY_MENU_BAR_ENABLED = "chat4j.ui.menuBar.enabled";

    private final SettingsRepository settingsRepo;

    public ChatBehaviorSettings(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public String sendKey() {
        String value = settingsRepo.get(KEY_SEND, SEND_ENTER);
        return value != null ? value : SEND_ENTER;
    }

    public boolean sendOnEnter() {
        return !SEND_CTRL_ENTER.equalsIgnoreCase(sendKey());
    }

    public boolean autoScrollEnabled() {
        return Boolean.parseBoolean(settingsRepo.get(KEY_AUTO_SCROLL, "true"));
    }

    public boolean menuBarEnabled(boolean defaultValue) {
        return Boolean.parseBoolean(settingsRepo.get(KEY_MENU_BAR_ENABLED, String.valueOf(defaultValue)));
    }

    public void persistSendKey(String sendKey) {
        settingsRepo.put(KEY_SEND, sendKey);
    }

    public void persistAutoScrollEnabled(boolean enabled) {
        settingsRepo.put(KEY_AUTO_SCROLL, String.valueOf(enabled));
    }

    public void persistMenuBarEnabled(boolean enabled) {
        settingsRepo.put(KEY_MENU_BAR_ENABLED, String.valueOf(enabled));
    }
}
