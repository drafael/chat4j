package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import lombok.NonNull;

import java.util.function.Consumer;

public class GeneralSettingsDefaultModeApplyCoordinator {

    public RenderMode apply(
            @NonNull RenderMode defaultRenderMode,
            @NonNull Consumer<RenderMode> setDefaultRenderMode
    ) {

        setDefaultRenderMode.accept(defaultRenderMode);
        return defaultRenderMode;
    }
}
