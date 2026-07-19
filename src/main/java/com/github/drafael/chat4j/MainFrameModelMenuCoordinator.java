package com.github.drafael.chat4j;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshTriggerCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityRefreshDispatchCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuReadyDispatchCoordinator;
import lombok.NonNull;

import java.util.function.Consumer;
import java.util.function.Supplier;

public class MainFrameModelMenuCoordinator {

    private final ProviderMenuReadyDispatchCoordinator providerMenuReadyDispatchCoordinator;
    private final ModelMenuStructureRebuildCoordinator modelMenuStructureRebuildCoordinator;
    private final ModelMenuStructureRebuildApplyCoordinator modelMenuStructureRebuildApplyCoordinator;
    private final ModelMenuSelectionDispatchCoordinator modelMenuSelectionDispatchCoordinator;
    private final ModelMenuSelectionApplyCoordinator modelMenuSelectionApplyCoordinator;
    private final ModelMenuSelectionChangeCoordinator modelMenuSelectionChangeCoordinator;
    private final ModelMenuDirtyRefreshTriggerCoordinator modelMenuDirtyRefreshTriggerCoordinator;
    private final ProviderMenuAvailabilityRefreshDispatchCoordinator providerMenuAvailabilityRefreshDispatchCoordinator;
    private final ProviderMenuIconResolver providerMenuIconResolver;
    private final ProviderRegistry providerRegistry;

    public MainFrameModelMenuCoordinator(
            @NonNull ProviderMenuReadyDispatchCoordinator providerMenuReadyDispatchCoordinator,
            @NonNull ModelMenuStructureRebuildCoordinator modelMenuStructureRebuildCoordinator,
            @NonNull ModelMenuStructureRebuildApplyCoordinator modelMenuStructureRebuildApplyCoordinator,
            @NonNull ModelMenuSelectionDispatchCoordinator modelMenuSelectionDispatchCoordinator,
            @NonNull ModelMenuSelectionApplyCoordinator modelMenuSelectionApplyCoordinator,
            @NonNull ModelMenuSelectionChangeCoordinator modelMenuSelectionChangeCoordinator,
            @NonNull ModelMenuDirtyRefreshTriggerCoordinator modelMenuDirtyRefreshTriggerCoordinator,
            @NonNull ProviderMenuAvailabilityRefreshDispatchCoordinator providerMenuAvailabilityRefreshDispatchCoordinator,
            @NonNull ProviderMenuIconResolver providerMenuIconResolver,
            @NonNull ProviderRegistry providerRegistry
    ) {
        this.providerMenuReadyDispatchCoordinator = providerMenuReadyDispatchCoordinator;
        this.modelMenuStructureRebuildCoordinator = modelMenuStructureRebuildCoordinator;
        this.modelMenuStructureRebuildApplyCoordinator = modelMenuStructureRebuildApplyCoordinator;
        this.modelMenuSelectionDispatchCoordinator = modelMenuSelectionDispatchCoordinator;
        this.modelMenuSelectionApplyCoordinator = modelMenuSelectionApplyCoordinator;
        this.modelMenuSelectionChangeCoordinator = modelMenuSelectionChangeCoordinator;
        this.modelMenuDirtyRefreshTriggerCoordinator = modelMenuDirtyRefreshTriggerCoordinator;
        this.providerMenuAvailabilityRefreshDispatchCoordinator = providerMenuAvailabilityRefreshDispatchCoordinator;
        this.providerMenuIconResolver = providerMenuIconResolver;
        this.providerRegistry = providerRegistry;
    }

    public void ensureReady(@NonNull ModelMenuContext context) {
        providerMenuReadyDispatchCoordinator.ensureReady(
                context.modelMenuState().modelsMenuDirty(),
                () -> rebuildStructure(context),
                () -> refreshAvailability(context),
                () -> syncSelection(context)
        );
    }

    public void markDirty(@NonNull ModelMenuContext context) {
        context.modelMenuState().markModelsMenuDirty();
    }

    public void onSelectedModelChanged(@NonNull ModelMenuContext context) {
        modelMenuSelectionChangeCoordinator.onSelectedModelChanged(
                context.boundMenusState().modelsMenu(),
                context.modelMenuState().modelsMenuDirty(),
                () -> syncSelection(context)
        );
    }

    public void onModelFavoritesChanged(@NonNull ModelMenuContext context) {
        triggerDirtyRefresh(context);
    }

    public void onModelCatalogChanged(@NonNull ModelMenuContext context) {
        triggerDirtyRefresh(context);
    }

    private void triggerDirtyRefresh(ModelMenuContext context) {
        modelMenuDirtyRefreshTriggerCoordinator.trigger(
                context.boundMenusState().modelsMenu(),
                () -> markDirty(context),
                () -> ensureReady(context)
        );
    }

    private void rebuildStructure(ModelMenuContext context) {
        ModelMenuStructureRebuildCoordinator.RebuildState rebuildState = modelMenuStructureRebuildCoordinator.rebuild(
                context.boundMenusState().modelsMenu(),
                context.menuItemsState().modelMenuItemsByKey(),
                context.menuItemsState().providerHeaderItemsByName(),
                providerRegistry.availableProviders(),
                context.setSelectedModel(),
                context.modelMenuState().modelsMenuDirty(),
                context.modelMenuState().lastMenuSelectedModelKey()
        );

        modelMenuStructureRebuildApplyCoordinator.apply(
                rebuildState,
                context.modelMenuState()::setModelsMenuDirty,
                context.modelMenuState()::setLastMenuSelectedModelKey
        );
    }

    private void syncSelection(ModelMenuContext context) {
        String syncedSelection = modelMenuSelectionDispatchCoordinator.sync(
                context.menuItemsState().modelMenuItemsByKey(),
                context.selectedModelSupplier().get(),
                context.modelMenuState().lastMenuSelectedModelKey(),
                context.modelMenuState().modelsMenuDirty()
        );

        modelMenuSelectionApplyCoordinator.apply(
                syncedSelection,
                context.modelMenuState()::setLastMenuSelectedModelKey
        );
    }

    private void refreshAvailability(ModelMenuContext context) {
        providerMenuAvailabilityRefreshDispatchCoordinator.refresh(
                context.menuItemsState().modelMenuItemsByKey(),
                context.menuItemsState().providerHeaderItemsByName(),
                providerMenuIconResolver
        );
    }

    public record ModelMenuContext(
            @NonNull MainFrameBoundMenusState boundMenusState,
            @NonNull MainFrameMenuItemsState menuItemsState,
            @NonNull MainFrameModelMenuState modelMenuState,
            @NonNull Supplier<String> selectedModelSupplier,
            @NonNull Consumer<String> setSelectedModel
    ) {
    }
}
