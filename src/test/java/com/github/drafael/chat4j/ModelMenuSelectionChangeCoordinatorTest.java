package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMenuSelectionChangeCoordinatorTest {

    @Test
    @DisplayName("onSelectedModelChanged syncs selection when menu exists and is not dirty")
    void onSelectedModelChanged_whenMenuReady_syncsSelection() {
        var subject = new ModelMenuSelectionChangeCoordinator();
        var syncCalls = new AtomicInteger();

        boolean handled = subject.onSelectedModelChanged(new JMenu("Model"), false, syncCalls::incrementAndGet);

        assertThat(handled).isTrue();
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("onSelectedModelChanged skips sync when menu is missing or dirty")
    void onSelectedModelChanged_whenMenuMissingOrDirty_skipsSync() {
        var subject = new ModelMenuSelectionChangeCoordinator();
        var syncCalls = new AtomicInteger();

        boolean handledMissing = subject.onSelectedModelChanged(null, false, syncCalls::incrementAndGet);
        boolean handledDirty = subject.onSelectedModelChanged(new JMenu("Model"), true, syncCalls::incrementAndGet);

        assertThat(handledMissing).isFalse();
        assertThat(handledDirty).isFalse();
        assertThat(syncCalls.get()).isZero();
    }

    @Test
    @DisplayName("onSelectedModelChanged validates sync callback")
    void onSelectedModelChanged_whenCallbackMissing_throwsException() {
        var subject = new ModelMenuSelectionChangeCoordinator();

        assertThatThrownBy(() -> subject.onSelectedModelChanged(new JMenu("Model"), false, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("syncModelsMenuSelection must not be null");
    }
}
