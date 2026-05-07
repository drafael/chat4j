package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsKeys;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public class AgentModeSettingsCoordinator {

    private static final String KEY_AGENT_SYSTEM_PROMPT_APPEND = SettingsKeys.CHAT_AGENT_SYSTEM_PROMPT_APPEND;

    private final SettingsRepo settingsRepo;

    public AgentModeSettingsCoordinator(@NonNull SettingsRepo settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public String resolveSystemPromptAppend() {
        try {
            return StringUtils.defaultString(settingsRepo.get(KEY_AGENT_SYSTEM_PROMPT_APPEND, ""));
        } catch (Exception e) {
            return "";
        }
    }

    public void persistSystemPromptAppend(String promptAppend) {
        String normalized = StringUtils.defaultString(promptAppend);
        if (StringUtils.isBlank(normalized)) {
            removeSystemPromptAppend();
            return;
        }

        try {
            settingsRepo.put(KEY_AGENT_SYSTEM_PROMPT_APPEND, normalized);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private void removeSystemPromptAppend() {
        try {
            settingsRepo.remove(KEY_AGENT_SYSTEM_PROMPT_APPEND);
        } catch (Exception ignored) {
            // best effort
        }
    }
}
