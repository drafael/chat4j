package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import lombok.NonNull;

public class RenderModeSelectionResolver {

    public RenderMode resolve(@NonNull RenderMode defaultMode) {
        return defaultMode;
    }
}
