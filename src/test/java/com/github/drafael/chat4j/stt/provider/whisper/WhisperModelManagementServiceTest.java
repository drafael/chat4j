package com.github.drafael.chat4j.stt.provider.whisper;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WhisperModelManagementServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Closing from a model service listener does not deadlock the worker")
    void close_whenCalledFromListener_returnsWithoutWaitingForItself() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        var subject = new WhisperModelManagementService(repo, tempDir.resolve("models"), tempDir.resolve("temp"));
        var closeReturned = new CountDownLatch(1);
        try {
            subject.addListener(snapshot -> {
                if (!snapshot.operationInProgress() && !snapshot.rows().isEmpty()) {
                    subject.close();
                    closeReturned.countDown();
                }
            });

            subject.refreshAsync(false);

            assertThat(closeReturned.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            subject.close();
        }
    }
}
