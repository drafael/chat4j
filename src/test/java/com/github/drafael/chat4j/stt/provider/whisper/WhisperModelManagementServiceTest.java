package com.github.drafael.chat4j.stt.provider.whisper;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhisperModelManagementServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Closed Whisper model management rejects new work and listeners")
    void close_whenWorkRequested_rejectsOperation() {
        var repo = new SettingsRepository(tempDir.resolve("settings-closed.properties"));
        var subject = new WhisperModelManagementService(
                repo,
                tempDir.resolve("models-closed"),
                tempDir.resolve("temp-closed")
        );

        subject.close();

        assertThatThrownBy(() -> subject.refreshAsync(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
        assertThatThrownBy(subject::refreshAsync)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
        assertThatThrownBy(() -> subject.downloadAsync("model"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
        assertThatThrownBy(() -> subject.deleteAsync("model"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
        assertThatThrownBy(() -> subject.selectModel("model"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
        assertThatThrownBy(() -> subject.selectModelAsync("model"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
        assertThatThrownBy(subject::clearSelection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
        assertThatThrownBy(subject::cancelActiveOperation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
        assertThatThrownBy(subject::validateSelectedNow)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
        assertThatThrownBy(() -> subject.addListener(snapshot -> {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Whisper.cpp model management is closed.");
    }

    @Test
    @DisplayName("A failing Whisper listener does not prevent refresh completion")
    void refreshAsync_whenListenerFails_completesRefresh() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings-listener-failure.properties"));
        var subject = new WhisperModelManagementService(
                repo,
                tempDir.resolve("models-listener-failure"),
                tempDir.resolve("temp-listener-failure")
        );
        var refreshCompleted = new CountDownLatch(1);
        try {
            subject.addListener(snapshot -> {
                if (snapshot.operationInProgress()) {
                    throw new IllegalStateException("listener failed");
                }
            });
            subject.addListener(snapshot -> {
                if (!snapshot.operationInProgress() && !snapshot.rows().isEmpty()) {
                    refreshCompleted.countDown();
                }
            });

            subject.refreshAsync(false);

            assertThat(refreshCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Closing from a model service listener does not deadlock the worker")
    void close_whenCalledFromListener_returnsWithoutWaitingForItself() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        var subject = new WhisperModelManagementService(repo, tempDir.resolve("models"), tempDir.resolve("temp"));
        var closeReturned = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        try {
            subject.addListener(snapshot -> {
                if (!snapshot.operationInProgress() && !snapshot.rows().isEmpty()) {
                    worker.set(Thread.currentThread());
                    subject.close();
                    closeReturned.countDown();
                }
            });

            subject.refreshAsync(false);

            assertThat(closeReturned.await(2, TimeUnit.SECONDS)).isTrue();
            joinWorker(worker.get());
        } finally {
            subject.close();
            interruptAndJoin(worker.get());
        }
    }

    private void joinWorker(Thread worker) throws InterruptedException {
        assertThat(worker).isNotNull();
        worker.join(2_000);
        assertThat(worker.isAlive()).isFalse();
    }

    private void interruptAndJoin(Thread worker) throws InterruptedException {
        if (worker == null) {
            return;
        }
        worker.interrupt();
        worker.join(2_000);
        assertThat(worker.isAlive()).isFalse();
    }
}
