package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.ApiCredentialSource;
import com.github.drafael.chat4j.provider.support.ApiCredentialStatus;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
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
import com.github.drafael.chat4j.stt.provider.whisper.WhisperJniEngine;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperNativeRuntime;
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
import java.util.concurrent.TimeUnit;
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
    private final Object stateLock = new Object();
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final Object disposalLock = new Object();
    private volatile CompletableFuture<Void> disposalFuture = CompletableFuture.completedFuture(null);
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

    SpeechToTextService(SpeechToTextSettings settings, MicrophoneAudioCapture capture, ExecutorService executor, boolean disabled, WhisperModelManagementService whisperModelManagementService) {
        this.settings = settings;
        this.capture = capture;
        this.executor = executor;
        this.disabled = disabled;
        this.whisperModelManagementService = whisperModelManagementService;
        if (!disabled) {
            executor.submit(capture::cleanupStaleTempFiles);
        }
    }

    public static SpeechToTextService createDefault(
            SettingsRepository settingsRepo,
            Path sttModelsDirectory,
            Path sttTempDirectory,
            VoskModelManagementService voskModelManagementService,
            WhisperModelManagementService whisperModelManagementService,
            CredentialResolver credentialResolver,
            WhisperNativeRuntime whisperNativeRuntime
    ) {
        CredentialSource credentialSource = CredentialSource.from(credentialResolver);
        return new SpeechToTextService(
                new SpeechToTextSettings(
                        settingsRepo,
                        SpeechToTextProviderRegistry.createDefault(new WhisperJniEngine(whisperNativeRuntime)),
                        credentialSource,
                        sttModelsDirectory,
                        voskModelManagementService,
                        whisperModelManagementService
                ),
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
        if (disposed.get()) {
            return false;
        }
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
        if (disposed.get()) {
            return;
        }
        if (disabled) {
            runEdt(() -> callbacks.error("Speech to Text is turned off."));
            return;
        }
        long sessionId;
        synchronized (stateLock) {
            if (disposed.get()) {
                return;
            }
            if (!active.compareAndSet(false, true)) {
                sessionId = -1;
            } else {
                sessionId = sessionCounter.incrementAndGet();
                activeCancellation = new AtomicBoolean();
            }
        }
        if (sessionId < 0) {
            runEdt(() -> callbacks.error("Speech to Text is already active."));
            return;
        }
        try {
            CompletableFuture.runAsync(() -> prepareAndStartRecording(sessionId, callbacks), executor);
        } catch (RejectedExecutionException e) {
            if (resetState(sessionId)) {
                runResetSessionEdt(sessionId, () -> {
                    callbacks.stateChanged();
                    callbacks.error("Speech to Text is not available in this window.");
                });
            }
        }
    }

    private void prepareAndStartRecording(long sessionId, Callbacks callbacks) {
        WhisperModelUsageTracker.Lease preparationLease = null;
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
                runCurrentSessionEdt(sessionId, () -> callbacks.status("Validating Whisper.cpp model..."));
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

            SpeechToTextSessionSnapshot sessionSnapshot = new SpeechToTextSessionSnapshot(
                    settingsSnapshot.providerId(),
                    settingsSnapshot.model().id(),
                    settingsSnapshot.baseUri(),
                    settingsSnapshot.transcriptionUri(),
                    settingsSnapshot.localModelReference()
            );
            preparationLease = acquireWhisperLease(settingsSnapshot);
            AudioCaptureSession session = capture.start(
                    settingsSnapshot.maxDurationSeconds(),
                    (rms, peak) -> runCurrentSessionEdt(sessionId, () -> callbacks.level(rms, peak))
            );
            boolean published;
            synchronized (stateLock) {
                published = !isStale(sessionId) && !disposed.get();
                if (published) {
                    activeSnapshot = sessionSnapshot;
                    activeWhisperLease = preparationLease;
                    preparationLease = null;
                    activeCapture = session;
                    recording = true;
                    transcribing = false;
                }
            }
            if (!published) {
                session.cancel();
                awaitCaptureCompletion(session);
                return;
            }
            runCurrentSessionEdt(sessionId, callbacks::stateChanged);
            session.completion().whenComplete((audio, error) -> onCaptureComplete(sessionId, audio, error, callbacks));
        } catch (Exception | LinkageError e) {
            if (resetState(sessionId)) {
                runResetSessionEdt(sessionId, () -> {
                    callbacks.stateChanged();
                    callbacks.error(safeMessage(e));
                });
            }
            log.warn("Could not start STT capture: {}", ExceptionUtils.getMessage(e));
        } finally {
            if (preparationLease != null) {
                preparationLease.close();
            }
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
        boolean wasTranscribing;
        boolean whisperTranscribing;
        long sessionId;
        AudioCaptureSession session;
        synchronized (stateLock) {
            wasTranscribing = transcribing;
            whisperTranscribing = wasTranscribing
                    && activeSnapshot != null
                    && WhisperSpeechToTextProvider.ID.equals(activeSnapshot.providerId());
            activeCancellation.set(true);
            sessionId = sessionCounter.get();
            if (whisperTranscribing) {
                session = null;
            } else {
                sessionCounter.incrementAndGet();
                session = activeCapture;
                clearActiveStateLocked(true);
            }
        }
        if (whisperTranscribing) {
            runCurrentSessionEdt(sessionId, () -> {
                callbacks.status("Finishing Whisper.cpp cancellation...");
                callbacks.stateChanged();
            });
            return;
        }
        String message = wasTranscribing ? "Transcription canceled." : "Recording canceled.";
        runResetSessionEdt(sessionId, () -> {
            callbacks.status(message);
            callbacks.stateChanged();
        });
        if (session != null) {
            runCaptureControl(session::cancel);
        }
    }

    public void dispose() {
        disposeAsync();
    }

    public CompletableFuture<Void> disposeAsync() {
        synchronized (disposalLock) {
            if (!disposed.compareAndSet(false, true)) {
                return disposalFuture;
            }

            AudioCaptureSession session;
            synchronized (stateLock) {
                boolean transcriptionInProgress = transcribing;
                activeCancellation.set(true);
                sessionCounter.incrementAndGet();
                session = activeCapture;
                clearActiveStateLocked(!transcriptionInProgress);
            }
            CompletableFuture<Void> captureCleanup = cancelCaptureAsync(session);
            if (disabled) {
                disposalFuture = captureCleanup;
                return disposalFuture;
            }
            executor.shutdownNow();
            CompletableFuture<Void> executorCleanup = awaitTerminationAsync(executor);
            disposalFuture = CompletableFuture.allOf(captureCleanup, executorCleanup);
            return disposalFuture;
        }
    }

    private void onCaptureComplete(long sessionId, CapturedAudio audio, Throwable error, Callbacks callbacks) {
        synchronized (stateLock) {
            if (isStale(sessionId)) {
                deleteAudio(audio);
                return;
            }
            recording = false;
            activeCapture = null;
        }
        if (error != null) {
            if (resetState(sessionId)) {
                runResetSessionEdt(sessionId, () -> {
                    callbacks.stateChanged();
                    if (!(error instanceof CancellationException)) {
                        callbacks.error("Microphone access is unavailable. Check your input device and permissions.");
                    }
                });
            }
            return;
        }
        if (audio.durationMillis() < MicrophoneAudioCapture.MIN_DURATION_MILLIS) {
            deleteAudio(audio);
            if (resetState(sessionId)) {
                runResetSessionEdt(sessionId, () -> {
                    callbacks.stateChanged();
                    callbacks.error("No speech was recorded.");
                });
            }
            return;
        }
        if (audio.sizeBytes() > MicrophoneAudioCapture.MAX_CAPTURED_WAV_BYTES) {
            deleteAudio(audio);
            if (resetState(sessionId)) {
                runResetSessionEdt(sessionId, () -> {
                    callbacks.stateChanged();
                    callbacks.error("Recording is too large to transcribe.");
                });
            }
            return;
        }
        synchronized (stateLock) {
            if (isStale(sessionId)) {
                deleteAudio(audio);
                return;
            }
            transcribing = true;
        }
        runCurrentSessionEdt(sessionId, callbacks::stateChanged);
        try {
            CompletableFuture.runAsync(() -> transcribe(sessionId, audio, callbacks), executor);
        } catch (RejectedExecutionException e) {
            deleteAudio(audio);
            if (resetState(sessionId)) {
                runResetSessionEdt(sessionId, () -> callbacks.error("Speech to Text is not available in this window."));
            } else if (disposed.get()) {
                releaseWhisperLease();
            }
        }
    }

    private void transcribe(long sessionId, CapturedAudio audio, Callbacks callbacks) {
        String apiKey = null;
        try {
            SpeechToTextSessionSnapshot snapshot = activeSnapshot;
            SpeechToTextSettingsSnapshot current = settings.resolve();
            if (snapshot != null && WhisperSpeechToTextProvider.ID.equals(snapshot.providerId())) {
                runCurrentSessionEdt(sessionId, () -> callbacks.status("Validating Whisper.cpp model..."));
                settings.validateSelectedWhisperModelNow();
                current = settings.resolve();
            }
            if (!matchesSnapshot(snapshot, current)) {
                throw new SpeechToTextException("Speech-to-text settings changed; recording was not transcribed.");
            }
            CredentialSource requestCredentialSource = settings.credentialSource();
            String requiredEnvVar = current.provider().requiredEnvVar();
            if (StringUtils.isNotBlank(requiredEnvVar)) {
                apiKey = requestCredentialSource.resolveRequiredApiKey(requiredEnvVar);
                requestCredentialSource = new RequestCredentialSource(requestCredentialSource, requiredEnvVar, apiKey);
            }
            SpeechToTextProviderContext context = new SpeechToTextProviderContext(
                    current.baseUri(),
                    current.transcriptionUri(),
                    requestCredentialSource,
                    () -> isStale(sessionId) || activeCancellation.get(),
                    REQUEST_TIMEOUT,
                    current.localModelReference()
            );
            SpeechToTextResult result = current.provider().transcribe(
                    new SpeechToTextRequest(current.providerId(), current.model().id(), audio.path(), audio.durationMillis(), audio.sizeBytes()),
                    context
            );
            boolean cancellationRequested = activeCancellation.get();
            if (resetState(sessionId)) {
                runResetSessionEdt(sessionId, () -> {
                    callbacks.stateChanged();
                    if (cancellationRequested) {
                        callbacks.status("Transcription canceled.");
                    } else {
                        callbacks.transcript(result.text());
                    }
                });
            }
        } catch (Exception | LinkageError e) {
            boolean cancellationRequested = activeCancellation.get();
            String safeError = ProviderExceptionMapper.sanitizeMessage(safeMessage(e), apiKey);
            if (resetState(sessionId)) {
                runResetSessionEdt(sessionId, () -> {
                    callbacks.stateChanged();
                    if (cancellationRequested) {
                        callbacks.status("Transcription canceled.");
                    } else {
                        callbacks.error(safeError);
                    }
                });
            }
        } finally {
            deleteAudio(audio);
            if (disposed.get()) {
                releaseWhisperLease();
            }
        }
    }

    private WhisperModelUsageTracker.Lease acquireWhisperLease(SpeechToTextSettingsSnapshot settingsSnapshot) {
        if (!WhisperSpeechToTextProvider.ID.equals(settingsSnapshot.providerId())
                || settingsSnapshot.localModelReference() == null
                || whisperModelManagementService == null) {
            return null;
        }
        return whisperModelManagementService.usageTracker().acquire(settingsSnapshot.localModelReference().modelId());
    }

    private void releaseWhisperLease() {
        WhisperModelUsageTracker.Lease lease = activeWhisperLease;
        activeWhisperLease = null;
        if (lease != null) {
            lease.close();
        }
    }

    private CompletableFuture<Void> cancelCaptureAsync(AudioCaptureSession session) {
        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(
                        session::cancel,
                        command -> Thread.ofVirtual().name("chat4j-stt-capture-cancel").start(command)
                )
                .handle((ignored, error) -> null)
                .thenCompose(ignored -> session.completion().handle((audio, error) -> {
                    deleteAudio(audio);
                    return null;
                }));
    }

    private static CompletableFuture<Void> awaitTerminationAsync(ExecutorService executor) {
        return CompletableFuture.runAsync(
                () -> awaitTermination(executor),
                command -> Thread.ofVirtual().name("chat4j-stt-dispose-await").start(command)
        );
    }

    private static void awaitTermination(ExecutorService executor) {
        boolean restoreInterrupt = Thread.interrupted();
        try {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    restoreInterrupt = true;
                }
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitCaptureCompletion(AudioCaptureSession session) {
        try {
            session.completion().join();
        } catch (CancellationException | CompletionException ignored) {
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

    private boolean resetState(long sessionId) {
        synchronized (stateLock) {
            if (!sessionCounter.compareAndSet(sessionId, sessionId + 1)) {
                return false;
            }
            clearActiveStateLocked(true);
            return true;
        }
    }

    private void clearActiveStateLocked(boolean releaseLease) {
        active.set(false);
        recording = false;
        transcribing = false;
        activeCapture = null;
        activeSnapshot = null;
        activeCancellation = new AtomicBoolean();
        if (releaseLease) {
            releaseWhisperLease();
        }
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

    private String safeMessage(Throwable e) {
        Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
        return StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private void runResetSessionEdt(long sessionId, Runnable action) {
        long resetGeneration = sessionId + 1;
        if (sessionCounter.get() != resetGeneration || active.get() || disposed.get()) {
            return;
        }
        runEdt(() -> {
            if (sessionCounter.get() == resetGeneration && !active.get()) {
                action.run();
            }
        });
    }

    private void runCurrentSessionEdt(long sessionId, Runnable action) {
        if (isStale(sessionId) || disposed.get()) {
            return;
        }
        runEdt(() -> {
            if (!isStale(sessionId)) {
                action.run();
            }
        });
    }

    final void runEdt(Runnable action) {
        if (disposed.get()) {
            return;
        }
        Runnable guardedAction = () -> {
            if (!disposed.get()) {
                action.run();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            guardedAction.run();
        } else {
            SwingUtilities.invokeLater(guardedAction);
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

    private record RequestCredentialSource(
            CredentialSource delegate,
            String credentialId,
            String apiKey
    ) implements CredentialSource {
        @Override
        public boolean hasRequiredCredentials(String envVar) {
            return Objects.equals(credentialId, envVar) && StringUtils.isNotBlank(apiKey);
        }

        @Override
        public String resolveRequiredApiKey(String envVar) {
            if (!Objects.equals(credentialId, envVar)) {
                throw new IllegalStateException("Credential is not available for this transcription request.");
            }
            return apiKey;
        }

        @Override
        public ApiCredentialStatus credentialStatus(String envVar) {
            return Objects.equals(credentialId, envVar)
                    ? delegate.credentialStatus(envVar)
                    : new ApiCredentialStatus(ApiCredentialSource.MISSING, envVar, "");
        }

        @Override
        public String toString() {
            return "RequestCredentialSource[credentialId=%s, apiKey=****]".formatted(credentialId);
        }
    }

    public interface Callbacks {
        void stateChanged();

        void status(String message);

        void error(String message);

        void transcript(String text);

        void level(double rms, double peak);
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

    }
}
