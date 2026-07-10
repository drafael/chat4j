package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public final class AgentModeSettings {

    private static final String KEY_AGENT_SYSTEM_PROMPT_APPEND = "chat4j.chat.agent.systemPromptAppend";

    private final SettingsRepository settingsRepo;

    public AgentModeSettings(@NonNull SettingsRepository settingsRepo) {
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

        settingsRepo.put(KEY_AGENT_SYSTEM_PROMPT_APPEND, normalized);
    }

    private void removeSystemPromptAppend() {
        settingsRepo.remove(KEY_AGENT_SYSTEM_PROMPT_APPEND);
    }
}
