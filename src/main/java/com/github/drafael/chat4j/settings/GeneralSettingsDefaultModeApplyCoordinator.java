package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.AssistantRenderMode;
import org.apache.commons.lang3.Validate;

import java.util.function.Consumer;

public class GeneralSettingsDefaultModeApplyCoordinator {

    public AssistantRenderMode apply(
            AssistantRenderMode defaultAssistantRenderMode,
            Consumer<AssistantRenderMode> setAssistantMarkdownDefaultMode
    ) {
        Validate.notNull(defaultAssistantRenderMode, "defaultAssistantRenderMode must not be null");
        Validate.notNull(
                setAssistantMarkdownDefaultMode,
                "setAssistantMarkdownDefaultMode must not be null"
        );

        setAssistantMarkdownDefaultMode.accept(defaultAssistantRenderMode);
        return defaultAssistantRenderMode;
    }
}
