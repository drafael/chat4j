package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeMenuReadyDispatchCoordinatorTest {

    @Test
    @DisplayName("EnsureReady delegates built flag and callbacks to action")
    void ensureReady_whenCalled_delegatesToAction() {
        var capturedBuilt = new AtomicBoolean();
        var capturedRebuild = new AtomicReference<Runnable>();
        var capturedSync = new AtomicReference<Runnable>();

        var subject = new ThemeMenuReadyDispatchCoordinator((themesMenuBuilt, rebuildThemesMenuStructure, syncThemeMenuSelection) -> {
            capturedBuilt.set(themesMenuBuilt);
            capturedRebuild.set(rebuildThemesMenuStructure);
            capturedSync.set(syncThemeMenuSelection);
        });

        Runnable rebuild = () -> {
        };
        Runnable sync = () -> {
        };

        subject.ensureReady(true, rebuild, sync);

        assertThat(capturedBuilt.get()).isTrue();
        assertThat(capturedRebuild.get()).isSameAs(rebuild);
        assertThat(capturedSync.get()).isSameAs(sync);
    }

    @Test
    @DisplayName("EnsureReady validates callbacks and constructor dependency")
    void ensureReady_whenInvalidInput_throwsException() {
        var subject = new ThemeMenuReadyDispatchCoordinator((themesMenuBuilt, rebuildThemesMenuStructure, syncThemeMenuSelection) -> {
        });

        assertThatThrownBy(() -> subject.ensureReady(false, null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("rebuildThemesMenuStructure must not be null");

        assertThatThrownBy(() -> subject.ensureReady(false, () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("syncThemeMenuSelection must not be null");

        assertThatThrownBy(() -> new ThemeMenuReadyDispatchCoordinator(
                (ThemeMenuReadyDispatchCoordinator.EnsureReadyAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensureReadyAction must not be null");
    }
}
