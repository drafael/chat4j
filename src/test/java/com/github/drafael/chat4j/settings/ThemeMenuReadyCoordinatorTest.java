package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeMenuReadyCoordinatorTest {

    @Test
    @DisplayName("EnsureReady rebuilds when menu is not built and always syncs selection")
    void ensureReady_whenMenuNotBuilt_rebuildsAndSyncs() {
        var subject = new ThemeMenuReadyCoordinator();
        AtomicInteger rebuildCalls = new AtomicInteger();
        AtomicInteger syncCalls = new AtomicInteger();

        subject.ensureReady(false, rebuildCalls::incrementAndGet, syncCalls::incrementAndGet);

        assertThat(rebuildCalls.get()).isEqualTo(1);
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("EnsureReady skips rebuild when menu is already built and still syncs selection")
    void ensureReady_whenMenuBuilt_skipsRebuildAndSyncs() {
        var subject = new ThemeMenuReadyCoordinator();
        AtomicInteger rebuildCalls = new AtomicInteger();
        AtomicInteger syncCalls = new AtomicInteger();

        subject.ensureReady(true, rebuildCalls::incrementAndGet, syncCalls::incrementAndGet);

        assertThat(rebuildCalls.get()).isZero();
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("EnsureReady validates required callbacks")
    void ensureReady_whenCallbackMissing_throwsException() {
        var subject = new ThemeMenuReadyCoordinator();

        assertThatThrownBy(() -> subject.ensureReady(true, null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildThemesMenuStructure must not be null");

        assertThatThrownBy(() -> subject.ensureReady(true, () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("syncThemeMenuSelection must not be null");
    }
}
