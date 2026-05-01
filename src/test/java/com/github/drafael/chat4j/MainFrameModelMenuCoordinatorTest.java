package com.github.drafael.chat4j;

import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshTriggerCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityRefreshDispatchCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuReadyDispatchCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MainFrameModelMenuCoordinatorTest {

    @Test
    @DisplayName("Ensure ready rebuilds, refreshes availability, and syncs selection")
    void ensureReady_whenMenuDirty_runsReadyFlowAndUpdatesState() {
        ProviderMenuReadyDispatchCoordinator readyDispatchCoordinator = mock(ProviderMenuReadyDispatchCoordinator.class);
        ModelMenuStructureRebuildCoordinator rebuildCoordinator = mock(ModelMenuStructureRebuildCoordinator.class);
        var rebuildApplyCoordinator = new ModelMenuStructureRebuildApplyCoordinator();
        ModelMenuSelectionDispatchCoordinator selectionDispatchCoordinator = mock(ModelMenuSelectionDispatchCoordinator.class);
        var selectionApplyCoordinator = new ModelMenuSelectionApplyCoordinator();
        var selectionChangeCoordinator = mock(ModelMenuSelectionChangeCoordinator.class);
        var dirtyRefreshTriggerCoordinator = mock(ModelMenuDirtyRefreshTriggerCoordinator.class);
        ProviderMenuAvailabilityRefreshDispatchCoordinator availabilityRefreshDispatchCoordinator = mock(
                ProviderMenuAvailabilityRefreshDispatchCoordinator.class
        );
        ProviderMenuIconResolver providerMenuIconResolver = mock(ProviderMenuIconResolver.class);
        var subject = new MainFrameModelMenuCoordinator(
                readyDispatchCoordinator,
                rebuildCoordinator,
                rebuildApplyCoordinator,
                selectionDispatchCoordinator,
                selectionApplyCoordinator,
                selectionChangeCoordinator,
                dirtyRefreshTriggerCoordinator,
                availabilityRefreshDispatchCoordinator,
                providerMenuIconResolver
        );
        MainFrameBoundMenusState boundMenusState = new MainFrameBoundMenusState();
        boundMenusState.setModelsMenu(new JMenu("Models"));
        var menuItemsState = new MainFrameMenuItemsState();
        var modelMenuState = new MainFrameModelMenuState(true, "old-model");
        var selectedModel = new AtomicReference<>("OpenAI > gpt-4.1");
        MainFrameModelMenuCoordinator.ModelMenuContext context = new MainFrameModelMenuCoordinator.ModelMenuContext(
                boundMenusState,
                menuItemsState,
                modelMenuState,
                selectedModel::get,
                selectedModel::set
        );
        when(rebuildCoordinator.rebuild(
                eq(boundMenusState.modelsMenu()),
                eq(menuItemsState.modelMenuItemsByKey()),
                eq(menuItemsState.providerHeaderItemsByName()),
                anyList(),
                any(),
                eq(true),
                eq("old-model")
        )).thenReturn(new ModelMenuStructureRebuildCoordinator.RebuildState(false, null));
        when(selectionDispatchCoordinator.sync(
                eq(menuItemsState.modelMenuItemsByKey()),
                eq("OpenAI > gpt-4.1"),
                eq(null),
                eq(false)
        )).thenReturn("OpenAI > gpt-4.1");
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            invocation.getArgument(2, Runnable.class).run();
            invocation.getArgument(3, Runnable.class).run();
            return null;
        }).when(readyDispatchCoordinator).ensureReady(anyBoolean(), any(), any(), any());

        subject.ensureReady(context);

        assertThat(modelMenuState.modelsMenuDirty()).isFalse();
        assertThat(modelMenuState.lastMenuSelectedModelKey()).isEqualTo("OpenAI > gpt-4.1");
        verify(availabilityRefreshDispatchCoordinator).refresh(
                menuItemsState.modelMenuItemsByKey(),
                menuItemsState.providerHeaderItemsByName(),
                providerMenuIconResolver
        );
    }

    @Test
    @DisplayName("Favorites changes mark models dirty and request menu readiness")
    void onModelFavoritesChanged_whenTriggered_marksDirtyAndEnsuresReady() {
        ProviderMenuReadyDispatchCoordinator readyDispatchCoordinator = mock(ProviderMenuReadyDispatchCoordinator.class);
        var subject = new MainFrameModelMenuCoordinator(
                readyDispatchCoordinator,
                mock(ModelMenuStructureRebuildCoordinator.class),
                new ModelMenuStructureRebuildApplyCoordinator(),
                mock(ModelMenuSelectionDispatchCoordinator.class),
                new ModelMenuSelectionApplyCoordinator(),
                mock(ModelMenuSelectionChangeCoordinator.class),
                dirtyRefreshTriggerCoordinatorRunningActions(),
                mock(ProviderMenuAvailabilityRefreshDispatchCoordinator.class),
                mock(ProviderMenuIconResolver.class)
        );
        MainFrameBoundMenusState boundMenusState = new MainFrameBoundMenusState();
        boundMenusState.setModelsMenu(new JMenu("Models"));
        var modelMenuState = new MainFrameModelMenuState(false, null);
        MainFrameModelMenuCoordinator.ModelMenuContext context = new MainFrameModelMenuCoordinator.ModelMenuContext(
                boundMenusState,
                new MainFrameMenuItemsState(),
                modelMenuState,
                () -> null,
                ignored -> {
                }
        );

        subject.onModelFavoritesChanged(context);

        assertThat(modelMenuState.modelsMenuDirty()).isTrue();
        verify(readyDispatchCoordinator).ensureReady(eq(true), any(), any(), any());
    }

    private ModelMenuDirtyRefreshTriggerCoordinator dirtyRefreshTriggerCoordinatorRunningActions() {
        ModelMenuDirtyRefreshTriggerCoordinator coordinator = mock(ModelMenuDirtyRefreshTriggerCoordinator.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            invocation.getArgument(2, Runnable.class).run();
            return null;
        }).when(coordinator).trigger(any(), any(), any());
        return coordinator;
    }
}
