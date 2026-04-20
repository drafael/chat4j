package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.AssistantRenderMode;

public class MainFrameAssistantRenderModeState {

    private AssistantRenderMode defaultAssistantRenderMode = AssistantRenderMode.PREVIEW;

    public AssistantRenderMode defaultAssistantRenderMode() {
        return defaultAssistantRenderMode;
    }

    public void setDefaultAssistantRenderMode(AssistantRenderMode defaultAssistantRenderMode) {
        this.defaultAssistantRenderMode = defaultAssistantRenderMode;
    }
}
