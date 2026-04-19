package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderMenuReadyDispatchCoordinatorTest {

    @Test
    @DisplayName("EnsureReady delegates to ensureReady action with provided state and callbacks")
    void ensureReady_whenCalled_delegatesToEnsureReadyAction() {
        var capturedDirty = new AtomicBoolean();
        var capturedRebuild = new AtomicReference<Runnable>();
        var capturedRefresh = new AtomicReference<Runnable>();
        var capturedSync = new AtomicReference<Runnable>();

        var subject = new ProviderMenuReadyDispatchCoordinator((
                modelsMenuDirty,
                rebuildModelsMenuStructure,
                refreshLocalProviderAvailabilityInMenu,
                syncModelsMenuSelection
        ) -> {
            capturedDirty.set(modelsMenuDirty);
            capturedRebuild.set(rebuildModelsMenuStructure);
            capturedRefresh.set(refreshLocalProviderAvailabilityInMenu);
            capturedSync.set(syncModelsMenuSelection);
        });

        Runnable rebuild = () -> {
        };
        Runnable refresh = () -> {
        };
        Runnable sync = () -> {
        };

        subject.ensureReady(true, rebuild, refresh, sync);

        assertThat(capturedDirty.get()).isTrue();
        assertThat(capturedRebuild.get()).isSameAs(rebuild);
        assertThat(capturedRefresh.get()).isSameAs(refresh);
        assertThat(capturedSync.get()).isSameAs(sync);
    }

    @Test
    @DisplayName("EnsureReady validates callbacks and constructor dependency")
    void ensureReady_whenInvalidInput_throwsException() {
        var subject = new ProviderMenuReadyDispatchCoordinator((
                modelsMenuDirty,
                rebuildModelsMenuStructure,
                refreshLocalProviderAvailabilityInMenu,
                syncModelsMenuSelection
        ) -> {
        });

        assertThatThrownBy(() -> subject.ensureReady(true, null, () -> {
        }, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildModelsMenuStructure must not be null");

        assertThatThrownBy(() -> new ProviderMenuReadyDispatchCoordinator(
                (ProviderMenuReadyDispatchCoordinator.EnsureReadyAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensureReadyAction must not be null");
    }
}
