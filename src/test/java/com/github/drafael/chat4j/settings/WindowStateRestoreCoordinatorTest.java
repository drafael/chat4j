package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowStateRestoreCoordinatorTest {

    @Test
    @DisplayName("Restore applies saved bounds when visible stored bounds are available")
    void restore_whenSavedBoundsAvailable_appliesSavedBounds() {
        Rectangle visibleBounds = new Rectangle(0, 0, 1920, 1080);
        Rectangle savedBounds = new Rectangle(100, 120, 1000, 700);
        var capturedVisibleBounds = new AtomicReference<Rectangle>();
        var appliedBounds = new AtomicReference<Rectangle>();
        var defaultCalls = new AtomicInteger();

        var subject = new WindowStateRestoreCoordinator(
                bounds -> {
                    capturedVisibleBounds.set(bounds);
                    return Optional.of(savedBounds);
                },
                () -> visibleBounds
        );

        boolean restored = subject.restore(appliedBounds::set, defaultCalls::incrementAndGet);

        assertThat(restored).isTrue();
        assertThat(capturedVisibleBounds.get()).isEqualTo(visibleBounds);
        assertThat(appliedBounds.get()).isEqualTo(savedBounds);
        assertThat(defaultCalls.get()).isZero();
    }

    @Test
    @DisplayName("Restore applies default window state when no saved visible bounds are available")
    void restore_whenSavedBoundsUnavailable_appliesDefaultWindowState() {
        var appliedBoundsCalls = new AtomicInteger();
        var defaultCalls = new AtomicInteger();

        var subject = new WindowStateRestoreCoordinator(
                bounds -> Optional.empty(),
                () -> new Rectangle(0, 0, 1920, 1080)
        );

        boolean restored = subject.restore(bounds -> appliedBoundsCalls.incrementAndGet(), defaultCalls::incrementAndGet);

        assertThat(restored).isFalse();
        assertThat(appliedBoundsCalls.get()).isZero();
        assertThat(defaultCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Restore validates required callbacks")
    void restore_whenCallbackMissing_throwsException() {
        var subject = new WindowStateRestoreCoordinator(
                bounds -> Optional.empty(),
                () -> new Rectangle(0, 0, 1920, 1080)
        );

        assertThatThrownBy(() -> subject.restore(null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("applyBounds must not be null");

        assertThatThrownBy(() -> subject.restore(bounds -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("applyDefaultWindowState must not be null");
    }

    @Test
    @DisplayName("Constructor validates required collaborators")
    void constructor_whenDependencyMissing_throwsException() {
        assertThatThrownBy(() -> new WindowStateRestoreCoordinator(
                null,
                () -> new Rectangle(0, 0, 1920, 1080)
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("storedBoundsLoader must not be null");

        assertThatThrownBy(() -> new WindowStateRestoreCoordinator(
                bounds -> Optional.empty(),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("visibleScreenBoundsResolver must not be null");
    }
}
