package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.util.Locale;
import java.util.UUID;

public class AssistantRenderModeSettingsCoordinator {

    private static final String KEY_ASSISTANT_MARKDOWN_DEFAULT = SettingsKeys.CHAT_RENDER_MODE;

    private final SettingsRepo settingsRepo;

    public AssistantRenderModeSettingsCoordinator(SettingsRepo settingsRepo) {
        this.settingsRepo = Validate.notNull(settingsRepo, "settingsRepo must not be null");
    }

    public AssistantRenderMode resolveDefaultMode() {
        try {
            String stored = settingsRepo.get(KEY_ASSISTANT_MARKDOWN_DEFAULT, SettingsKeys.CHAT_RENDER_MODE_PREVIEW);
            return toRenderMode(stored);
        } catch (Exception e) {
            return AssistantRenderMode.PREVIEW;
        }
    }

    public AssistantRenderMode resolveConversationMode(UUID conversationId, AssistantRenderMode defaultMode) {
        Validate.notNull(defaultMode, "defaultMode must not be null");
        return defaultMode;
    }

    public void persistConversationMode(UUID conversationId, AssistantRenderMode mode) {
        if (mode == null) {
            return;
        }

        try {
            settingsRepo.put(KEY_ASSISTANT_MARKDOWN_DEFAULT, toSettingValue(mode));
        } catch (Exception ignored) {
            // Assistant render mode persistence is best-effort.
        }
    }

    private AssistantRenderMode toRenderMode(String stored) {
        if (StringUtils.isBlank(stored)) {
            return AssistantRenderMode.PREVIEW;
        }

        String normalized = stored.trim().toUpperCase(Locale.ROOT);
        if (SettingsKeys.CHAT_RENDER_MODE_MARKDOWN.equals(normalized)) {
            return AssistantRenderMode.MARKDOWN;
        }

        if (SettingsKeys.CHAT_RENDER_MODE_PREVIEW.equals(normalized)) {
            return AssistantRenderMode.PREVIEW;
        }

        return AssistantRenderMode.PREVIEW;
    }

    private String toSettingValue(AssistantRenderMode mode) {
        return mode == AssistantRenderMode.MARKDOWN
                ? SettingsKeys.CHAT_RENDER_MODE_MARKDOWN
                : SettingsKeys.CHAT_RENDER_MODE_PREVIEW;
    }
}
