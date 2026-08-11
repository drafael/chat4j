package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.tts.audio.AudioPlaybackService;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextToSpeechServiceTest {

    @Test
    @DisplayName("Read aloud chunks provider-limited text before synthesis")
    void readAloud_providerHasInputLimit_synthesizesChunks() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fake");
        var provider = new FakeProvider();
        var playback = new RecordingPlaybackService();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                playback,
                executor
        );

        subject.readAloud("message", "one two three four five", error -> {
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(provider.requests).extracting(TextToSpeechRequest::text).containsExactly("one two", "three four", "five");
        assertThat(playback.playCount).isEqualTo(3);
    }

    @Test
    @DisplayName("Read aloud does not exceed provider limit when punctuation is at split boundary")
    void readAloud_whenPunctuationIsAtSplitBoundary_keepsChunksWithinLimit() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fake");
        var provider = new FakeProvider();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                new RecordingPlaybackService(),
                executor
        );

        subject.readAloud("message", "abcdefghij. next", error -> {
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(provider.requests)
                .extracting(request -> request.text().length())
                .allMatch(length -> length <= provider.maxInputCharacters());
        assertThat(provider.requests)
                .extracting(TextToSpeechRequest::text)
                .containsExactly("abcdefghij", ". next");
    }

    @Test
    @DisplayName("Read aloud toggles active message and stops on second click")
    void readAloud_whenSameMessageClickedAgain_stopsActivePlayback() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fake");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var playback = new BlockingPlaybackService();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(new FakeProvider()))),
                playback,
                executor
        );
        var stateChanges = new AtomicInteger();

        subject.readAloud("message", "hello", error -> {
        }, status -> {
        }, stateChanges::incrementAndGet);
        assertThat(playback.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(subject.isReadAloudActive("message")).isTrue();

        subject.readAloud("message", "", error -> {
        }, status -> {
        }, stateChanges::incrementAndGet);

        assertThat(subject.isReadAloudActive("message")).isFalse();
        assertThat(stateChanges.get()).isGreaterThanOrEqualTo(2);
        subject.dispose();
    }

    @Test
    @DisplayName("Fatal synthesis errors settle ownership and reach the uncaught exception handler")
    void readAloud_whenProviderThrowsFatalError_cleansUpAndRethrows() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fatal-error");
        var uncaught = new AtomicReference<Throwable>();
        var uncaughtReported = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor(command -> {
            var thread = new Thread(command, "tts-fatal-error-test");
            thread.setUncaughtExceptionHandler((ignored, error) -> {
                uncaught.set(error);
                uncaughtReported.countDown();
            });
            return thread;
        });
        var reportedError = new AtomicReference<String>();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(new FatalErrorProvider()))),
                new RecordingPlaybackService(),
                executor
        );

        try {
            subject.readAloud("message", "hello", reportedError::set);

            assertThat(uncaughtReported.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(uncaught.get()).isInstanceOf(AssertionError.class).hasMessage("fatal synthesis failure");
            assertThat(reportedError.get()).isNull();
            assertThat(subject.isReadAloudActive("message")).isFalse();
        } finally {
            subject.disposeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Synthesis cancellation ownership is published before provider work starts")
    void stop_whenCalledFromProviderSynthesis_interruptsOwnedSynthesisThread() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "reentrant-stop");
        var subjectReference = new AtomicReference<TextToSpeechService>();
        var provider = new ReentrantStopProvider(subjectReference);
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                new RecordingPlaybackService(),
                Executors.newSingleThreadExecutor()
        );
        subjectReference.set(subject);

        try {
            subject.readAloud("message", "hello", ignored -> {
            });

            assertThat(provider.finished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.interrupted).isTrue();
            assertThat(subject.isReadAloudActive("message")).isFalse();
        } finally {
            subject.disposeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Disposing read aloud interrupts active provider synthesis")
    void dispose_whenSynthesisIsActive_interruptsProviderWork() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "blocking");
        var provider = new BlockingSynthesisProvider();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                new RecordingPlaybackService(),
                Executors.newSingleThreadExecutor()
        );

        try {
            subject.readAloud("message", "hello", ignored -> {
            });
            assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();

            subject.dispose();

            assertThat(provider.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(subject.isReadAloudActive("message")).isFalse();
        } finally {
            subject.dispose();
        }
    }

    @Test
    @DisplayName("Asynchronous disposal waits for synthesis cleanup to settle")
    void disposeAsync_whenSynthesisIgnoresInterruption_completesAfterProviderSettles() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "stubborn");
        var provider = new StubbornSynthesisProvider();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                new RecordingPlaybackService(),
                Executors.newSingleThreadExecutor()
        );

        CompletableFuture<Void> cleanup = null;
        try {
            subject.readAloud("message", "hello", ignored -> {
            });
            assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();

            cleanup = subject.disposeAsync();

            assertThat(cleanup.isDone()).isFalse();
            provider.release.countDown();
            cleanup.get(5, TimeUnit.SECONDS);
            assertThat(cleanup.isDone()).isTrue();
        } finally {
            provider.release.countDown();
            if (cleanup != null) {
                cleanup.get(5, TimeUnit.SECONDS);
            }
            subject.dispose();
        }
    }

    @Test
    @DisplayName("Stopping after playback admission prevents late playback registration")
    void stop_whenPlaybackAdmissionIsBlocked_preventsPlaybackStart() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fake");
        var playback = new CancellationAwarePlaybackService();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(new FakeProvider()))),
                playback,
                Executors.newSingleThreadExecutor()
        );

        try {
            subject.readAloud("message", "hello", ignored -> {
            });
            assertThat(playback.admissionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            subject.stop();
            playback.releaseAdmission.countDown();

            assertThat(playback.settled.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(playback.played).isFalse();
        } finally {
            playback.releaseAdmission.countDown();
            subject.dispose();
        }
    }

    @Test
    @DisplayName("Read aloud strips markdown syntax before synthesis")
    void readAloud_markdownText_synthesizesPlainSpeechText() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fake");
        FakeProvider provider = new LargeInputProvider();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                new RecordingPlaybackService(),
                executor
        );

        subject.readAloud("message", "## Title\nThis is **bold**, [price is $5](https://example.com), and `C:\\tmp\\`.", error -> {
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(provider.requests).extracting(TextToSpeechRequest::text)
                .containsExactly("Title This is bold, price is $5, and C:\\tmp.");
    }

    @Test
    @DisplayName("Excluded blocks and formulas never reach the speech provider")
    void readAloud_whenMarkdownContainsExcludedContent_sendsOnlySpeakableText() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fake");
        FakeProvider provider = new LargeInputProvider();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                new RecordingPlaybackService(),
                executor
        );
        String markdown = """
                Intro.
                # Provider heading
                    HEADING_CODE_SENTINEL
                Provider setext heading
                =======
                    SETEXT_CODE_SENTINEL
                * * *
                    THEMATIC_BREAK_CODE_SENTINEL
                | Table heading |
                | --- |
                    TABLE_CODE_SENTINEL
                ```java
                CODE_SENTINEL
                ```
                ~~~mermaid
                MERMAID_SENTINEL
                ~~~
                ```smiles
                SMILES_SENTINEL
                ```
                - ```python
                  LIST_CODE_SENTINEL
                  ```
                - List prose.

                    ~~~~mermaid
                    LIST_CONTINUATION_DIAGRAM_SENTINEL
                    ~~~~
                - ```java
                  FIRST_BLANK_LINE_CODE_SENTINEL

                  SECOND_BLANK_LINE_CODE_SENTINEL
                  ```
                -     EXCESS_LIST_PADDING_CODE_SENTINEL
                - $$
                  FIRST_BLANK_LINE_MATH_SENTINEL = x

                  SECOND_BLANK_LINE_MATH_SENTINEL = y
                  $$
                  - ```java
                    NESTED_LIST_CODE_SENTINEL
                    ```
                  - NESTED_LIST_PROSE_SENTINEL
                - dedented outer
                  - dedented inner
                  dedented outer prose

                      DEDENTED_OUTER_CODE_SENTINEL
                  DEDENTED_OUTER_PROSE_SENTINEL
                - outer
                  > - inner
                  >   ~~~java
                  >   NESTED_QUOTE_LIST_CODE_SENTINEL
                  > ~~~
                  > NESTED_QUOTE_LIST_LEAK_SENTINEL
                  >   ~~~
                  > NESTED_QUOTE_LIST_PROSE_SENTINEL
                > ~~~smiles
                > BLOCKQUOTE_SMILES_SENTINEL
                > ~~~
                > Quoted prose.
                >
                >     BLOCKQUOTE_CODE_SENTINEL
                > Closing prose.
                - > ~~~mermaid
                  > LIST_BLOCKQUOTE_DIAGRAM_SENTINEL
                  > ~~~
                > - ~~~smiles
                >   BLOCKQUOTE_LIST_DIAGRAM_SENTINEL
                >   ~~~
                - Padded blockquote prose.
                  >   ~~~mermaid
                  > PADDED_BLOCKQUOTE_CODE_SENTINEL
                  > ~~~
                  > Padded fence trailing prose.
                  >   $$
                  > PADDED_BLOCKQUOTE_MATH_SENTINEL = x
                  > $$ Padded math trailing prose.
                > $$
                > BLOCKQUOTE_DISPLAY_MATH_SENTINEL = x
                > $$ Quoted math trailing prose.
                - \\[
                  LIST_DISPLAY_MATH_SENTINEL = y
                  \\] List math trailing prose.
                Formula $INLINE_MATH_SENTINEL = x$.
                $$
                DISPLAY_MATH_SENTINEL = y
                $$
                Use `Thread.startVirtualThread()`.
                Preserve ``a `$INLINE_CODE_MATH_SENTINEL$ b``.
                Preserve multiline ``a
                $MULTILINE_INLINE_CODE_MATH_SENTINEL = z$
                b`` too.
                Done.
                """;

        try {
            subject.readAloud("message", markdown, error -> {
            });
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(provider.requests).hasSize(1);
            assertThat(provider.requests.getFirst().text())
                    .contains(
                            "Intro.",
                            "Provider heading",
                            "Provider setext heading",
                            "NESTED_LIST_PROSE_SENTINEL",
                            "DEDENTED_OUTER_PROSE_SENTINEL",
                            "NESTED_QUOTE_LIST_PROSE_SENTINEL",
                            "Padded fence trailing prose.",
                            "Padded math trailing prose.",
                            "Quoted math trailing prose.",
                            "List math trailing prose.",
                            "Formula",
                            "Thread.startVirtualThread()",
                            "$INLINE_CODE_MATH_SENTINEL$",
                            "$MULTILINE_INLINE_CODE_MATH_SENTINEL = z$",
                            "Done."
                    )
                    .doesNotContain(
                            "HEADING_CODE_SENTINEL",
                            "SETEXT_CODE_SENTINEL",
                            "THEMATIC_BREAK_CODE_SENTINEL",
                            "TABLE_CODE_SENTINEL",
                            "CODE_SENTINEL",
                            "MERMAID_SENTINEL",
                            "SMILES_SENTINEL",
                            "LIST_CODE_SENTINEL",
                            "LIST_CONTINUATION_DIAGRAM_SENTINEL",
                            "FIRST_BLANK_LINE_CODE_SENTINEL",
                            "SECOND_BLANK_LINE_CODE_SENTINEL",
                            "EXCESS_LIST_PADDING_CODE_SENTINEL",
                            "FIRST_BLANK_LINE_MATH_SENTINEL",
                            "SECOND_BLANK_LINE_MATH_SENTINEL",
                            "NESTED_LIST_CODE_SENTINEL",
                            "DEDENTED_OUTER_CODE_SENTINEL",
                            "NESTED_QUOTE_LIST_CODE_SENTINEL",
                            "NESTED_QUOTE_LIST_LEAK_SENTINEL",
                            "BLOCKQUOTE_SMILES_SENTINEL",
                            "BLOCKQUOTE_CODE_SENTINEL",
                            "LIST_BLOCKQUOTE_DIAGRAM_SENTINEL",
                            "BLOCKQUOTE_LIST_DIAGRAM_SENTINEL",
                            "PADDED_BLOCKQUOTE_CODE_SENTINEL",
                            "PADDED_BLOCKQUOTE_MATH_SENTINEL",
                            "BLOCKQUOTE_DISPLAY_MATH_SENTINEL",
                            "LIST_DISPLAY_MATH_SENTINEL",
                            "INLINE_MATH_SENTINEL",
                            "DISPLAY_MATH_SENTINEL"
                    );
        } finally {
            subject.disposeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Messages containing only excluded content do not invoke the provider")
    void readAloud_whenMarkdownContainsOnlyExcludedContent_skipsSynthesis() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fake");
        var provider = new FakeProvider();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                new RecordingPlaybackService(),
                executor
        );
        var status = new AtomicReference<String>();

        try {
            subject.readAloud("message", "```mermaid\ngraph TD\n```\n$x = y$", error -> {
            }, status::set);
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(provider.requests).isEmpty();
            assertThat(status).hasValue("No text to read aloud.");
            assertThat(subject.isReadAloudActive("message")).isFalse();
        } finally {
            subject.disposeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Read aloud uses provider default response format")
    void readAloud_providerDefaultResponseFormat_sendsProviderFormat() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fake");
        var provider = new FakeProvider();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                new RecordingPlaybackService(),
                executor
        );

        subject.readAloud("message", "hello", error -> {
        });
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        assertThat(provider.requests).extracting(TextToSpeechRequest::responseFormat).containsExactly("test-format");
    }

    @Test
    @DisplayName("Read aloud redacts provider credentials from synthesis errors")
    void readAloud_whenProviderErrorContainsApiKey_redactsCredential() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "credential-error");
        var error = new AtomicReference<String>();
        var errorReported = new CountDownLatch(1);
        var provider = new CredentialErrorProvider();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(provider))),
                new RecordingPlaybackService(),
                Executors.newSingleThreadExecutor()
        );

        try {
            subject.readAloud("message", "hello", message -> {
                error.set(message);
                errorReported.countDown();
            });

            assertThat(errorReported.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.apiKeyResolutions).hasValue(1);
            assertThat(provider.synthesisApiKey).isEqualTo("tts-secret-1");
            assertThat(error.get())
                    .contains("synthesis rejected [REDACTED]")
                    .doesNotContain("tts-secret-1")
                    .doesNotContain("tts-secret-2");
        } finally {
            subject.disposeAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Read aloud uses provider unavailable message")
    void readAloud_providerUnavailable_reportsProviderMessage() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "unavailable");
        var error = new AtomicReference<String>();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(new UnavailableProvider()))),
                new RecordingPlaybackService(),
                Executors.newSingleThreadExecutor()
        );

        subject.readAloud("message", "hello", error::set);

        assertThat(error.get()).isEqualTo("Provider unavailable without credentials message.");
        subject.dispose();
    }

    @Test
    @DisplayName("Read aloud reports when the executor has already been disposed")
    void readAloud_executorDisposed_reportsError() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(TextToSpeechSettings.PROVIDER_KEY, "fake");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(new FakeProvider()))),
                new RecordingPlaybackService(),
                executor
        );
        subject.dispose();
        var error = new AtomicReference<String>();

        assertThat(subject.isReadAloudAvailable()).isFalse();
        subject.readAloud("message", "hello", error::set);

        assertThat(error.get()).contains("Read aloud is not available");
    }

    private static final class FatalErrorProvider extends FakeProvider {
        @Override
        public String id() {
            return "fatal-error";
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            throw new AssertionError("fatal synthesis failure");
        }
    }

    private static final class ReentrantStopProvider extends FakeProvider {
        private final AtomicReference<TextToSpeechService> subject;
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile boolean interrupted;

        private ReentrantStopProvider(AtomicReference<TextToSpeechService> subject) {
            this.subject = subject;
        }

        @Override
        public String id() {
            return "reentrant-stop";
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            subject.get().stop();
            interrupted = Thread.currentThread().isInterrupted();
            finished.countDown();
            return new TextToSpeechAudio(new byte[] {1}, "audio/test", "test-format");
        }
    }

    private static final class StubbornSynthesisProvider extends FakeProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String id() {
            return "stubborn";
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            started.countDown();
            boolean interrupted = false;
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return new TextToSpeechAudio(new byte[] {1}, "audio/test", "test-format");
        }
    }

    private static final class BlockingSynthesisProvider extends FakeProvider {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);

        @Override
        public String id() {
            return "blocking";
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            started.countDown();
            try {
                TimeUnit.SECONDS.sleep(30);
                return new TextToSpeechAudio(new byte[] {1}, "audio/test", "test-format");
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("synthesis interrupted", e);
            }
        }
    }

    private static class FakeProvider implements TextToSpeechProvider {
        private final List<TextToSpeechRequest> requests = new ArrayList<>();

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public String requiredEnvVar() {
            return null;
        }

        @Override
        public TextToSpeechCatalogItem defaultModel() {
            return TextToSpeechCatalogItem.of("model", "Model");
        }

        @Override
        public TextToSpeechCatalogItem defaultVoice() {
            return TextToSpeechCatalogItem.of("voice", "Voice");
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledModels() {
            return List.of(defaultModel());
        }

        @Override
        public List<TextToSpeechCatalogItem> bundledVoices() {
            return List.of(defaultVoice());
        }

        @Override
        public int maxInputCharacters() {
            return 10;
        }

        @Override
        public String defaultResponseFormat() {
            return "test-format";
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchModels() {
            return bundledModels();
        }

        @Override
        public List<TextToSpeechCatalogItem> fetchVoices() {
            return bundledVoices();
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            requests.add(request);
            return new TextToSpeechAudio(new byte[]{1}, "audio/wav", "wav");
        }
    }

    private static final class LargeInputProvider extends FakeProvider {
        @Override
        public int maxInputCharacters() {
            return 1_000;
        }
    }

    private static final class CredentialErrorProvider extends FakeProvider {
        private final AtomicInteger apiKeyResolutions = new AtomicInteger();
        private volatile String synthesisApiKey;

        @Override
        public String id() {
            return "credential-error";
        }

        @Override
        public String requiredEnvVar() {
            return "TEST_API_KEY";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String apiKey() {
            return "tts-secret-%d".formatted(apiKeyResolutions.incrementAndGet());
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request) {
            throw new AssertionError("Expected request-owned credential overload");
        }

        @Override
        public TextToSpeechAudio synthesize(TextToSpeechRequest request, String apiKey) {
            synthesisApiKey = apiKey;
            throw new IllegalStateException("synthesis rejected %s".formatted(apiKey));
        }
    }

    private static final class UnavailableProvider extends FakeProvider {
        @Override
        public String id() {
            return "unavailable";
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String unavailableMessage() {
            return "Provider unavailable without credentials message.";
        }
    }

    private static final class CancellationAwarePlaybackService implements AudioPlaybackService {
        private final CountDownLatch admissionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseAdmission = new CountDownLatch(1);
        private final CountDownLatch settled = new CountDownLatch(1);
        private volatile boolean played;

        @Override
        public void play(TextToSpeechAudio audio) {
            played = true;
            settled.countDown();
        }

        @Override
        public void play(TextToSpeechAudio audio, java.util.function.BooleanSupplier isCancelled) {
            admissionStarted.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = releaseAdmission.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }
            if (!isCancelled.getAsBoolean()) {
                played = true;
            }
            settled.countDown();
        }

        @Override
        public void stop() {
        }
    }

    private static final class BlockingPlaybackService implements AudioPlaybackService {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicBoolean playing = new AtomicBoolean();

        @Override
        public void play(TextToSpeechAudio audio) throws InterruptedException {
            playing.set(true);
            started.countDown();
            stopped.await(5, TimeUnit.SECONDS);
        }

        @Override
        public void stop() {
            if (playing.get()) {
                stopped.countDown();
            }
        }
    }

    private static final class RecordingPlaybackService implements AudioPlaybackService {
        private int playCount;

        @Override
        public void play(TextToSpeechAudio audio) {
            playCount++;
        }

        @Override
        public void stop() {
        }
    }
}
