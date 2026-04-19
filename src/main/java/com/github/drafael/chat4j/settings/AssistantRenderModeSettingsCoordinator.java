package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import com.github.drafael.chat4j.storage.SettingsRepo;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.util.UUID;

public class AssistantRenderModeSettingsCoordinator {

    private static final String KEY_ASSISTANT_MARKDOWN_DEFAULT = "chat.markdown.default";
    private static final String KEY_ASSISTANT_MARKDOWN_CONVERSATION_PREFIX = "chat.markdown.conv.";

    private final SettingsRepo settingsRepo;

    public AssistantRenderModeSettingsCoordinator(SettingsRepo settingsRepo) {
        this.settingsRepo = Validate.notNull(settingsRepo, "settingsRepo must not be null");
    }

    public AssistantRenderMode resolveDefaultMode() {
        try {
            return AssistantRenderMode.fromSettingValue(
                    settingsRepo.get(KEY_ASSISTANT_MARKDOWN_DEFAULT, AssistantRenderMode.PREVIEW.settingValue())
            );
        } catch (Exception e) {
            return AssistantRenderMode.PREVIEW;
        }
    }

    public AssistantRenderMode resolveConversationMode(UUID conversationId, AssistantRenderMode defaultMode) {
        Validate.notNull(defaultMode, "defaultMode must not be null");
        if (conversationId == null) {
            return defaultMode;
        }

        try {
            String key = KEY_ASSISTANT_MARKDOWN_CONVERSATION_PREFIX + conversationId;
            String stored = settingsRepo.get(key, null);
            if (StringUtils.isBlank(stored)) {
                return defaultMode;
            }
            return AssistantRenderMode.fromSettingValue(stored);
        } catch (Exception e) {
            return defaultMode;
        }
    }

    public void persistConversationMode(UUID conversationId, AssistantRenderMode mode) {
        if (conversationId == null || mode == null) {
            return;
        }

        try {
            settingsRepo.put(KEY_ASSISTANT_MARKDOWN_CONVERSATION_PREFIX + conversationId, mode.settingValue());
        } catch (Exception ignored) {
            // Assistant render mode persistence is best-effort.
        }
    }
}
