package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.RenderMode;

public class MainFrameRenderModeState {

    private RenderMode defaultRenderMode = RenderMode.PREVIEW;

    public RenderMode defaultRenderMode() {
        return defaultRenderMode;
    }

    public void setDefaultRenderMode(RenderMode defaultRenderMode) {
        this.defaultRenderMode = defaultRenderMode;
    }
}
