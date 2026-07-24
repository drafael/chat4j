package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.audio.AudioPlaybackService;
import com.github.drafael.chat4j.tts.audio.JavaSoundAudioPlaybackService;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

@Slf4j
public class TextToSpeechService {

    private static final Pattern FENCED_CODE_BOUNDARY = Pattern.compile("(?m)^\\s*(```|~~~)[\\w+-]*\\s*$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[([^]\\n]*)]\\([^)]*\\)");
    private static final Pattern REFERENCE_LINK = Pattern.compile("\\[([^]\\n]+)]\\[[^]\\n]*]");
    private static final Pattern STRONG_MARKER = Pattern.compile("(\\*\\*|__)(\\S(?:.*?\\S)?)\\1");
    private static final Pattern STRIKE_MARKER = Pattern.compile("~~(\\S(?:.*?\\S)?)~~");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern ITALIC_ASTERISK = Pattern.compile("(?<!\\*)\\*(?!\\*)(\\S(?:.*?\\S)?)(?<!\\*)\\*(?!\\*)");
    private static final Pattern HEADING_MARKER = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s+");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s{0,3}(?:[-*+]\\s+|\\d+[.)]\\s+)");
    private static final Pattern BLOCKQUOTE_MARKER = Pattern.compile("(?m)^\\s{0,3}>\\s?");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("(?m)^\\s*(?:-{3,}|_{3,}|\\*{3,})\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("(?m)^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?\\s*$");
    private static final Pattern ESCAPED_MARKDOWN_CHARACTER = Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|>])");

    private final TextToSpeechSettings settings;
    private final AudioPlaybackService playbackService;
    private final ExecutorService executor;
    private final AtomicLong requestCounter = new AtomicLong();
    private final AtomicReference<String> activeMessageKey = new AtomicReference<>("");
    private final AtomicReference<SynthesisOperation> activeSynthesisOperation = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> playbackCleanup =
            new AtomicReference<>(CompletableFuture.completedFuture(null));
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final Object disposalLock = new Object();
    private volatile CompletableFuture<Void> disposalFuture = CompletableFuture.completedFuture(null);
    private final AtomicReference<Future<?>> activeTask = new AtomicReference<>();

    public TextToSpeechService(TextToSpeechSettings settings, AudioPlaybackService playbackService) {
        this(settings, playbackService, Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat4j-tts-", 0).factory()));
    }

    TextToSpeechService(TextToSpeechSettings settings, AudioPlaybackService playbackService, ExecutorService executor) {
        this.settings = settings;
        this.playbackService = playbackService;
        this.executor = executor;
    }

    public static TextToSpeechService createDefault(
            SettingsRepository settingsRepo,
            CredentialResolver credentialResolver,
            Map<String, String> subprocessEnvironment
    ) {
        TextToSpeechProviderRegistry registry = TextToSpeechProviderRegistry.createDefault(
                credentialResolver,
                subprocessEnvironment
        );
        return new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, registry),
                new JavaSoundAudioPlaybackService()
        );
    }

    public static TextToSpeechService disabled() {
        return new DisabledTextToSpeechService();
    }

    public boolean isReadAloudAvailable() {
        if (disposed.get()) {
            return false;
        }
        try {
            TextToSpeechSettings.Selection selection = settings.resolve();
            return selection.enabled() && selection.available();
        } catch (Exception | LinkageError e) {
            log.warn("Failed to read Text to Speech availability: {}", e.toString());
            return false;
        }
    }

    public boolean isReadAloudActive(String messageKey) {
        return Strings.CS.equals(activeMessageKey.get(), StringUtils.defaultString(messageKey));
    }

    public void readAloud(String messageKey, String text, Consumer<String> errorHandler) {
        readAloud(messageKey, text, errorHandler, null, null);
    }

    public void readAloud(String messageKey, String text, Consumer<String> errorHandler, Consumer<String> statusHandler) {
        readAloud(messageKey, text, errorHandler, statusHandler, null);
    }

    public void readAloud(
            String messageKey,
            String text,
            Consumer<String> errorHandler,
            Consumer<String> statusHandler,
            Runnable stateChangeHandler
    ) {
        if (disposed.get()) {
            report(errorHandler, "Read aloud is not available in this window. Please reopen the conversation window and try again.");
            return;
        }
        String normalizedText = speechText(text);
        String normalizedMessageKey = StringUtils.defaultString(messageKey);
        if (isReadAloudActive(normalizedMessageKey)) {
            stop();
            run(stateChangeHandler);
            report(statusHandler, "Stopped read aloud.");
            return;
        }
        if (normalizedText.isBlank()) {
            report(statusHandler, "No text to read aloud.");
            return;
        }

        TextToSpeechSettings.Selection selection;
        try {
            selection = settings.resolve();
        } catch (Exception | LinkageError e) {
            report(errorHandler, "Unable to read Text to Speech settings.");
            return;
        }
        if (!selection.enabled()) {
            report(errorHandler, "Text to Speech is turned off.");
            return;
        }
        if (!selection.available()) {
            report(errorHandler, selection.provider().unavailableMessage());
            return;
        }

        stop();
        report(statusHandler, "Preparing read aloud...");
        long requestId = requestCounter.incrementAndGet();
        activeMessageKey.set(normalizedMessageKey);
        run(stateChangeHandler);
        FutureTask<Void> task = new FutureTask<>(() -> {
            synthesizeAndPlay(
                    requestId,
                    normalizedMessageKey,
                    normalizedText,
                    selection,
                    errorHandler,
                    statusHandler,
                    stateChangeHandler
            );
            return null;
        }) {
            @Override
            protected void done() {
                activeTask.compareAndSet(this, null);
                if (isCancelled()) {
                    return;
                }
                try {
                    get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof Error error) {
                        throw error;
                    }
                }
            }
        };
        activeTask.set(task);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            activeTask.compareAndSet(task, null);
            task.cancel(false);
            activeMessageKey.set("");
            run(stateChangeHandler);
            report(errorHandler, "Read aloud is not available in this window. Please reopen the conversation window and try again.");
        }
    }

    public void stop() {
        requestCounter.incrementAndGet();
        activeMessageKey.set("");
        SynthesisOperation synthesisOperation = activeSynthesisOperation.getAndSet(null);
        if (synthesisOperation != null) {
            synthesisOperation.cancel();
        }
        cancel(activeTask.getAndSet(null));
        CompletableFuture<Void> stoppedPlayback;
        try {
            stoppedPlayback = playbackService.stopAsync().exceptionally(error -> {
                log.warn("Could not stop audio playback: {}", error.getClass().getSimpleName());
                return null;
            });
        } catch (RuntimeException | LinkageError e) {
            log.warn("Could not stop audio playback: {}", e.getClass().getSimpleName());
            stoppedPlayback = CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> cleanup = stoppedPlayback;
        CompletableFuture<Void> trackedCleanup = playbackCleanup.updateAndGet(
                current -> CompletableFuture.allOf(current, cleanup)
        );
        trackedCleanup.whenComplete((ignored, error) -> playbackCleanup.compareAndSet(
                trackedCleanup,
                CompletableFuture.completedFuture(null)
        ));
    }

    public void dispose() {
        disposeAsync();
    }

    public CompletableFuture<Void> disposeAsync() {
        synchronized (disposalLock) {
            if (!disposed.compareAndSet(false, true)) {
                return disposalFuture;
            }
            stop();
            executor.shutdownNow();
            CompletableFuture<Void> executorCleanup = awaitTerminationAsync(
                    executor,
                    "chat4j-tts-dispose-await"
            );
            disposalFuture = CompletableFuture.allOf(playbackCleanup.get(), executorCleanup);
            return disposalFuture;
        }
    }

    private void synthesizeAndPlay(
            long requestId,
            String messageKey,
            String text,
            TextToSpeechSettings.Selection selection,
            Consumer<String> errorHandler,
            Consumer<String> statusHandler,
            Runnable stateChangeHandler
    ) {
        String apiKey = null;
        try {
            TextToSpeechProvider provider = selection.provider();
            if (StringUtils.isNotBlank(provider.requiredEnvVar())) {
                apiKey = provider.apiKey();
            }
            String responseFormat = provider.defaultResponseFormat();
            List<String> chunks = speechChunks(text, provider.maxInputCharacters());
            SynthesisOperation audioOperation = null;
            try {
                for (int index = 0; index < chunks.size(); index++) {
                    if (isStale(requestId, messageKey)) {
                        return;
                    }
                    if (audioOperation == null) {
                        audioOperation = newSynthesisOperation(provider, selection, chunks.get(index), responseFormat, apiKey);
                        activeSynthesisOperation.set(audioOperation);
                        if (isStale(requestId, messageKey)) {
                            cancel(audioOperation);
                            return;
                        }
                        audioOperation.start();
                    }
                    TextToSpeechAudio audio = audioOperation.result().get();
                    if (isStale(requestId, messageKey)) {
                        return;
                    }
                    SynthesisOperation nextAudioOperation = index + 1 < chunks.size()
                            ? newSynthesisOperation(provider, selection, chunks.get(index + 1), responseFormat, apiKey)
                            : null;
                    activeSynthesisOperation.set(nextAudioOperation);
                    if (isStale(requestId, messageKey)) {
                        cancel(nextAudioOperation);
                        awaitSettlement(nextAudioOperation);
                        return;
                    }
                    if (nextAudioOperation != null) {
                        nextAudioOperation.start();
                    }
                    audioOperation = nextAudioOperation;
                    report(statusHandler, "Playing read aloud...");
                    playbackService.play(audio, () -> isStale(requestId, messageKey));
                }
            } finally {
                cancel(audioOperation);
                activeSynthesisOperation.set(null);
                awaitSettlement(audioOperation);
            }
            if (!isStale(requestId, messageKey)) {
                report(statusHandler, "Read aloud complete.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError e) {
            rethrowUnexpectedError(e);
            if (!isStale(requestId, messageKey)) {
                String safeMessage = ProviderExceptionMapper.sanitizeMessage(e, apiKey);
                log.warn("Read aloud failed: {}", safeMessage);
                report(errorHandler, "Read aloud failed: %s".formatted(StringUtils.defaultIfBlank(safeMessage, e.getClass().getSimpleName())));
            }
        } finally {
            if (!isStale(requestId, messageKey) && activeMessageKey.compareAndSet(messageKey, "")) {
                run(stateChangeHandler);
            }
        }
    }

    private SynthesisOperation newSynthesisOperation(
            TextToSpeechProvider provider,
            TextToSpeechSettings.Selection selection,
            String chunk,
            String responseFormat,
            String apiKey
    ) {
        var result = new CompletableFuture<TextToSpeechAudio>();
        var cancelled = new AtomicBoolean();
        Thread thread = Thread.ofVirtual().name("chat4j-tts-synthesis").unstarted(() -> {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                result.cancel(false);
                return;
            }
            try {
                var request = new TextToSpeechRequest(
                        provider.id(),
                        selection.model().id(),
                        selection.voice().id(),
                        chunk,
                        responseFormat
                );
                result.complete(provider.synthesize(request, apiKey));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return new SynthesisOperation(thread, result, cancelled);
    }

    private static void rethrowUnexpectedError(Throwable failure) {
        Throwable cause = failure instanceof ExecutionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        if (cause instanceof Error error && !(error instanceof LinkageError)) {
            throw error;
        }
    }

    private static CompletableFuture<Void> awaitTerminationAsync(ExecutorService executor, String threadName) {
        return CompletableFuture.runAsync(
                () -> awaitTermination(executor),
                command -> Thread.ofVirtual().name(threadName).start(command)
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

    private static void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static void cancel(SynthesisOperation operation) {
        if (operation != null) {
            operation.cancel();
        }
    }

    private static void awaitSettlement(SynthesisOperation operation) {
        if (operation == null) {
            return;
        }
        boolean restoreInterrupt = Thread.interrupted();
        try {
            while (operation.thread().isAlive()) {
                try {
                    operation.thread().join();
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

    private boolean isStale(long requestId, String messageKey) {
        return requestId != requestCounter.get() || !Objects.equals(activeMessageKey.get(), messageKey);
    }

    private static String speechText(String text) {
        String speech = StringUtils.defaultString(text);
        speech = HORIZONTAL_RULE.matcher(speech).replaceAll(" ");
        speech = TABLE_SEPARATOR.matcher(speech).replaceAll(" ");
        speech = FENCED_CODE_BOUNDARY.matcher(speech).replaceAll(" ");
        speech = HEADING_MARKER.matcher(speech).replaceAll("");
        speech = LIST_MARKER.matcher(speech).replaceAll("");
        speech = BLOCKQUOTE_MARKER.matcher(speech).replaceAll("");
        speech = MARKDOWN_LINK.matcher(speech).replaceAll(TextToSpeechService::firstGroup);
        speech = REFERENCE_LINK.matcher(speech).replaceAll(TextToSpeechService::firstGroup);
        speech = STRONG_MARKER.matcher(speech).replaceAll(TextToSpeechService::secondGroup);
        speech = STRIKE_MARKER.matcher(speech).replaceAll(TextToSpeechService::firstGroup);
        speech = INLINE_CODE.matcher(speech).replaceAll(TextToSpeechService::firstGroup);
        speech = ITALIC_ASTERISK.matcher(speech).replaceAll(TextToSpeechService::firstGroup);
        speech = ESCAPED_MARKDOWN_CHARACTER.matcher(speech).replaceAll(TextToSpeechService::firstGroup);
        speech = speech.replace('|', ' ');
        return StringUtils.normalizeSpace(speech);
    }

    private static String firstGroup(MatchResult match) {
        return Matcher.quoteReplacement(match.group(1));
    }

    private static String secondGroup(MatchResult match) {
        return Matcher.quoteReplacement(match.group(2));
    }

    private static List<String> speechChunks(String text, int maxCharacters) {
        String normalized = StringUtils.normalizeSpace(text);
        if (maxCharacters <= 0 || normalized.length() <= maxCharacters) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + maxCharacters, normalized.length());
            int split = splitPoint(normalized, start, end);
            chunks.add(normalized.substring(start, split).trim());
            start = split;
            while (start < normalized.length() && Character.isWhitespace(normalized.charAt(start))) {
                start++;
            }
        }
        return chunks.stream()
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private static int splitPoint(String text, int start, int maxEnd) {
        if (maxEnd >= text.length()) {
            return text.length();
        }
        int searchEnd = maxEnd - 1;
        int punctuation = Math.max(
                Math.max(text.lastIndexOf('.', searchEnd), text.lastIndexOf('!', searchEnd)),
                text.lastIndexOf('?', searchEnd)
        );
        if (punctuation > start) {
            return punctuation + 1;
        }
        int whitespace = text.lastIndexOf(' ', maxEnd);
        return whitespace > start ? whitespace : maxEnd;
    }

    private static void report(Consumer<String> errorHandler, String message) {
        if (errorHandler != null) {
            errorHandler.accept(message);
        }
    }

    private static void run(Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    private record SynthesisOperation(
            Thread thread,
            CompletableFuture<TextToSpeechAudio> result,
            AtomicBoolean cancelled
    ) {
        private void start() {
            if (cancelled.get()) {
                result.cancel(false);
                return;
            }
            thread.start();
        }

        private void cancel() {
            cancelled.set(true);
            result.cancel(false);
            thread.interrupt();
        }
    }

    private static final class DisabledTextToSpeechService extends TextToSpeechService {
        private DisabledTextToSpeechService() {
            super(null, null, null);
        }

        @Override
        public boolean isReadAloudAvailable() {
            return false;
        }

        @Override
        public boolean isReadAloudActive(String messageKey) {
            return false;
        }

        @Override
        public void readAloud(String messageKey, String text, Consumer<String> errorHandler) {
        }

        @Override
        public void readAloud(
                String messageKey,
                String text,
                Consumer<String> errorHandler,
                Consumer<String> statusHandler,
                Runnable stateChangeHandler
        ) {
        }

        @Override
        public void stop() {
        }

        @Override
        public CompletableFuture<Void> disposeAsync() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void dispose() {
        }
    }
}
