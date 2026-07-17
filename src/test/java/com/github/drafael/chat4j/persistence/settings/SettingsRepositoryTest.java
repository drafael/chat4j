package com.github.drafael.chat4j.persistence.settings;

import com.github.drafael.chat4j.persistence.model.ModelFavoritesService;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("findByPrefix returns matching settings rows in key order")
    void findByPrefix_whenKeysSharePrefix_returnsMatchingRows() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));
        subject.put("chat4j.models.favorite.ollama::qwen3%3A14b", "true");
        subject.put("chat4j.models.favorite.openai::gpt-4.1", "true");
        subject.put("chat4j.ui.theme.name", "GitHub");

        Map<String, String> rows = subject.findByPrefix("chat4j.models.favorite.");

        assertThat(rows.keySet()).containsExactly(
                "chat4j.models.favorite.ollama::qwen3%3A14b",
                "chat4j.models.favorite.openai::gpt-4.1"
        );
        assertThat(rows.values()).containsOnly("true");
    }

    @Test
    @DisplayName("Bounded prefix discovery rejects incomplete settings images")
    void findByPrefix_whenMatchingEntriesExceedLimit_rejectsPartialResult() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));
        subject.put("chat4j.catalog.first", "one");
        subject.put("chat4j.catalog.second", "two");

        assertThatThrownBy(() -> subject.findByPrefix("chat4j.catalog.", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too many entries");
    }

    @Test
    @DisplayName("Prefix batch diagnostics never expose settings values")
    void updatePrefixBatch_whenValuesAreSensitive_usesSanitizedToString() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));
        subject.put("chat4j.example", "secret-value");

        subject.updatePrefixBatch("chat4j.", 10, batch ->
                assertThat(batch.toString()).isEqualTo("PrefixBatchUpdate[valueCount=1]"));
    }

    @Test
    @DisplayName("Model favorites persisted in settings are restored after service restart")
    void modelFavoritesService_whenRestarted_restoresPersistedFavorites() {
        Path settingsFile = tempDir.resolve("chat4j.properties");
        var settingsRepo = new SettingsRepository(settingsFile);
        var writer = new ModelFavoritesService(settingsRepo);
        writer.setFavorite("Anthropic", "claude-sonnet-4-6", true);

        var subject = new ModelFavoritesService(new SettingsRepository(settingsFile));
        subject.primeFromSettings();

        assertThat(subject.isFavorite("Anthropic", "claude-sonnet-4-6")).isTrue();
    }

    @Test
    @DisplayName("File failures use settings storage exceptions")
    void put_whenSettingsPathIsDirectory_throwsSettingsStorageException() {
        var subject = new SettingsRepository(tempDir);

        assertThatThrownBy(() -> subject.put("chat4j.ui.theme.name", "GitHub"))
                .isInstanceOf(SettingsStorageException.class)
                .hasMessageContaining("settings file");
    }

    @Test
    @DisplayName("Batch updates persist correlated values atomically")
    void updateBatch_whenMultipleValuesProvided_persistsAllValues() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));

        subject.updateBatch(batch -> {
            batch.put("chat4j.stt.provider", "groq");
            batch.put("chat4j.stt.groq.model.id", "whisper-large-v3-turbo");
            batch.put("chat4j.stt.groq.model.label", "Whisper Large v3 Turbo");
        });

        assertThat(subject.get("chat4j.stt.provider")).contains("groq");
        assertThat(subject.get("chat4j.stt.groq.model.id")).contains("whisper-large-v3-turbo");
        assertThat(subject.get("chat4j.stt.groq.model.label")).contains("Whisper Large v3 Turbo");
    }

    @Test
    @DisplayName("Conditional batch updates skip persistence when condition turns false")
    void updateBatchIf_whenConditionFalse_skipsUpdate() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));
        subject.put("chat4j.existing", "kept");
        AtomicBoolean current = new AtomicBoolean(false);

        boolean updated = subject.updateBatchIf(current::get, batch -> batch.put("chat4j.new", "skipped"));

        assertThat(updated).isFalse();
        assertThat(subject.get("chat4j.existing")).contains("kept");
        assertThat(subject.get("chat4j.new")).isEmpty();
    }

    @Test
    @DisplayName("Conditional batch updates evaluate the condition once after loading current settings")
    void updateBatchIf_whenConditionTrue_evaluatesConditionOnce() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));
        AtomicInteger checks = new AtomicInteger();

        boolean updated = subject.updateBatchIf(() -> checks.incrementAndGet() == 1, batch -> batch.put("chat4j.new", "saved"));

        assertThat(updated).isTrue();
        assertThat(checks).hasValue(1);
        assertThat(subject.get("chat4j.new")).contains("saved");
    }

    @Test
    @DisplayName("Concurrent same-file repository instances serialize batch updates")
    void updateBatch_whenMultipleInstancesTargetSameFile_preservesBothUpdates() throws Exception {
        Path settingsFile = tempDir.resolve("shared.properties");
        var first = new SettingsRepository(settingsFile);
        var second = new SettingsRepository(settingsFile);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        AtomicBoolean secondEntered = new AtomicBoolean(false);

        CompletableFuture<Void> firstUpdate = CompletableFuture.runAsync(() -> first.updateBatch(batch -> {
            firstEntered.countDown();
            await(releaseFirst);
            batch.put("chat4j.first", "one");
        }));
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> secondUpdate = CompletableFuture.runAsync(() -> {
            secondAttempting.countDown();
            second.updateBatch(batch -> {
                secondEntered.set(true);
                batch.put("chat4j.second", "two");
            });
        });
        assertThat(secondAttempting.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondUpdate).isNotDone();
        assertThat(secondEntered.get()).isFalse();

        releaseFirst.countDown();
        CompletableFuture.allOf(firstUpdate, secondUpdate).get(5, TimeUnit.SECONDS);

        var subject = new SettingsRepository(settingsFile);
        assertThat(subject.get("chat4j.first")).contains("one");
        assertThat(subject.get("chat4j.second")).contains("two");
    }

    @Test
    @DisplayName("Batch updates reject blank keys")
    void updateBatch_whenKeyBlank_rejectsOperation() {
        var subject = new SettingsRepository(tempDir.resolve("chat4j.properties"));

        assertThatThrownBy(() -> subject.updateBatch(batch -> batch.put(" ", "value")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
