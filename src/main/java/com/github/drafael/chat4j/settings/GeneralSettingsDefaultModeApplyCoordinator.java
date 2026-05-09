package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import lombok.NonNull;

import java.util.function.Consumer;

public class GeneralSettingsDefaultModeApplyCoordinator {

    public AssistantRenderMode apply(
            @NonNull AssistantRenderMode defaultAssistantRenderMode,
            @NonNull Consumer<AssistantRenderMode> setAssistantMarkdownDefaultMode
    ) {

        setAssistantMarkdownDefaultMode.accept(defaultAssistantRenderMode);
        return defaultAssistantRenderMode;
    }
}
