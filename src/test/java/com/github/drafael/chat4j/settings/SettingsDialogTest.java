package com.github.drafael.chat4j.settings;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsDialogTest {

    @Test
    @DisplayName("Permanent closure stops the pending save drain before the next section")
    void saveParticipants_whenClosedDuringCurrentSave_doesNotStartNextParticipant() throws Exception {
        var firstSave = new CompletableFuture<Boolean>();
        var firstStarted = new CountDownLatch(1);
        var secondStarted = new AtomicBoolean();
        var active = new AtomicBoolean(true);
        AsyncPendingSettingsSaveParticipant first = participant(() -> {
            firstStarted.countDown();
            return firstSave;
        });
        AsyncPendingSettingsSaveParticipant second = participant(() -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture(true);
        });

        CompletableFuture<SettingsDialog.SavePendingResult> result = SettingsDialog.saveParticipants(
                List.of(first, second),
                active::get
        );
        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> active.set(false));

        firstSave.complete(true);

        assertThat(result.get(5, TimeUnit.SECONDS).saved()).isTrue();
        SwingUtilities.invokeAndWait(() -> {
        });
        assertThat(secondStarted).isFalse();
    }

    @Test
    @DisplayName("A failed section stops the save drain and can be retried")
    void saveParticipants_whenSectionFails_stopsAndSucceedsOnRetry() throws Exception {
        var firstCalls = new AtomicInteger();
        var secondCalls = new AtomicInteger();
        AsyncPendingSettingsSaveParticipant first = participant(
                () -> CompletableFuture.completedFuture(firstCalls.incrementAndGet() > 1),
                "General settings",
                "forced save failure"
        );
        AsyncPendingSettingsSaveParticipant second = participant(() -> {
            secondCalls.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        });

        SettingsDialog.SavePendingResult firstResult = SettingsDialog.saveParticipants(
                List.of(first, second),
                () -> true
        ).get(5, TimeUnit.SECONDS);

        assertThat(firstResult.saved()).isFalse();
        assertThat(firstResult.sectionName()).isEqualTo("General settings");
        assertThat(firstResult.message()).isEqualTo("forced save failure");
        assertThat(secondCalls).hasValue(0);

        SettingsDialog.SavePendingResult retryResult = SettingsDialog.saveParticipants(
                List.of(first, second),
                () -> true
        ).get(5, TimeUnit.SECONDS);

        assertThat(retryResult.saved()).isTrue();
        assertThat(firstCalls).hasValue(2);
        assertThat(secondCalls).hasValue(1);
    }

    @Test
    @DisplayName("An exceptional section save reports its error and stops later sections")
    void saveParticipants_whenSectionFailsExceptionally_reportsFailure() throws Exception {
        var secondStarted = new AtomicBoolean();
        AsyncPendingSettingsSaveParticipant first = participant(
                () -> CompletableFuture.failedFuture(new IllegalStateException("forced exceptional failure")),
                "Appearance settings",
                ""
        );
        AsyncPendingSettingsSaveParticipant second = participant(() -> {
            secondStarted.set(true);
            return CompletableFuture.completedFuture(true);
        });

        SettingsDialog.SavePendingResult result = SettingsDialog.saveParticipants(
                List.of(first, second),
                () -> true
        ).get(5, TimeUnit.SECONDS);

        assertThat(result.saved()).isFalse();
        assertThat(result.sectionName()).isEqualTo("Appearance settings");
        assertThat(result.message()).isEqualTo("forced exceptional failure");
        assertThat(secondStarted).isFalse();
    }

    @Test
    @DisplayName("Settings-originated exit admits mandatory cleanup before returning its hard deadline")
    void admitSettingsOriginatedExit_whenRestartRequested_invokesAdmissionWithSettingsDeadline() {
        var admittedDeadline = new AtomicLong();

        long hardDeadline = SettingsDialog.admitSettingsOriginatedExit(
                () -> 1_000L,
                settingsDeadline -> {
                    admittedDeadline.set(settingsDeadline);
                    return settingsDeadline + 500L;
                }
        );

        assertThat(admittedDeadline).hasValue(1_000L + TimeUnit.MILLISECONDS.toNanos(2_000));
        assertThat(hardDeadline).isEqualTo(admittedDeadline.get() + 500L);
    }

    @Test
    @DisplayName("External exit continues after save failure but ordinary Close remains open")
    void abortCloseAfterSaveFailure_whenExitAlreadyAdmitted_continuesOnlyExternalExit() {
        assertThat(SettingsDialog.abortCloseAfterSaveFailure(true, false)).isFalse();
        assertThat(SettingsDialog.abortCloseAfterSaveFailure(false, false)).isTrue();
        assertThat(SettingsDialog.abortCloseAfterSaveFailure(false, true)).isFalse();
    }

    private AsyncPendingSettingsSaveParticipant participant(AsyncSave save) {
        return participant(save, "Settings", "");
    }

    private AsyncPendingSettingsSaveParticipant participant(AsyncSave save, String sectionName, String lastSaveError) {
        return new AsyncPendingSettingsSaveParticipant() {
            @Override
            public CompletableFuture<Boolean> savePendingChangesAsync() {
                return save.run();
            }

            @Override
            public String lastSaveError() {
                return lastSaveError;
            }

            @Override
            public String settingsSectionName() {
                return sectionName;
            }
        };
    }

    @FunctionalInterface
    private interface AsyncSave {
        CompletableFuture<Boolean> run();
    }
}
