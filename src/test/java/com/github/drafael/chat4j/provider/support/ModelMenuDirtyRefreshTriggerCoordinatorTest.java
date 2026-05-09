package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMenuDirtyRefreshTriggerCoordinatorTest {

    @Test
    @DisplayName("Trigger delegates models menu and callbacks to trigger action")
    void trigger_whenCalled_delegatesToTriggerAction() {
        JMenu modelsMenu = new JMenu("Model");
        var capturedMenu = new AtomicReference<JMenu>();
        var markCalls = new AtomicInteger();
        var readyCalls = new AtomicInteger();

        var subject = new ModelMenuDirtyRefreshTriggerCoordinator((menu, markDirty, ensureReady) -> {
            capturedMenu.set(menu);
            markDirty.run();
            ensureReady.run();
        });

        subject.trigger(modelsMenu, markCalls::incrementAndGet, readyCalls::incrementAndGet);

        assertThat(capturedMenu.get()).isSameAs(modelsMenu);
        assertThat(markCalls.get()).isEqualTo(1);
        assertThat(readyCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Trigger validates required callbacks and constructor dependency")
    void trigger_whenInvalidInput_throwsException() {
        var subject = new ModelMenuDirtyRefreshTriggerCoordinator((menu, markDirty, ensureReady) -> {
        });

        assertThatThrownBy(() -> subject.trigger(new JMenu("Model"), null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("markModelsMenuDirty");

        assertThatThrownBy(() -> subject.trigger(new JMenu("Model"), () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensureModelsMenuReady");

        assertThatThrownBy(() -> new ModelMenuDirtyRefreshTriggerCoordinator(
                (ModelMenuDirtyRefreshTriggerCoordinator.TriggerAction) null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("triggerAction");
    }
}
