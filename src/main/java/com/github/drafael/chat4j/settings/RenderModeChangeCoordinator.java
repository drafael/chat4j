package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import lombok.NonNull;

public class RenderModeChangeCoordinator {

    private final ModePersister modePersister;

    public RenderModeChangeCoordinator(RenderModeSettingsCoordinator renderModeSettingsCoordinator) {
        this(renderModeSettingsCoordinator::persistDefaultMode);
    }

    RenderModeChangeCoordinator(@NonNull ModePersister modePersister) {
        this.modePersister = modePersister;
    }

    public ApplyResult apply(RenderMode mode) {
        if (mode == null) {
            return ApplyResult.ignoredResult();
        }

        modePersister.persist(mode);
        return ApplyResult.handledResult();
    }

    public record ApplyResult(boolean handled) {

        static ApplyResult ignoredResult() {
            return new ApplyResult(false);
        }

        static ApplyResult handledResult() {
            return new ApplyResult(true);
        }
    }

    @FunctionalInterface
    interface ModePersister {
        void persist(RenderMode mode);
    }
}
