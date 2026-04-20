package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.Validate;

import java.util.function.Consumer;

public class ModelMenuStructureRebuildApplyCoordinator {

    public ModelMenuStructureRebuildCoordinator.RebuildState apply(
            ModelMenuStructureRebuildCoordinator.RebuildState rebuildState,
            Consumer<Boolean> setModelsMenuDirty,
            Consumer<String> setLastMenuSelectedModelKey
    ) {
        Validate.notNull(rebuildState, "rebuildState must not be null");
        Validate.notNull(setModelsMenuDirty, "setModelsMenuDirty must not be null");
        Validate.notNull(
                setLastMenuSelectedModelKey,
                "setLastMenuSelectedModelKey must not be null"
        );

        setModelsMenuDirty.accept(rebuildState.modelsMenuDirty());
        setLastMenuSelectedModelKey.accept(rebuildState.lastMenuSelectedModelKey());
        return rebuildState;
    }
}
