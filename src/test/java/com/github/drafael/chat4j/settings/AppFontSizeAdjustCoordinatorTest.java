package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppFontSizeAdjustCoordinatorTest {

    @Test
    @DisplayName("Adjust resolves adjusted size and applies it")
    void adjust_whenCalled_resolvesAndAppliesSize() {
        var capturedIncrease = new AtomicReference<Boolean>();
        var capturedCurrentSize = new AtomicInteger();
        var subject = new AppFontSizeAdjustCoordinator((sizeOptions, currentSize, increase) -> {
            capturedIncrease.set(increase);
            capturedCurrentSize.set(currentSize);
            return 18;
        });
        var appliedSize = new AtomicInteger(-1);

        int adjustedSize = subject.adjust(
                true,
                () -> new int[]{12, 14, 16, 18},
                () -> 16,
                appliedSize::set
        );

        assertThat(adjustedSize).isEqualTo(18);
        assertThat(appliedSize.get()).isEqualTo(18);
        assertThat(capturedIncrease.get()).isTrue();
        assertThat(capturedCurrentSize.get()).isEqualTo(16);
    }

    @Test
    @DisplayName("Adjust validates required arguments and constructor dependency")
    void adjust_whenInvalidInput_throwsException() {
        var subject = new AppFontSizeAdjustCoordinator((sizeOptions, currentSize, increase) -> currentSize);

        assertThatThrownBy(() -> subject.adjust(
                false,
                null,
                () -> 14,
                size -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sizeOptionsSupplier must not be null");

        assertThatThrownBy(() -> subject.adjust(
                false,
                () -> new int[]{12, 14},
                () -> 14,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sizeApplier must not be null");

        assertThatThrownBy(() -> new AppFontSizeAdjustCoordinator(
                (AppFontSizeAdjustCoordinator.ResolveAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resolveAction must not be null");
    }
}
