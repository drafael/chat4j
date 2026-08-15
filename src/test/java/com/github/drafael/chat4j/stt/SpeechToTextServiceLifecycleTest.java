package com.github.drafael.chat4j.stt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.audio.AudioCaptureSession;
import com.github.drafael.chat4j.stt.audio.AudioLevelListener;
import com.github.drafael.chat4j.stt.audio.CapturedAudio;
import com.github.drafael.chat4j.stt.audio.MicrophoneAudioCapture;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperInstalledModel;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelCatalog;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementSnapshot;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelUsageTracker;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperNativeRuntime;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperValidationStatus;

import static org.assertj.core.api.Assertions.assertThat;

class SpeechToTextServiceLifecycleTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Queued UI callbacks are suppressed after terminal disposal")
    void runEdt_whenServiceIsDisposedBeforeDispatch_skipsCallback() throws Exception {
        var settings = new SpeechToTextSettings(
                new SettingsRepository(StoragePaths.ofConfigHome(tempDir)),
                new SpeechToTextProviderRegistry(List.of()),
                new MissingCredentialSource(),
                tempDir.resolve("models")
        );
        var capture = new MicrophoneAudioCapture(tempDir.resolve("audio")) {
            @Override
            public void cleanupStaleTempFiles() {
            }
        };
        var subject = new SpeechToTextService(settings, capture);
        var edtBlocked = new CountDownLatch(1);
        var releaseEdt = new CountDownLatch(1);
        var callbacks = new AtomicInteger();

        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            await(releaseEdt);
        });
        assertThat(edtBlocked.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            subject.runEdt(callbacks::incrementAndGet);
            subject.dispose();
        } finally {
            releaseEdt.countDown();
        }
        SwingUtilities.invokeAndWait(() -> {
        });

        assertThat(callbacks).hasValue(0);
    }

    @Test
    @DisplayName("A completed transcription cannot publish after a newer recording starts")
    void transcribe_whenNewSessionStartsBeforeEdtDispatch_suppressesOldTranscript() throws Exception {
        var settingsRepository = new SettingsRepository(StoragePaths.ofConfigHome(tempDir.resolve("terminal-config")));
        settingsRepository.put(SpeechToTextSettings.PROVIDER_KEY, "groq");
        var provider = new ImmediateSpeechToTextProvider();
        var capture = new SequencedCapture(tempDir.resolve("terminal-audio"));
        var settings = new SpeechToTextSettings(
                settingsRepository,
                new SpeechToTextProviderRegistry(List.of(provider)),
                new AvailableCredentialSource(),
                tempDir.resolve("terminal-models")
        );
        var subject = new SpeechToTextService(settings, capture);
        var callbacks = new RecordingCallbacks();
        var edtBlocked = new CountDownLatch(1);
        var releaseEdt = new CountDownLatch(1);

        try {
            subject.startRecording(callbacks);
            assertThat(capture.firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> {
            });
            SwingUtilities.invokeLater(() -> {
                edtBlocked.countDown();
                await(releaseEdt);
            });
            assertThat(edtBlocked.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                capture.first.completion.complete(new CapturedAudio(
                        Files.createFile(tempDir.resolve("terminal-audio.wav")),
                        1_000,
                        1_024
                ));
                assertThat(provider.transcribed.await(2, TimeUnit.SECONDS)).isTrue();
                awaitCondition(() -> !subject.active());

                subject.startRecording(callbacks);
                assertThat(capture.secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
                awaitCondition(subject::recording);
            } finally {
                releaseEdt.countDown();
            }
            SwingUtilities.invokeAndWait(() -> {
            });

            assertThat(callbacks.transcripts).isEmpty();
            assertThat(subject.recording()).isTrue();
        } finally {
            releaseEdt.countDown();
            subject.disposeAsync().get(2, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    @Test
    @DisplayName("Asynchronous disposal waits for microphone capture cleanup")
    void disposeAsync_whenCaptureCancellationIsBlocked_completesAfterCaptureSettles() throws Exception {
        var settingsRepository = new SettingsRepository(StoragePaths.ofConfigHome(tempDir.resolve("capture-dispose-config")));
        settingsRepository.put(SpeechToTextSettings.PROVIDER_KEY, "groq");
        var captureSession = new BlockingCancelCaptureSession();
        var capture = new MicrophoneAudioCapture(tempDir.resolve("capture-dispose-audio")) {
            @Override
            public void cleanupStaleTempFiles() {
            }

            @Override
            public AudioCaptureSession start(int maxDurationSeconds, AudioLevelListener levelListener) {
                return captureSession;
            }
        };
        var settings = new SpeechToTextSettings(
                settingsRepository,
                new SpeechToTextProviderRegistry(List.of(new ImmediateSpeechToTextProvider())),
                new AvailableCredentialSource(),
                tempDir.resolve("capture-dispose-models")
        );
        var subject = new SpeechToTextService(settings, capture);
        CompletableFuture<Void> cleanup = null;
        try {
            subject.startRecording(new RecordingCallbacks());
            awaitCondition(subject::recording);

            cleanup = subject.disposeAsync();
            assertThat(captureSession.cancelStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cleanup.isDone()).isFalse();

            captureSession.releaseCancel.countDown();
            cleanup.get(2, TimeUnit.SECONDS);
            assertThat(cleanup.isDone()).isTrue();
        } finally {
            captureSession.releaseCancel.countDown();
            if (cleanup != null) {
                cleanup.get(2, TimeUnit.SECONDS);
            }
            subject.dispose();
        }
    }

    @Test
    @DisplayName("Provider linkage failure resets transcription state and removes captured audio")
    void transcribe_whenProviderThrowsLinkageError_resetsStateAndDeletesAudio() throws Exception {
        var settingsRepository = new SettingsRepository(StoragePaths.ofConfigHome(tempDir.resolve("provider-linkage-config")));
        settingsRepository.put(SpeechToTextSettings.PROVIDER_KEY, "groq");
        var provider = new LinkageFailingSpeechToTextProvider();
        var credentialSource = new RotatingCredentialSource();
        Path audioPath = Files.createFile(tempDir.resolve("provider-linkage.wav"));
        var capture = new MicrophoneAudioCapture(tempDir.resolve("provider-linkage-audio")) {
            @Override
            public void cleanupStaleTempFiles() {
            }

            @Override
            public AudioCaptureSession start(int maxDurationSeconds, AudioLevelListener levelListener) {
                return new CompletedCaptureSession(new CapturedAudio(audioPath, 1_000, 1_024));
            }
        };
        var settings = new SpeechToTextSettings(
                settingsRepository,
                new SpeechToTextProviderRegistry(List.of(provider)),
                credentialSource,
                tempDir.resolve("provider-linkage-models")
        );
        var subject = new SpeechToTextService(settings, capture);
        var callbacks = new RecordingCallbacks();
        try {
            subject.startRecording(callbacks);

            awaitCondition(() -> !callbacks.errors.isEmpty());
            assertThat(subject.active()).isFalse();
            assertThat(credentialSource.resolutions).hasValue(1);
            assertThat(provider.observedApiKeys).containsExactly("stt-secret-1", "stt-secret-1");
            assertThat(provider.credentialSourceText)
                    .contains("apiKey=****")
                    .doesNotContain("stt-secret-1", "stt-secret-2");
            assertThat(provider.contextText)
                    .contains("credentialSource=<masked>")
                    .doesNotContain("stt-secret-1", "stt-secret-2");
            assertThat(callbacks.errors).contains("provider runtime missing [REDACTED]");
            assertThat(callbacks.errors).allMatch(message -> !message.contains("stt-secret-1") && !message.contains("stt-secret-2"));
            assertThat(audioPath).doesNotExist();
        } finally {
            subject.disposeAsync().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Whisper cancellation suppresses a result returned after cancellation")
    void cancel_whenWhisperReturnsAfterCancellation_doesNotPublishTranscript() throws Exception {
        var settingsRepository = new SettingsRepository(StoragePaths.ofConfigHome(tempDir.resolve("cancel-result-config")));
        settingsRepository.put(SpeechToTextSettings.PROVIDER_KEY, WhisperSpeechToTextProvider.ID);
        var provider = new BlockingWhisperProvider();
        var audioPath = Files.createFile(tempDir.resolve("cancel-result.wav"));
        var capture = new MicrophoneAudioCapture(tempDir.resolve("cancel-result-audio")) {
            @Override
            public void cleanupStaleTempFiles() {
            }

            @Override
            public AudioCaptureSession start(int maxDurationSeconds, AudioLevelListener levelListener) {
                return new CompletedCaptureSession(new CapturedAudio(audioPath, 1_000, 1_024));
            }
        };
        try (var managementService = new FakeWhisperModelManagementService(
                settingsRepository,
                tempDir.resolve("cancel-result-models"),
                tempDir.resolve("cancel-result-temp")
        )) {
            var settings = new SpeechToTextSettings(
                    settingsRepository,
                    new SpeechToTextProviderRegistry(List.of(provider)),
                    new MissingCredentialSource(),
                    tempDir.resolve("cancel-result-models"),
                    null,
                    managementService
            );
            var subject = new SpeechToTextService(
                    settings,
                    capture,
                    Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()),
                    false,
                    managementService
            );
            var callbacks = new RecordingCallbacks();
            try {
                subject.startRecording(callbacks);
                assertThat(provider.started.await(2, TimeUnit.SECONDS)).isTrue();

                subject.cancel(callbacks);
                provider.release.countDown();
                awaitCondition(() -> !subject.active());
                awaitCondition(() -> callbacks.statuses.contains("Transcription canceled."));

                assertThat(callbacks.transcripts).isEmpty();
                assertThat(callbacks.errors).isEmpty();
                assertThat(callbacks.statuses).contains("Transcription canceled.");
            } finally {
                provider.release.countDown();
                subject.disposeAsync().get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("A stale capture preparation cannot clear a restarted recording session")
    void cancelAndRestart_whenFirstCaptureStartupWasBlocked_keepsRestartedSessionActive() throws Exception {
        var settingsRepository = new SettingsRepository(StoragePaths.ofConfigHome(tempDir.resolve("restart-config")));
        settingsRepository.put(SpeechToTextSettings.PROVIDER_KEY, WhisperSpeechToTextProvider.ID);
        var firstCaptureStarted = new CountDownLatch(1);
        var releaseFirstCapture = new CountDownLatch(1);
        var secondCaptureStarted = new CountDownLatch(1);
        var captureCount = new AtomicInteger();
        var capture = new MicrophoneAudioCapture(tempDir.resolve("restart-audio")) {
            @Override
            public void cleanupStaleTempFiles() {
            }

            @Override
            public AudioCaptureSession start(int maxDurationSeconds, AudioLevelListener levelListener) {
                int attempt = captureCount.incrementAndGet();
                if (attempt == 1) {
                    firstCaptureStarted.countDown();
                    await(releaseFirstCapture);
                } else {
                    secondCaptureStarted.countDown();
                }
                return new PendingCaptureSession();
            }
        };
        try (var managementService = new FakeWhisperModelManagementService(
                settingsRepository,
                tempDir.resolve("restart-models"),
                tempDir.resolve("restart-temp")
        )) {
            var settings = new SpeechToTextSettings(
                    settingsRepository,
                    SpeechToTextTestProviders.createDefault(),
                    new MissingCredentialSource(),
                    tempDir.resolve("restart-models"),
                    null,
                    managementService
            );
            var subject = new SpeechToTextService(
                    settings,
                    capture,
                    Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()),
                    false,
                    managementService
            );
            var callbacks = new RecordingCallbacks();

            subject.startRecording(callbacks);
            assertThat(firstCaptureStarted.await(2, TimeUnit.SECONDS)).isTrue();
            subject.cancel(callbacks);
            subject.startRecording(callbacks);
            releaseFirstCapture.countDown();
            assertThat(secondCaptureStarted.await(2, TimeUnit.SECONDS)).isTrue();
            awaitCondition(subject::recording);

            subject.startRecording(callbacks);
            awaitCondition(() -> callbacks.errors.contains("Speech to Text is already active."));

            assertThat(subject.active()).isTrue();
            assertThat(subject.recording()).isTrue();
            assertThat(captureCount).hasValue(2);
            assertThat(callbacks.errors).contains("Speech to Text is already active.");
            subject.dispose();
        } finally {
            releaseFirstCapture.countDown();
        }
    }

    @Test
    @DisplayName("Native capture startup failure resets STT state and releases the Whisper lease")
    void startRecording_whenCaptureThrowsLinkageError_resetsStateAndReleasesLease() throws Exception {
        var settingsRepository = new SettingsRepository(StoragePaths.ofConfigHome(tempDir.resolve("linkage-config")));
        settingsRepository.put(SpeechToTextSettings.PROVIDER_KEY, WhisperSpeechToTextProvider.ID);
        var capture = new MicrophoneAudioCapture(tempDir.resolve("linkage-audio")) {
            @Override
            public void cleanupStaleTempFiles() {
            }

            @Override
            public AudioCaptureSession start(int maxDurationSeconds, AudioLevelListener levelListener) {
                throw new NoClassDefFoundError("audio runtime missing");
            }
        };
        try (var managementService = new FakeWhisperModelManagementService(
                settingsRepository,
                tempDir.resolve("linkage-models"),
                tempDir.resolve("linkage-temp")
        )) {
            var settings = new SpeechToTextSettings(
                    settingsRepository,
                    SpeechToTextTestProviders.createDefault(),
                    new MissingCredentialSource(),
                    tempDir.resolve("linkage-models"),
                    null,
                    managementService
            );
            var subject = new SpeechToTextService(
                    settings,
                    capture,
                    Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()),
                    false,
                    managementService
            );
            var callbacks = new RecordingCallbacks();
            try {
                subject.startRecording(callbacks);

                awaitCondition(() -> !callbacks.errors.isEmpty());
                assertThat(subject.active()).isFalse();
                assertThat(managementService.tracker.inUse(managementService.tracker.acquiredModelId)).isFalse();
                assertThat(callbacks.errors).contains("audio runtime missing");
            } finally {
                subject.disposeAsync().get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("Cancellation releases a Whisper lease when capture startup subsequently fails")
    void cancel_whenWhisperCaptureStartupFails_releasesModelLease() throws Exception {
        var settingsRepository = new SettingsRepository(StoragePaths.ofConfigHome(tempDir.resolve("whisper-config")));
        settingsRepository.put(SpeechToTextSettings.PROVIDER_KEY, WhisperSpeechToTextProvider.ID);
        var captureStarted = new CountDownLatch(1);
        var releaseCapture = new CountDownLatch(1);
        var capture = new MicrophoneAudioCapture(tempDir.resolve("whisper-audio")) {
            @Override
            public void cleanupStaleTempFiles() {
            }

            @Override
            public AudioCaptureSession start(int maxDurationSeconds, AudioLevelListener levelListener) {
                captureStarted.countDown();
                await(releaseCapture);
                throw new IllegalStateException("forced capture failure");
            }
        };
        try (var managementService = new FakeWhisperModelManagementService(
                settingsRepository,
                tempDir.resolve("whisper-models"),
                tempDir.resolve("whisper-temp")
        )) {
            var settings = new SpeechToTextSettings(
                    settingsRepository,
                    SpeechToTextTestProviders.createDefault(),
                    new MissingCredentialSource(),
                    tempDir.resolve("whisper-models"),
                    null,
                    managementService
            );
            var subject = new SpeechToTextService(
                    settings,
                    capture,
                    Executors.newSingleThreadExecutor(Thread.ofVirtual().factory()),
                    false,
                    managementService
            );

            subject.startRecording(new RecordingCallbacks());
            assertThat(captureStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(managementService.tracker.acquiredModelId).isNotBlank();
            assertThat(managementService.tracker.inUse(managementService.tracker.acquiredModelId)).isTrue();

            subject.cancel(new RecordingCallbacks());
            releaseCapture.countDown();

            awaitCondition(() -> !managementService.tracker.inUse(managementService.tracker.acquiredModelId));
            assertThat(subject.active()).isFalse();
            subject.dispose();
        } finally {
            releaseCapture.countDown();
        }
    }

    private static void awaitCondition(Condition condition) throws Exception {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.met() && System.nanoTime() < deadlineNanos) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(condition.met()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", e);
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean met();
    }

    private static final class SequencedCapture extends MicrophoneAudioCapture {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch secondStarted = new CountDownLatch(1);
        private final PendingCaptureSession first = new PendingCaptureSession();
        private final PendingCaptureSession second = new PendingCaptureSession();
        private final AtomicInteger attempts = new AtomicInteger();

        private SequencedCapture(Path tempDirectory) {
            super(tempDirectory);
        }

        @Override
        public void cleanupStaleTempFiles() {
        }

        @Override
        public AudioCaptureSession start(int maxDurationSeconds, AudioLevelListener levelListener) {
            if (attempts.incrementAndGet() == 1) {
                firstStarted.countDown();
                return first;
            }
            secondStarted.countDown();
            return second;
        }
    }

    private static final class LinkageFailingSpeechToTextProvider implements SpeechToTextProvider {
        private static final SpeechToTextCatalogItem MODEL = SpeechToTextCatalogItem.of("fake-model", "Fake model");
        private final List<String> observedApiKeys = new CopyOnWriteArrayList<>();
        private volatile String credentialSourceText;
        private volatile String contextText;

        @Override
        public String id() {
            return "groq";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public String requiredEnvVar() {
            return "GROQ_API_KEY";
        }

        @Override
        public SpeechToTextCatalogItem defaultModel() {
            return MODEL;
        }

        @Override
        public List<SpeechToTextCatalogItem> bundledModels() {
            return List.of(MODEL);
        }

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
            return List.of(MODEL);
        }

        @Override
        public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) {
            credentialSourceText = context.credentialSource().toString();
            contextText = context.toString();
            observedApiKeys.add(context.credentialSource().resolveRequiredApiKey(requiredEnvVar()));
            observedApiKeys.add(context.credentialSource().resolveRequiredApiKey(requiredEnvVar()));
            throw new NoClassDefFoundError("provider runtime missing %s".formatted(observedApiKeys.getLast()));
        }
    }

    private static final class ImmediateSpeechToTextProvider implements SpeechToTextProvider {
        private static final SpeechToTextCatalogItem MODEL = SpeechToTextCatalogItem.of("fake-model", "Fake model");
        private final CountDownLatch transcribed = new CountDownLatch(1);

        @Override
        public String id() {
            return "groq";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public String requiredEnvVar() {
            return "GROQ_API_KEY";
        }

        @Override
        public SpeechToTextCatalogItem defaultModel() {
            return MODEL;
        }

        @Override
        public List<SpeechToTextCatalogItem> bundledModels() {
            return List.of(MODEL);
        }

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
            return List.of(MODEL);
        }

        @Override
        public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) {
            transcribed.countDown();
            return new SpeechToTextResult("old transcript");
        }
    }

    private static final class BlockingCancelCaptureSession implements AudioCaptureSession {
        private final CompletableFuture<CapturedAudio> completion = new CompletableFuture<>();
        private final CountDownLatch cancelStarted = new CountDownLatch(1);
        private final CountDownLatch releaseCancel = new CountDownLatch(1);

        @Override
        public CompletableFuture<CapturedAudio> completion() {
            return completion;
        }

        @Override
        public void stop() {
        }

        @Override
        public void cancel() {
            cancelStarted.countDown();
            await(releaseCancel);
            completion.cancel(false);
        }
    }

    private static final class CompletedCaptureSession implements AudioCaptureSession {
        private final CompletableFuture<CapturedAudio> completion;

        private CompletedCaptureSession(CapturedAudio audio) {
            completion = CompletableFuture.completedFuture(audio);
        }

        @Override
        public CompletableFuture<CapturedAudio> completion() {
            return completion;
        }

        @Override
        public void stop() {
        }

        @Override
        public void cancel() {
        }
    }

    private static final class BlockingWhisperProvider implements SpeechToTextProvider {
        private static final SpeechToTextCatalogItem MODEL = SpeechToTextCatalogItem.of("tiny.en", "Whisper tiny.en");
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String id() {
            return WhisperSpeechToTextProvider.ID;
        }

        @Override
        public String displayName() {
            return "Whisper test";
        }

        @Override
        public String requiredEnvVar() {
            return null;
        }

        @Override
        public SpeechToTextCatalogItem defaultModel() {
            return MODEL;
        }

        @Override
        public List<SpeechToTextCatalogItem> bundledModels() {
            return List.of(MODEL);
        }

        @Override
        public boolean supportsLocalModels() {
            return true;
        }

        @Override
        public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
            return List.of(MODEL);
        }

        @Override
        public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) {
            started.countDown();
            await(release);
            return new SpeechToTextResult("cancelled transcript");
        }
    }

    private static final class PendingCaptureSession implements AudioCaptureSession {
        private final CompletableFuture<CapturedAudio> completion = new CompletableFuture<>();

        @Override
        public CompletableFuture<CapturedAudio> completion() {
            return completion;
        }

        @Override
        public void stop() {
        }

        @Override
        public void cancel() {
            completion.cancel(false);
        }
    }

    private static final class RecordingCallbacks implements SpeechToTextService.Callbacks {
        private final List<String> errors = new CopyOnWriteArrayList<>();
        private final List<String> transcripts = new CopyOnWriteArrayList<>();
        private final List<String> statuses = new CopyOnWriteArrayList<>();
        @Override
        public void stateChanged() {
        }

        @Override
        public void status(String message) {
            statuses.add(message);
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }

        @Override
        public void transcript(String text) {
            transcripts.add(text);
        }

        @Override
        public void level(double rms, double peak) {
        }
    }

    private static final class FakeWhisperModelManagementService extends WhisperModelManagementService {
        private final RecordingUsageTracker tracker = new RecordingUsageTracker();
        private final WhisperModelManagementSnapshot snapshot;

        private FakeWhisperModelManagementService(
                SettingsRepository settingsRepository,
                Path modelsDirectory,
                Path tempDirectory
        ) {
            super(
                    settingsRepository,
                    modelsDirectory,
                    tempDirectory,
                    WhisperNativeRuntime.shared(),
                    new WhisperModelUsageTracker()
            );
            var entry = WhisperModelCatalog.find("tiny.en").orElseThrow();
            var installed = new WhisperInstalledModel(
                    entry.id(),
                    entry.label(),
                    modelsDirectory.resolve("whisper/tiny.en"),
                    modelsDirectory.resolve("whisper/tiny.en"),
                    entry,
                    true,
                    WhisperValidationStatus.VALID,
                    "Installed",
                    "fingerprint"
            );
            snapshot = WhisperModelManagementSnapshot.builder()
                    .modelRoot(modelsDirectory.resolve("whisper"))
                    .tempRoot(tempDirectory)
                    .catalog(WhisperModelCatalog.entries())
                    .installedModels(List.of(installed))
                    .rows(List.of())
                    .selectedModel(installed)
                    .runtimeReady(true)
                    .statusMessage("Using Whisper.cpp model Whisper tiny.en locally.")
                    .operationType("")
                    .operationModelId("")
                    .operationModelLabel("")
                    .operationStatus("")
                    .build();
        }

        @Override
        public WhisperModelManagementSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public void validateSelectedNow() {
        }

        @Override
        public WhisperModelUsageTracker usageTracker() {
            return tracker;
        }
    }

    private static final class RecordingUsageTracker extends WhisperModelUsageTracker {
        private volatile String acquiredModelId = "";

        @Override
        public Lease acquire(String modelId) {
            acquiredModelId = modelId;
            return super.acquire(modelId);
        }
    }

    private static final class RotatingCredentialSource implements CredentialSource {
        private final AtomicInteger resolutions = new AtomicInteger();

        @Override
        public boolean hasRequiredCredentials(String envVar) {
            return true;
        }

        @Override
        public String resolveRequiredApiKey(String envVar) {
            return "stt-secret-%d".formatted(resolutions.incrementAndGet());
        }
    }

    private static final class AvailableCredentialSource implements CredentialSource {
        @Override
        public boolean hasRequiredCredentials(String envVar) {
            return true;
        }

        @Override
        public String resolveRequiredApiKey(String envVar) {
            return "test-key";
        }
    }

    private static final class MissingCredentialSource implements CredentialSource {
        @Override
        public boolean hasRequiredCredentials(String envVar) {
            return false;
        }

        @Override
        public String resolveRequiredApiKey(String envVar) {
            throw new IllegalStateException("missing credential");
        }
    }
}
