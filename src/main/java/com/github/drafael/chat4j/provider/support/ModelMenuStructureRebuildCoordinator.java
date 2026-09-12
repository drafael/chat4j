package com.github.drafael.chat4j.provider.support;

import lombok.NonNull;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.Map;
import java.util.function.Consumer;

public class ModelMenuStructureRebuildCoordinator {

    private final RebuildAction rebuildAction;

    public ModelMenuStructureRebuildCoordinator(ProviderMenuStructureRebuilder providerMenuStructureRebuilder) {
        this(providerMenuStructureRebuilder::rebuild);
    }

    ModelMenuStructureRebuildCoordinator(@NonNull RebuildAction rebuildAction) {
        this.rebuildAction = rebuildAction;
    }

    public RebuildState rebuild(
            JMenu modelsMenu,
            @NonNull Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            @NonNull Map<String, JMenuItem> providerHeaderItemsByName,
            @NonNull ProviderMenuDataResolver.ProviderMenuData menuData,
            @NonNull Consumer<String> onModelSelected,
            boolean currentModelsMenuDirty,
            String currentLastMenuSelectedModelKey
    ) {

        if (modelsMenu == null) {
            return new RebuildState(currentModelsMenuDirty, currentLastMenuSelectedModelKey);
        }

        rebuildAction.rebuild(modelsMenu, modelMenuItemsByKey, providerHeaderItemsByName, menuData, onModelSelected);
        return new RebuildState(false, null);
    }

    public record RebuildState(boolean modelsMenuDirty, String lastMenuSelectedModelKey) {
    }

    @FunctionalInterface
    interface RebuildAction {
        void rebuild(
                JMenu modelsMenu,
                Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
                Map<String, JMenuItem> providerHeaderItemsByName,
                ProviderMenuDataResolver.ProviderMenuData menuData,
                Consumer<String> onModelSelected
        );
    }
}
