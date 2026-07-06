package com.github.drafael.chat4j.stt.provider.vosk;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class VoskModelManagementServiceTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Remote catalog rows without expected size are not downloadable")
    void refreshAsync_whenCatalogEntryHasZeroSize_marksRowUnavailableForDownload() throws Exception {
        var repo = new SettingsRepository(tempDir.resolve("settings.properties"));
        new VoskModelCatalogCache(repo).saveRawJson("""
                [
                  {
                    "name": "vosk-model-small-test-0.1",
                    "lang": "en",
                    "lang_text": "English",
                    "type": "small",
                    "url": "https://alphacephei.com/vosk/models/vosk-model-small-test-0.1.zip",
                    "md5": "0123456789abcdef0123456789abcdef",
                    "size": 0,
                    "size_text": "unknown"
                  }
                ]
                """);
        var subject = new VoskModelManagementService(repo, tempDir.resolve("models"), tempDir.resolve("temp"));
        try {
            subject.refreshAsync();
            waitUntil(() -> !subject.snapshot().rows().isEmpty());

            VoskLocalModelRow row = subject.snapshot().rows().getFirst();
            assertThat(row.downloadable()).isFalse();
            assertThat(row.actionStatus()).contains("download metadata");
        } finally {
            subject.close();
        }
    }

    private void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.met() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.met()).isTrue();
    }

    @FunctionalInterface
    private interface Condition {
        boolean met();
    }
}
