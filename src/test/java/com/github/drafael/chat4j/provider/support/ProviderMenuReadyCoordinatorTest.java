package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderMenuReadyCoordinatorTest {

    @Test
    @DisplayName("EnsureReady rebuilds when model menu is dirty and always refreshes and syncs")
    void ensureReady_whenMenuDirty_rebuildsRefreshesAndSyncs() {
        var subject = new ProviderMenuReadyCoordinator();
        var rebuildCalls = new AtomicInteger();
        var refreshCalls = new AtomicInteger();
        var syncCalls = new AtomicInteger();

        subject.ensureReady(
                true,
                rebuildCalls::incrementAndGet,
                refreshCalls::incrementAndGet,
                syncCalls::incrementAndGet
        );

        assertThat(rebuildCalls.get()).isEqualTo(1);
        assertThat(refreshCalls.get()).isEqualTo(1);
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("EnsureReady skips rebuild when model menu is clean and still refreshes and syncs")
    void ensureReady_whenMenuClean_skipsRebuildAndRefreshesAndSyncs() {
        var subject = new ProviderMenuReadyCoordinator();
        var rebuildCalls = new AtomicInteger();
        var refreshCalls = new AtomicInteger();
        var syncCalls = new AtomicInteger();

        subject.ensureReady(
                false,
                rebuildCalls::incrementAndGet,
                refreshCalls::incrementAndGet,
                syncCalls::incrementAndGet
        );

        assertThat(rebuildCalls.get()).isZero();
        assertThat(refreshCalls.get()).isEqualTo(1);
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("EnsureReady validates required callbacks")
    void ensureReady_whenCallbackMissing_throwsException() {
        var subject = new ProviderMenuReadyCoordinator();

        assertThatThrownBy(() -> subject.ensureReady(true, null, () -> {
        }, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildModelsMenuStructure must not be null");

        assertThatThrownBy(() -> subject.ensureReady(true, () -> {
        }, null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refreshLocalProviderAvailabilityInMenu must not be null");

        assertThatThrownBy(() -> subject.ensureReady(true, () -> {
        }, () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("syncModelsMenuSelection must not be null");
    }
}
