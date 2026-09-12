package com.github.drafael.chat4j;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.provider.support.ModelMenuDirtyRefreshTriggerCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildApplyCoordinator;
import com.github.drafael.chat4j.provider.support.ModelMenuStructureRebuildCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuAvailabilityRefreshDispatchCoordinator;
import com.github.drafael.chat4j.provider.support.ProviderMenuDataResolver;
import com.github.drafael.chat4j.provider.support.ProviderMenuIconResolver;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JMenu;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainFrameModelMenuCoordinatorTest {

    @Test
    @DisplayName("Opening a dirty model menu remains responsive while its snapshot is resolved")
    void ensureReady_whenSnapshotResolutionBlocks_keepsEdtResponsiveAndAppliesLater() throws Exception {
        ProviderMenuDataResolver dataResolver = mock(ProviderMenuDataResolver.class);
        ModelMenuStructureRebuildCoordinator rebuildCoordinator = mock(ModelMenuStructureRebuildCoordinator.class);
        ModelMenuSelectionDispatchCoordinator selectionDispatchCoordinator = mock(ModelMenuSelectionDispatchCoordinator.class);
        ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
        var resolutionStarted = new CountDownLatch(1);
        var releaseResolution = new CountDownLatch(1);
        var snapshotApplied = new CountDownLatch(1);
        var resolvedOnEdt = new AtomicBoolean(true);
        var menuData = emptyMenuData();
        when(providerRegistry.availableProviders()).thenReturn(emptyList());
        when(dataResolver.resolve(emptyList())).thenAnswer(invocation -> {
            resolvedOnEdt.set(SwingUtilities.isEventDispatchThread());
            resolutionStarted.countDown();
            releaseResolution.await(3, TimeUnit.SECONDS);
            return menuData;
        });
        when(rebuildCoordinator.rebuild(any(), anyMap(), anyMap(), any(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    snapshotApplied.countDown();
                    return new ModelMenuStructureRebuildCoordinator.RebuildState(false, null);
                });
        when(selectionDispatchCoordinator.sync(anyMap(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        var subject = subject(dataResolver, rebuildCoordinator, selectionDispatchCoordinator, providerRegistry);
        var context = callOnEdt(() -> context(true));
        var ensureReturned = new CountDownLatch(1);

        try {
            SwingUtilities.invokeLater(() -> {
                subject.ensureReady(context);
                ensureReturned.countDown();
            });

            assertThat(ensureReturned.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(resolutionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(resolvedOnEdt).isFalse();
            assertThat(context.modelMenuState().modelsMenuDirty()).isTrue();

            releaseResolution.countDown();
            assertThat(snapshotApplied.await(3, TimeUnit.SECONDS)).isTrue();
            flushEdt();

            assertThat(context.modelMenuState().modelsMenuDirty()).isFalse();
            assertThat(callOnEdt(() -> context.boundMenusState().modelsMenu().getItem(0).getText()))
                    .isEqualTo("Loading models…");
        } finally {
            releaseResolution.countDown();
            assertThat(snapshotApplied.await(3, TimeUnit.SECONDS)).isTrue();
            subject.dispose();
            flushEdt();
        }
    }

    @Test
    @DisplayName("Favorites changes mark the menu dirty and request a background snapshot")
    void onModelFavoritesChanged_whenTriggered_requestsBackgroundRefresh() throws Exception {
        ProviderMenuDataResolver dataResolver = mock(ProviderMenuDataResolver.class);
        ModelMenuStructureRebuildCoordinator rebuildCoordinator = mock(ModelMenuStructureRebuildCoordinator.class);
        ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
        var resolutionStarted = new CountDownLatch(1);
        var releaseResolution = new CountDownLatch(1);
        var snapshotApplied = new CountDownLatch(1);
        when(providerRegistry.availableProviders()).thenReturn(emptyList());
        when(dataResolver.resolve(emptyList())).thenAnswer(invocation -> {
            resolutionStarted.countDown();
            releaseResolution.await(3, TimeUnit.SECONDS);
            return emptyMenuData();
        });
        when(rebuildCoordinator.rebuild(any(), anyMap(), anyMap(), any(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    snapshotApplied.countDown();
                    return new ModelMenuStructureRebuildCoordinator.RebuildState(false, null);
                });
        var subject = subject(
                dataResolver,
                rebuildCoordinator,
                mock(ModelMenuSelectionDispatchCoordinator.class),
                providerRegistry
        );
        var context = callOnEdt(() -> context(false));

        try {
            runOnEdt(() -> subject.onModelFavoritesChanged(context));

            assertThat(resolutionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(context.modelMenuState().modelsMenuDirty()).isTrue();
        } finally {
            releaseResolution.countDown();
            assertThat(snapshotApplied.await(3, TimeUnit.SECONDS)).isTrue();
            subject.dispose();
            flushEdt();
        }
    }

    @Test
    @DisplayName("Only the newest overlapping model menu snapshot is applied on the event dispatch thread")
    void requestRefresh_whenRequestsOverlap_appliesOnlyNewestSnapshotOnEdt() throws Exception {
        ProviderMenuDataResolver dataResolver = mock(ProviderMenuDataResolver.class);
        ModelMenuStructureRebuildCoordinator rebuildCoordinator = mock(ModelMenuStructureRebuildCoordinator.class);
        ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
        var firstResolutionStarted = new CountDownLatch(1);
        var releaseFirstResolution = new CountDownLatch(1);
        var secondSnapshotApplied = new CountDownLatch(1);
        var resolutionCount = new AtomicInteger();
        var firstThread = new AtomicReference<Thread>();
        var secondThread = new AtomicReference<Thread>();
        var appliedData = new AtomicReference<ProviderMenuDataResolver.ProviderMenuData>();
        var appliedOnEdt = new AtomicBoolean();
        var firstMenuData = emptyMenuData();
        var secondMenuData = emptyMenuData();
        when(providerRegistry.availableProviders()).thenReturn(emptyList());
        when(dataResolver.resolve(emptyList())).thenAnswer(invocation -> {
            int resolution = resolutionCount.incrementAndGet();
            if (resolution == 1) {
                firstThread.set(Thread.currentThread());
                firstResolutionStarted.countDown();
                awaitIgnoringInterrupt(releaseFirstResolution);
                return firstMenuData;
            }
            secondThread.set(Thread.currentThread());
            return secondMenuData;
        });
        when(rebuildCoordinator.rebuild(any(), anyMap(), anyMap(), any(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    appliedData.set(invocation.getArgument(3));
                    appliedOnEdt.set(SwingUtilities.isEventDispatchThread());
                    secondSnapshotApplied.countDown();
                    return new ModelMenuStructureRebuildCoordinator.RebuildState(false, null);
                });
        var subject = subject(
                dataResolver,
                rebuildCoordinator,
                mock(ModelMenuSelectionDispatchCoordinator.class),
                providerRegistry
        );
        var context = callOnEdt(() -> context(false));

        try {
            runOnEdt(() -> subject.requestRefresh(context));
            assertThat(firstResolutionStarted.await(1, TimeUnit.SECONDS)).isTrue();

            runOnEdt(() -> subject.requestRefresh(context));
            assertThat(secondSnapshotApplied.await(3, TimeUnit.SECONDS)).isTrue();

            releaseFirstResolution.countDown();
            joinThread(firstThread.get());
            joinThread(secondThread.get());
            flushEdt();

            assertThat(appliedData.get()).isSameAs(secondMenuData);
            assertThat(appliedOnEdt).isTrue();
            assertThat(resolutionCount).hasValue(2);
        } finally {
            releaseFirstResolution.countDown();
            joinThread(firstThread.get());
            joinThread(secondThread.get());
            runOnEdt(subject::dispose);
            flushEdt();
        }
    }

    @Test
    @DisplayName("A resolved model menu snapshot waits until the open popup closes")
    void requestRefresh_whenMenuPopupIsVisible_defersSnapshotUntilPopupCloses() throws Exception {
        ProviderMenuDataResolver dataResolver = mock(ProviderMenuDataResolver.class);
        ModelMenuStructureRebuildCoordinator rebuildCoordinator = mock(ModelMenuStructureRebuildCoordinator.class);
        ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
        JMenu modelsMenu = mock(JMenu.class);
        JPopupMenu popupMenu = mock(JPopupMenu.class);
        var popupListener = new AtomicReference<PopupMenuListener>();
        var popupVisible = new AtomicBoolean(true);
        var visibilityChecked = new CountDownLatch(1);
        var resolutionStarted = new CountDownLatch(1);
        var workerThread = new AtomicReference<Thread>();
        var snapshotApplied = new CountDownLatch(1);
        var rebuildCount = new AtomicInteger();
        var menuData = emptyMenuData();
        when(modelsMenu.getPopupMenu()).thenReturn(popupMenu);
        when(modelsMenu.getMenuComponentCount()).thenReturn(1);
        when(modelsMenu.isPopupMenuVisible()).thenAnswer(invocation -> {
            visibilityChecked.countDown();
            return popupVisible.get();
        });
        doAnswer(invocation -> {
            popupListener.set(invocation.getArgument(0));
            return null;
        }).when(popupMenu).addPopupMenuListener(any());
        when(providerRegistry.availableProviders()).thenReturn(emptyList());
        when(dataResolver.resolve(emptyList())).thenAnswer(invocation -> {
            workerThread.set(Thread.currentThread());
            resolutionStarted.countDown();
            return menuData;
        });
        when(rebuildCoordinator.rebuild(any(), anyMap(), anyMap(), any(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    rebuildCount.incrementAndGet();
                    snapshotApplied.countDown();
                    return new ModelMenuStructureRebuildCoordinator.RebuildState(false, null);
                });
        var subject = subject(
                dataResolver,
                rebuildCoordinator,
                mock(ModelMenuSelectionDispatchCoordinator.class),
                providerRegistry
        );
        var context = callOnEdt(() -> context(false, modelsMenu));

        try {
            runOnEdt(() -> subject.requestRefresh(context));
            assertThat(resolutionStarted.await(1, TimeUnit.SECONDS)).isTrue();
            joinThread(workerThread.get());
            flushEdt();

            assertThat(visibilityChecked.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(rebuildCount).hasValue(0);
            assertThat(popupListener.get()).isNotNull();

            popupVisible.set(false);
            runOnEdt(() -> popupListener.get().popupMenuWillBecomeInvisible(new PopupMenuEvent(popupMenu)));

            assertThat(snapshotApplied.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(rebuildCount).hasValue(1);
        } finally {
            runOnEdt(subject::dispose);
            flushEdt();
        }
    }

    @Test
    @DisplayName("Disposal suppresses a model menu snapshot that is still resolving")
    void dispose_whenSnapshotResolutionIsActive_suppressesCompletion() throws Exception {
        ProviderMenuDataResolver dataResolver = mock(ProviderMenuDataResolver.class);
        ModelMenuStructureRebuildCoordinator rebuildCoordinator = mock(ModelMenuStructureRebuildCoordinator.class);
        ProviderRegistry providerRegistry = mock(ProviderRegistry.class);
        var resolutionStarted = new CountDownLatch(1);
        var releaseResolution = new CountDownLatch(1);
        var workerThread = new AtomicReference<Thread>();
        var rebuildCount = new AtomicInteger();
        when(providerRegistry.availableProviders()).thenReturn(emptyList());
        when(dataResolver.resolve(emptyList())).thenAnswer(invocation -> {
            workerThread.set(Thread.currentThread());
            resolutionStarted.countDown();
            awaitIgnoringInterrupt(releaseResolution);
            return emptyMenuData();
        });
        when(rebuildCoordinator.rebuild(any(), anyMap(), anyMap(), any(), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    rebuildCount.incrementAndGet();
                    return new ModelMenuStructureRebuildCoordinator.RebuildState(false, null);
                });
        var subject = subject(
                dataResolver,
                rebuildCoordinator,
                mock(ModelMenuSelectionDispatchCoordinator.class),
                providerRegistry
        );
        var context = callOnEdt(() -> context(false));

        try {
            runOnEdt(() -> subject.requestRefresh(context));
            assertThat(resolutionStarted.await(1, TimeUnit.SECONDS)).isTrue();

            runOnEdt(subject::dispose);
            releaseResolution.countDown();
            joinThread(workerThread.get());
            flushEdt();

            assertThat(rebuildCount).hasValue(0);
        } finally {
            releaseResolution.countDown();
            joinThread(workerThread.get());
            runOnEdt(subject::dispose);
            flushEdt();
        }
    }

    private MainFrameModelMenuCoordinator subject(
            ProviderMenuDataResolver dataResolver,
            ModelMenuStructureRebuildCoordinator rebuildCoordinator,
            ModelMenuSelectionDispatchCoordinator selectionDispatchCoordinator,
            ProviderRegistry providerRegistry
    ) {
        return new MainFrameModelMenuCoordinator(
                dataResolver,
                rebuildCoordinator,
                new ModelMenuStructureRebuildApplyCoordinator(),
                selectionDispatchCoordinator,
                new ModelMenuSelectionApplyCoordinator(),
                mock(ModelMenuSelectionChangeCoordinator.class),
                dirtyRefreshTriggerCoordinatorRunningActions(),
                mock(ProviderMenuAvailabilityRefreshDispatchCoordinator.class),
                mock(ProviderMenuIconResolver.class),
                providerRegistry
        );
    }

    private MainFrameModelMenuCoordinator.ModelMenuContext context(boolean dirty) {
        return context(dirty, new JMenu("Models"));
    }

    private MainFrameModelMenuCoordinator.ModelMenuContext context(boolean dirty, JMenu modelsMenu) {
        MainFrameBoundMenusState boundMenusState = new MainFrameBoundMenusState();
        boundMenusState.setModelsMenu(modelsMenu);
        return new MainFrameModelMenuCoordinator.ModelMenuContext(
                boundMenusState,
                new MainFrameMenuItemsState(),
                new MainFrameModelMenuState(dirty, null),
                () -> "OpenAI > gpt-4.1",
                ignored -> {
                }
        );
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

    private static ProviderMenuDataResolver.ProviderMenuData emptyMenuData() {
        return new ProviderMenuDataResolver.ProviderMenuData(
                emptyList(),
                emptyMap(),
                emptyMap(),
                emptyList()
        );
    }

    private void runOnEdt(ThrowingAction action) throws Exception {
        callOnEdt(() -> {
            action.run();
            return null;
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    private void flushEdt() throws Exception {
        runOnEdt(() -> {
        });
    }

    private void joinThread(Thread thread) throws InterruptedException {
        if (thread == null) {
            return;
        }
        thread.join(TimeUnit.SECONDS.toMillis(3));
        assertThat(thread.isAlive()).isFalse();
    }

    private void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
