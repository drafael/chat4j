package com.github.drafael.chat4j;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshTriggerCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityRefreshDispatchCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuDataResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconResolver;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

@Slf4j
public class MainFrameModelMenuCoordinator {

    private final ProviderMenuDataResolver providerMenuDataResolver;
    private final ModelMenuStructureRebuildCoordinator modelMenuStructureRebuildCoordinator;
    private final ModelMenuStructureRebuildApplyCoordinator modelMenuStructureRebuildApplyCoordinator;
    private final ModelMenuSelectionDispatchCoordinator modelMenuSelectionDispatchCoordinator;
    private final ModelMenuSelectionApplyCoordinator modelMenuSelectionApplyCoordinator;
    private final ModelMenuSelectionChangeCoordinator modelMenuSelectionChangeCoordinator;
    private final ModelMenuDirtyRefreshTriggerCoordinator modelMenuDirtyRefreshTriggerCoordinator;
    private final ProviderMenuAvailabilityRefreshDispatchCoordinator providerMenuAvailabilityRefreshDispatchCoordinator;
    private final ProviderMenuIconResolver providerMenuIconResolver;
    private final ProviderRegistry providerRegistry;
    private final AtomicLong refreshCounter = new AtomicLong();
    private final AtomicReference<Thread> refreshThread = new AtomicReference<>();
    private final Set<JMenu> observedMenus = Collections.newSetFromMap(new IdentityHashMap<>());
    private PendingSnapshot pendingSnapshot;
    private volatile boolean disposed;

    public MainFrameModelMenuCoordinator(
            @NonNull ProviderMenuDataResolver providerMenuDataResolver,
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
        this.providerMenuDataResolver = providerMenuDataResolver;
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
        if (disposed) {
            return;
        }
        observeMenu(context.boundMenusState().modelsMenu());
        if (context.modelMenuState().modelsMenuDirty() && refreshThread.get() == null) {
            requestRefresh(context);
        }
        syncSelection(context);
        SwingUtilities.invokeLater(() -> {
            if (!disposed) {
                refreshAvailability(context);
            }
        });
    }

    public void requestRefresh(@NonNull ModelMenuContext context) {
        if (disposed) {
            return;
        }
        context.modelMenuState().markModelsMenuDirty();
        observeMenu(context.boundMenusState().modelsMenu());
        showLoadingStateIfEmpty(context.boundMenusState().modelsMenu());
        pendingSnapshot = null;

        long refreshId = refreshCounter.incrementAndGet();
        Thread loadThread = Thread.ofVirtual().name("chat4j-model-menu").unstarted(
                () -> loadSnapshot(refreshId, context)
        );
        Thread previous = refreshThread.getAndSet(loadThread);
        if (previous != null) {
            previous.interrupt();
        }
        try {
            loadThread.start();
        } catch (RuntimeException | Error e) {
            refreshThread.compareAndSet(loadThread, null);
            throw e;
        }
    }

    public void dispose() {
        disposed = true;
        refreshCounter.incrementAndGet();
        pendingSnapshot = null;
        Thread activeRefresh = refreshThread.getAndSet(null);
        if (activeRefresh != null) {
            activeRefresh.interrupt();
        }
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

    private void loadSnapshot(long refreshId, ModelMenuContext context) {
        Thread currentThread = Thread.currentThread();
        try {
            List<ProviderRegistry.ProviderDef> providers = providerRegistry.availableProviders();
            if (!isCurrent(refreshId)) {
                return;
            }
            ProviderMenuDataResolver.ProviderMenuData menuData = providerMenuDataResolver.resolve(providers);
            if (!isCurrent(refreshId)) {
                return;
            }
            SwingUtilities.invokeLater(() -> applySnapshot(refreshId, currentThread, context, menuData));
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> applyRefreshFailure(refreshId, currentThread, e));
        }
    }

    private boolean isCurrent(long refreshId) {
        return !disposed && refreshCounter.get() == refreshId && !Thread.currentThread().isInterrupted();
    }

    private void applySnapshot(
            long refreshId,
            Thread completedThread,
            ModelMenuContext context,
            ProviderMenuDataResolver.ProviderMenuData menuData
    ) {
        if (disposed || refreshCounter.get() != refreshId) {
            refreshThread.compareAndSet(completedThread, null);
            return;
        }
        JMenu modelsMenu = context.boundMenusState().modelsMenu();
        if (modelsMenu != null && modelsMenu.isPopupMenuVisible()) {
            pendingSnapshot = new PendingSnapshot(refreshId, completedThread, context, menuData);
            return;
        }
        applySnapshotNow(completedThread, context, menuData);
    }

    private void applySnapshotNow(
            Thread completedThread,
            ModelMenuContext context,
            ProviderMenuDataResolver.ProviderMenuData menuData
    ) {
        try {
            rebuildStructure(context, menuData);
            syncSelection(context);
        } finally {
            refreshThread.compareAndSet(completedThread, null);
        }
    }

    private void applyRefreshFailure(long refreshId, Thread completedThread, Exception error) {
        try {
            if (!disposed && refreshCounter.get() == refreshId && !completedThread.isInterrupted()) {
                log.warn("Failed to refresh the model menu: {}", ExceptionUtils.getMessage(error));
            }
        } finally {
            refreshThread.compareAndSet(completedThread, null);
        }
    }

    private void triggerDirtyRefresh(ModelMenuContext context) {
        modelMenuDirtyRefreshTriggerCoordinator.trigger(
                context.boundMenusState().modelsMenu(),
                () -> requestRefresh(context),
                () -> ensureReady(context)
        );
    }

    private void rebuildStructure(
            ModelMenuContext context,
            ProviderMenuDataResolver.ProviderMenuData menuData
    ) {
        ModelMenuStructureRebuildCoordinator.RebuildState rebuildState = modelMenuStructureRebuildCoordinator.rebuild(
                context.boundMenusState().modelsMenu(),
                context.menuItemsState().modelMenuItemsByKey(),
                context.menuItemsState().providerHeaderItemsByName(),
                menuData,
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
                false
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

    private void observeMenu(JMenu modelsMenu) {
        if (modelsMenu == null || !observedMenus.add(modelsMenu)) {
            return;
        }

        modelsMenu.getPopupMenu().addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                applyPendingSnapshot(modelsMenu);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                applyPendingSnapshot(modelsMenu);
            }
        });
    }

    private void applyPendingSnapshot(JMenu modelsMenu) {
        PendingSnapshot pending = pendingSnapshot;
        if (pending == null || pending.context().boundMenusState().modelsMenu() != modelsMenu) {
            return;
        }

        pendingSnapshot = null;
        if (!disposed && refreshCounter.get() == pending.refreshId()) {
            applySnapshotNow(pending.completedThread(), pending.context(), pending.menuData());
        } else {
            refreshThread.compareAndSet(pending.completedThread(), null);
        }
    }

    private static void showLoadingStateIfEmpty(JMenu modelsMenu) {
        if (modelsMenu == null || modelsMenu.getMenuComponentCount() > 0) {
            return;
        }

        JMenuItem loadingItem = new JMenuItem("Loading models…");
        loadingItem.setEnabled(false);
        modelsMenu.add(loadingItem);
    }

    private record PendingSnapshot(
            long refreshId,
            Thread completedThread,
            ModelMenuContext context,
            ProviderMenuDataResolver.ProviderMenuData menuData
    ) {
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
