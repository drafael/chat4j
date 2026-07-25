package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsDialogCoordinatorTest {

    @Test
    @DisplayName("Open reuses visible dialog and brings it to front")
    void open_whenDialogIsAlreadyVisible_reusesDialogAndFocusesIt() {
        var subject = new SettingsDialogCoordinator();
        var createdDialogs = new ArrayList<FakeDialogHandle>();

        subject.open(() -> createDialog(createdDialogs), () -> {
        });
        subject.open(() -> createDialog(createdDialogs), () -> {
        });

        assertThat(createdDialogs).hasSize(1);
        FakeDialogHandle dialog = createdDialogs.getFirst();
        assertThat(dialog.visibleCalls.get()).isEqualTo(1);
        assertThat(dialog.toFrontCalls.get()).isEqualTo(1);
        assertThat(dialog.requestFocusCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Open re-shows hidden displayable dialog without creating a new one")
    void open_whenDialogIsDisplayableButHidden_showsExistingDialog() {
        var subject = new SettingsDialogCoordinator();
        var createdDialogs = new ArrayList<FakeDialogHandle>();

        subject.open(() -> createDialog(createdDialogs), () -> {
        });
        FakeDialogHandle dialog = createdDialogs.getFirst();
        dialog.visible = false;

        subject.open(() -> createDialog(createdDialogs), () -> {
        });

        assertThat(createdDialogs).hasSize(1);
        assertThat(dialog.visibleCalls.get()).isEqualTo(2);
        assertThat(dialog.toFrontCalls.get()).isZero();
        assertThat(dialog.requestFocusCalls.get()).isZero();
    }

    @Test
    @DisplayName("Close callback clears tracked dialog and executes close hook")
    void open_whenDialogCloses_runsCloseHookAndAllowsNewDialogCreation() {
        var subject = new SettingsDialogCoordinator();
        var createdDialogs = new ArrayList<FakeDialogHandle>();
        var closeHookCalls = new AtomicInteger();

        subject.open(() -> createDialog(createdDialogs), closeHookCalls::incrementAndGet);
        FakeDialogHandle firstDialog = createdDialogs.getFirst();
        firstDialog.displayable = false;
        firstDialog.fireClosed();

        subject.open(() -> createDialog(createdDialogs), closeHookCalls::incrementAndGet);

        assertThat(createdDialogs).hasSize(2);
        assertThat(closeHookCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Application exit drains the tracked Settings dialog")
    void requestApplicationExit_whenDialogIsTracked_routesExitThroughDialog() {
        var subject = new SettingsDialogCoordinator();
        var createdDialogs = new ArrayList<FakeDialogHandle>();
        var fallbackCalls = new AtomicInteger();

        subject.open(() -> createDialog(createdDialogs), () -> {
        });
        subject.requestApplicationExit(fallbackCalls::incrementAndGet);

        assertThat(createdDialogs.getFirst().applicationExitCalls).hasValue(1);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    @DisplayName("Application exit proceeds directly when the tracked dialog is already closed")
    void requestApplicationExit_whenTrackedDialogIsClosed_runsFallback() {
        var subject = new SettingsDialogCoordinator();
        var createdDialogs = new ArrayList<FakeDialogHandle>();
        var fallbackCalls = new AtomicInteger();

        subject.open(() -> createDialog(createdDialogs), () -> {
        });
        createdDialogs.getFirst().displayable = false;
        subject.requestApplicationExit(fallbackCalls::incrementAndGet);

        assertThat(createdDialogs.getFirst().applicationExitCalls).hasValue(0);
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    @DisplayName("A delayed close callback cannot untrack a replacement dialog")
    void open_whenOldCloseArrivesAfterReplacement_keepsReplacementTracked() {
        var subject = new SettingsDialogCoordinator();
        var createdDialogs = new ArrayList<FakeDialogHandle>();

        subject.open(() -> createDialog(createdDialogs), () -> {
        });
        FakeDialogHandle firstDialog = createdDialogs.getFirst();
        firstDialog.displayable = false;

        subject.open(() -> createDialog(createdDialogs), () -> {
        });
        firstDialog.fireClosed();
        subject.open(() -> createDialog(createdDialogs), () -> {
        });

        assertThat(createdDialogs).hasSize(2);
        assertThat(createdDialogs.get(1).toFrontCalls).hasValue(1);
    }

    private FakeDialogHandle createDialog(List<FakeDialogHandle> createdDialogs) {
        FakeDialogHandle dialog = new FakeDialogHandle();
        createdDialogs.add(dialog);
        return dialog;
    }

    private static class FakeDialogHandle implements SettingsDialogCoordinator.DialogHandle {

        private final AtomicInteger visibleCalls = new AtomicInteger();
        private final AtomicInteger toFrontCalls = new AtomicInteger();
        private final AtomicInteger requestFocusCalls = new AtomicInteger();
        private final AtomicInteger applicationExitCalls = new AtomicInteger();
        private final AtomicReference<Runnable> onClosed = new AtomicReference<>();
        private boolean displayable = true;
        private boolean visible;

        @Override
        public boolean isDisplayable() {
            return displayable;
        }

        @Override
        public boolean isVisible() {
            return visible;
        }

        @Override
        public void toFront() {
            toFrontCalls.incrementAndGet();
        }

        @Override
        public void requestFocus() {
            requestFocusCalls.incrementAndGet();
        }

        @Override
        public void setVisible(boolean visible) {
            this.visible = visible;
            visibleCalls.incrementAndGet();
        }

        @Override
        public void onClosed(Runnable callback) {
            onClosed.set(callback);
        }

        @Override
        public void requestApplicationExit() {
            applicationExitCalls.incrementAndGet();
        }

        private void fireClosed() {
            Runnable callback = onClosed.get();
            if (callback != null) {
                callback.run();
            }
        }
    }
}
