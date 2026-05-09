package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import lombok.NonNull;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.List;
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
            @NonNull List<ProviderRegistry.ProviderDef> providers,
            @NonNull Consumer<String> onModelSelected,
            boolean currentModelsMenuDirty,
            String currentLastMenuSelectedModelKey
    ) {

        if (modelsMenu == null) {
            return new RebuildState(currentModelsMenuDirty, currentLastMenuSelectedModelKey);
        }

        rebuildAction.rebuild(modelsMenu, modelMenuItemsByKey, providerHeaderItemsByName, providers, onModelSelected);
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
                List<ProviderRegistry.ProviderDef> providers,
                Consumer<String> onModelSelected
        );
    }
}
