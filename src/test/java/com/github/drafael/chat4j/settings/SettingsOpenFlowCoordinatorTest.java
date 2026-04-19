package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsOpenFlowCoordinatorTest {

    @Test
    @DisplayName("Open dispatches through EDT coordinator and opens dialog flow")
    void open_whenCalled_dispatchesAndOpensDialogFlow() {
        var capturedOnEdt = new AtomicBoolean(true);
        var scheduleCalls = new AtomicInteger();
        var dialogOpenCalls = new AtomicInteger();
        var dialogClosedCallbacks = new AtomicInteger();

        var subject = new SettingsOpenFlowCoordinator(
                (onEdt, scheduleOpenOnEdt, openOnEdt) -> {
                    capturedOnEdt.set(onEdt);
                    scheduleOpenOnEdt.run();
                    openOnEdt.run();
                },
                (dialogFactory, onDialogClosed) -> {
                    dialogOpenCalls.incrementAndGet();
                    dialogFactory.create();
                    onDialogClosed.run();
                }
        );

        subject.open(
                false,
                scheduleCalls::incrementAndGet,
                SettingsOpenFlowCoordinatorTest::dialogHandle,
                dialogClosedCallbacks::incrementAndGet
        );

        assertThat(capturedOnEdt.get()).isFalse();
        assertThat(scheduleCalls.get()).isEqualTo(1);
        assertThat(dialogOpenCalls.get()).isEqualTo(1);
        assertThat(dialogClosedCallbacks.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Open validates required arguments and dependencies")
    void open_whenInvalidInput_throwsException() {
        var subject = new SettingsOpenFlowCoordinator(
                (onEdt, scheduleOpenOnEdt, openOnEdt) -> {
                },
                (dialogFactory, onDialogClosed) -> {
                }
        );

        assertThatThrownBy(() -> subject.open(true, null, SettingsOpenFlowCoordinatorTest::dialogHandle, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("scheduleOpenOnEdt must not be null");

        assertThatThrownBy(() -> subject.open(true, () -> {
        }, null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("dialogFactory must not be null");

        assertThatThrownBy(() -> new SettingsOpenFlowCoordinator(
                (SettingsOpenFlowCoordinator.OpenDispatchAction) null,
                (dialogFactory, onDialogClosed) -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("openDispatchAction must not be null");
    }

    private static SettingsDialogCoordinator.DialogHandle dialogHandle() {
        return new SettingsDialogCoordinator.DialogHandle() {
            @Override
            public boolean isDisplayable() {
                return true;
            }

            @Override
            public boolean isVisible() {
                return false;
            }

            @Override
            public void toFront() {
            }

            @Override
            public void requestFocus() {
            }

            @Override
            public void setVisible(boolean visible) {
            }

            @Override
            public void onClosed(Runnable callback) {
            }
        };
    }
}
