package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.apache.commons.lang3.Validate;

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

    ModelMenuStructureRebuildCoordinator(RebuildAction rebuildAction) {
        this.rebuildAction = Validate.notNull(rebuildAction, "rebuildAction must not be null");
    }

    public RebuildState rebuild(
            JMenu modelsMenu,
            Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            Map<String, JMenuItem> providerHeaderItemsByName,
            List<ProviderRegistry.ProviderDef> providers,
            Consumer<String> onModelSelected,
            boolean currentModelsMenuDirty,
            String currentLastMenuSelectedModelKey
    ) {
        Validate.notNull(modelMenuItemsByKey, "modelMenuItemsByKey must not be null");
        Validate.notNull(providerHeaderItemsByName, "providerHeaderItemsByName must not be null");
        Validate.notNull(providers, "providers must not be null");
        Validate.notNull(onModelSelected, "onModelSelected must not be null");

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
        boolean rebuild(
                JMenu modelsMenu,
                Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
                Map<String, JMenuItem> providerHeaderItemsByName,
                List<ProviderRegistry.ProviderDef> providers,
                Consumer<String> onModelSelected
        );
    }
}
