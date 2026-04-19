package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FontMenuReadyCoordinatorTest {

    @Test
    @DisplayName("EnsureReady rebuilds when menu is not built and always syncs selection")
    void ensureReady_whenMenuNotBuilt_rebuildsAndSyncs() {
        var subject = new FontMenuReadyCoordinator();
        var rebuildCalls = new AtomicInteger();
        var syncCalls = new AtomicInteger();

        subject.ensureReady(false, rebuildCalls::incrementAndGet, syncCalls::incrementAndGet);

        assertThat(rebuildCalls.get()).isEqualTo(1);
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("EnsureReady skips rebuild when menu is already built and still syncs selection")
    void ensureReady_whenMenuBuilt_skipsRebuildAndSyncs() {
        var subject = new FontMenuReadyCoordinator();
        var rebuildCalls = new AtomicInteger();
        var syncCalls = new AtomicInteger();

        subject.ensureReady(true, rebuildCalls::incrementAndGet, syncCalls::incrementAndGet);

        assertThat(rebuildCalls.get()).isZero();
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("EnsureReady validates required callbacks")
    void ensureReady_whenCallbackMissing_throwsException() {
        var subject = new FontMenuReadyCoordinator();

        assertThatThrownBy(() -> subject.ensureReady(true, null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildFontMenuStructure must not be null");

        assertThatThrownBy(() -> subject.ensureReady(true, () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("syncFontMenuSelection must not be null");
    }
}
