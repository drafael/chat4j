package com.github.drafael.chat4j.provider.support;


import lombok.NonNull;
import java.util.function.Consumer;

public class ModelMenuStructureRebuildApplyCoordinator {

    public ModelMenuStructureRebuildCoordinator.RebuildState apply(
            @NonNull ModelMenuStructureRebuildCoordinator.RebuildState rebuildState,
            @NonNull Consumer<Boolean> setModelsMenuDirty,
            @NonNull Consumer<String> setLastMenuSelectedModelKey
    ) {

        setModelsMenuDirty.accept(rebuildState.modelsMenuDirty());
        setLastMenuSelectedModelKey.accept(rebuildState.lastMenuSelectedModelKey());
        return rebuildState;
    }
}
