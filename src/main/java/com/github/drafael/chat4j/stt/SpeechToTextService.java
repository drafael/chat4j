package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.audio.AudioCaptureSession;
import com.github.drafael.chat4j.stt.audio.CapturedAudio;
import com.github.drafael.chat4j.stt.audio.MicrophoneAudioCapture;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.LocalSpeechToTextModelReference;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import com.github.drafael.chat4j.stt.provider.vosk.VoskSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelUsageTracker;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

@Slf4j
public class SpeechToTextService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final SpeechToTextSettings settings;
    private final MicrophoneAudioCapture capture;
    private final ExecutorService executor;
    private final boolean disabled;
    private final WhisperModelManagementService whisperModelManagementService;
    private final AtomicLong sessionCounter = new AtomicLong();
    private final AtomicBoolean active = new AtomicBoolean();
    private volatile AudioCaptureSession activeCapture;
    private volatile SpeechToTextSessionSnapshot activeSnapshot;
    private volatile boolean recording;
    private volatile boolean transcribing;
    private volatile AtomicBoolean activeCancellation = new AtomicBoolean();
    private volatile WhisperModelUsageTracker.Lease activeWhisperLease;

    public SpeechToTextService(
            @NonNull SpeechToTextSettings settings,
            @NonNull MicrophoneAudioCapture capture
    ) {
        this(settings, capture, Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat4j-stt-", 0).factory()), false, null);
    }

    private SpeechToTextService(SpeechToTextSettings settings, MicrophoneAudioCapture capture, ExecutorService executor, boolean disabled, WhisperModelManagementService whisperModelManagementService) {
        this.settings = settings;
        this.capture = capture;
        this.executor = executor;
        this.disabled = disabled;
        this.whisperModelManagementService = whisperModelManagementService;
        if (!disabled) {
            Thread.startVirtualThread(capture::cleanupStaleTempFiles);
        }
    }

    public static SpeechToTextService createDefault(SettingsRepository settingsRepo, Path sttModelsDirectory, Path sttTempDirectory) {
        return createDefault(settingsRepo, sttModelsDirectory, sttTempDirectory, null);
    }

    public static SpeechToTextService createDefault(
            SettingsRepository settingsRepo,
            Path sttModelsDirectory,
            Path sttTempDirectory,
            VoskModelManagementService voskModelManagementService
    ) {
        return createDefault(settingsRepo, sttModelsDirectory, sttTempDirectory, voskModelManagementService, null);
    }

    public static SpeechToTextService createDefault(
            SettingsRepository settingsRepo,
            Path sttModelsDirectory,
            Path sttTempDirectory,
            VoskModelManagementService voskModelManagementService,
            WhisperModelManagementService whisperModelManagementService
    ) {
        return new SpeechToTextService(
                new SpeechToTextSettings(settingsRepo, SpeechToTextProviderRegistry.createDefault(), CredentialSource.SYSTEM, sttModelsDirectory, voskModelManagementService, whisperModelManagementService),
                new MicrophoneAudioCapture(sttTempDirectory),
                Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat4j-stt-", 0).factory()),
                false,
                whisperModelManagementService
        );
    }

    public static SpeechToTextService disabled() {
        return new DisabledSpeechToTextService();
    }

    public SpeechToTextSettingsSnapshot resolveSettings() {
        return disabled ? SpeechToTextSettingsSnapshot.off(SpeechToTextSettings.DEFAULT_MAX_DURATION_SECONDS, Path.of(".")) : settings.resolve();
    }

    public boolean available() {
        SpeechToTextSettingsSnapshot snapshot = resolveSettings();
        return snapshot.enabled() && snapshot.available();
    }

    public boolean active() {
        return active.get() || recording || transcribing;
    }

    public boolean recording() {
        return recording;
    }

    public boolean transcribing() {
        return transcribing;
    }

    public void startRecording(Callbacks callbacks) {
        if (disabled) {
            runEdt(() -> callbacks.error("Speech to Text is turned off."));
            return;
        }
        if (!active.compareAndSet(false, true)) {
            runEdt(() -> callbacks.error("Speech to Text is already active."));
            return;
        }

        long sessionId = sessionCounter.incrementAndGet();
        activeCancellation = new AtomicBoolean();
        try {
            CompletableFuture.runAsync(() -> prepareAndStartRecording(sessionId, callbacks), executor);
        } catch (RejectedExecutionException e) {
            resetState(sessionId);
            runEdt(() -> {
                callbacks.stateChanged();
                callbacks.error("Speech to Text is not available in this window.");
            });
        }
    }

    private void prepareAndStartRecording(long sessionId, Callbacks callbacks) {
        try {
            SpeechToTextSettingsSnapshot settingsSnapshot = settings.resolve();
            if (isStale(sessionId)) {
                return;
            }
            if (!settingsSnapshot.enabled()) {
                throw new SpeechToTextException("Speech to Text is turned off.");
            }
            if (VoskSpeechToTextProvider.ID.equals(settingsSnapshot.providerId())) {
                settings.validateSelectedVoskModelNow();
                settingsSnapshot = settings.resolve();
                if (!settingsSnapshot.enabled()) {
                    throw new SpeechToTextException("Speech to Text is turned off.");
                }
            }
            if (WhisperSpeechToTextProvider.ID.equals(settingsSnapshot.providerId())) {
                runEdt(() -> callbacks.status("Validating Whisper.cpp model..."));
                settings.validateSelectedWhisperModelNow();
                settingsSnapshot = settings.resolve();
                if (!settingsSnapshot.enabled()) {
                    throw new SpeechToTextException("Speech to Text is turned off.");
                }
            }
            if (!settingsSnapshot.available()) {
                throw new SpeechToTextException(StringUtils.defaultIfBlank(settingsSnapshot.statusMessage(), settingsSnapshot.provider().unavailableMessage()));
            }
            if (settingsSnapshot.model() == null) {
                throw new SpeechToTextException(StringUtils.defaultIfBlank(settingsSnapshot.statusMessage(), "Select a Speech to Text model first."));
            }
            activeSnapshot = new SpeechToTextSessionSnapshot(
                    settingsSnapshot.providerId(),
                    settingsSnapshot.model().id(),
                    settingsSnapshot.baseUri(),
                    settingsSnapshot.transcriptionUri(),
                    settingsSnapshot.localModelReference()
            );
            acquireWhisperLease(settingsSnapshot);
            AudioCaptureSession session = capture.start(settingsSnapshot.maxDurationSeconds(), (rms, peak) -> runEdt(() -> callbacks.level(rms, peak)));
            if (isStale(sessionId)) {
                session.cancel();
                return;
            }
            activeCapture = session;
            recording = true;
            transcribing = false;
            runEdt(callbacks::stateChanged);
            session.completion().whenComplete((audio, error) -> onCaptureComplete(sessionId, audio, error, callbacks));
        } catch (Exception e) {
            if (!isStale(sessionId)) {
                resetState(sessionId);
                runEdt(() -> {
                    callbacks.stateChanged();
                    callbacks.error(safeMessage(e));
                });
            }
            log.warn("Could not start STT capture: {}", ExceptionUtils.getMessage(e));
        }
    }

    public void stopRecordingAndTranscribe() {
        AudioCaptureSession session = activeCapture;
        if (session == null || !recording) {
            return;
        }
        runCaptureControl(session::stop);
    }

    public void cancel(Callbacks callbacks) {
        boolean wasTranscribing = transcribing;
        boolean whisperTranscribing = wasTranscribing && activeSnapshot != null && WhisperSpeechToTextProvider.ID.equals(activeSnapshot.providerId());
        activeCancellation.set(true);
        if (whisperTranscribing) {
            runEdt(() -> {
                callbacks.status("Finishing Whisper.cpp cancellation...");
                callbacks.stateChanged();
            });
            return;
        }
        long sessionId = sessionCounter.incrementAndGet();
        AudioCaptureSession session = activeCapture;
        resetState(sessionId);
        String message = wasTranscribing ? "Transcription canceled." : "Recording canceled.";
        runEdt(() -> {
            callbacks.status(message);
            callbacks.stateChanged();
        });
        if (session != null) {
            runCaptureControl(session::cancel);
        }
    }

    public void dispose() {
        cancel(Callbacks.noop());
        if (!disabled) {
            executor.shutdownNow();
        }
    }

    private void onCaptureComplete(long sessionId, CapturedAudio audio, Throwable error, Callbacks callbacks) {
        if (isStale(sessionId)) {
            deleteAudio(audio);
            return;
        }
        recording = false;
        activeCapture = null;
        if (error != null) {
            resetState(sessionId);
            runEdt(() -> {
                callbacks.stateChanged();
                if (!(error instanceof CancellationException)) {
                    callbacks.error("Microphone access is unavailable. Check your input device and permissions.");
                }
            });
            return;
        }
        if (audio.durationMillis() < MicrophoneAudioCapture.MIN_DURATION_MILLIS) {
            deleteAudio(audio);
            resetState(sessionId);
            runEdt(() -> {
                callbacks.stateChanged();
                callbacks.error("No speech was recorded.");
            });
            return;
        }
        if (audio.sizeBytes() > MicrophoneAudioCapture.MAX_CAPTURED_WAV_BYTES) {
            deleteAudio(audio);
            resetState(sessionId);
            runEdt(() -> {
                callbacks.stateChanged();
                callbacks.error("Recording is too large to transcribe.");
            });
            return;
        }
        transcribing = true;
        runEdt(callbacks::stateChanged);
        try {
            CompletableFuture.runAsync(() -> transcribe(sessionId, audio, callbacks), executor);
        } catch (RejectedExecutionException e) {
            deleteAudio(audio);
            resetState(sessionId);
            runEdt(() -> callbacks.error("Speech to Text is not available in this window."));
        }
    }

    private void transcribe(long sessionId, CapturedAudio audio, Callbacks callbacks) {
        try {
            SpeechToTextSessionSnapshot snapshot = activeSnapshot;
            SpeechToTextSettingsSnapshot current = settings.resolve();
            if (snapshot != null && WhisperSpeechToTextProvider.ID.equals(snapshot.providerId())) {
                runEdt(() -> callbacks.status("Validating Whisper.cpp model..."));
                settings.validateSelectedWhisperModelNow();
                current = settings.resolve();
            }
            if (!matchesSnapshot(snapshot, current)) {
                throw new SpeechToTextException("Speech-to-text settings changed; recording was not transcribed.");
            }
            SpeechToTextProviderContext context = new SpeechToTextProviderContext(
                    current.baseUri(),
                    current.transcriptionUri(),
                    CredentialSource.SYSTEM,
                    () -> isStale(sessionId) || activeCancellation.get(),
                    REQUEST_TIMEOUT,
                    current.localModelReference()
            );
            SpeechToTextResult result = current.provider().transcribe(
                    new SpeechToTextRequest(current.providerId(), current.model().id(), audio.path(), audio.durationMillis(), audio.sizeBytes()),
                    context
            );
            if (isStale(sessionId)) {
                return;
            }
            resetState(sessionId);
            runEdt(() -> {
                callbacks.stateChanged();
                callbacks.transcript(result.text());
            });
        } catch (Exception e) {
            if (!isStale(sessionId)) {
                resetState(sessionId);
                runEdt(() -> {
                    callbacks.stateChanged();
                    callbacks.error(safeMessage(e));
                });
            }
        } finally {
            deleteAudio(audio);
        }
    }

    private void acquireWhisperLease(SpeechToTextSettingsSnapshot settingsSnapshot) {
        if (!WhisperSpeechToTextProvider.ID.equals(settingsSnapshot.providerId())
                || settingsSnapshot.localModelReference() == null
                || whisperModelManagementService == null) {
            return;
        }
        releaseWhisperLease();
        activeWhisperLease = whisperModelManagementService.usageTracker().acquire(settingsSnapshot.localModelReference().modelId());
    }

    private void releaseWhisperLease() {
        WhisperModelUsageTracker.Lease lease = activeWhisperLease;
        activeWhisperLease = null;
        if (lease != null) {
            lease.close();
        }
    }

    private void runCaptureControl(Runnable action) {
        Thread.startVirtualThread(action);
    }

    private boolean matchesSnapshot(SpeechToTextSessionSnapshot snapshot, SpeechToTextSettingsSnapshot current) {
        return snapshot != null
                && current.enabled()
                && current.available()
                && Objects.equals(snapshot.providerId(), current.providerId())
                && current.model() != null
                && Objects.equals(snapshot.modelId(), current.model().id())
                && Objects.equals(snapshot.baseUri(), current.baseUri())
                && Objects.equals(snapshot.transcriptionUri(), current.transcriptionUri())
                && Objects.equals(snapshot.localModelReference(), current.localModelReference());
    }

    private boolean isStale(long sessionId) {
        return sessionId != sessionCounter.get();
    }

    private void resetState(long sessionId) {
        sessionCounter.compareAndSet(sessionId, sessionId + 1);
        active.set(false);
        recording = false;
        transcribing = false;
        activeCapture = null;
        activeSnapshot = null;
        activeCancellation = new AtomicBoolean();
        releaseWhisperLease();
    }

    private void deleteAudio(CapturedAudio audio) {
        if (audio == null) {
            return;
        }
        try {
            Files.deleteIfExists(audio.path());
        } catch (Exception ignored) {
        }
    }

    private String safeMessage(Exception e) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        return StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private static void runEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private record SpeechToTextSessionSnapshot(
            String providerId,
            String modelId,
            URI baseUri,
            URI transcriptionUri,
            LocalSpeechToTextModelReference localModelReference
    ) {
    }

    public interface Callbacks {
        void stateChanged();

        void status(String message);

        void error(String message);

        void transcript(String text);

        void level(double rms, double peak);

        static Callbacks noop() {
            return new Callbacks() {
                @Override
                public void stateChanged() {
                }

                @Override
                public void status(String message) {
                }

                @Override
                public void error(String message) {
                }

                @Override
                public void transcript(String text) {
                }

                @Override
                public void level(double rms, double peak) {
                }
            };
        }
    }

    private static final class DisabledSpeechToTextService extends SpeechToTextService {
        private DisabledSpeechToTextService() {
            super(null, null, null, true, null);
        }

        @Override
        public SpeechToTextSettingsSnapshot resolveSettings() {
            return SpeechToTextSettingsSnapshot.off(SpeechToTextSettings.DEFAULT_MAX_DURATION_SECONDS, Path.of("."));
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public void startRecording(Callbacks callbacks) {
            runEdt(() -> callbacks.error("Speech to Text is turned off."));
        }

        @Override
        public void stopRecordingAndTranscribe() {
        }

        @Override
        public void cancel(Callbacks callbacks) {
        }

        @Override
        public void dispose() {
        }
    }
}
