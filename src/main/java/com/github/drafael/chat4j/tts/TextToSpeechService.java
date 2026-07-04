package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.util.Collections.emptyList;

@Slf4j
public class TextToSpeechService {

    private final TextToSpeechSettings settings;
    private final AudioPlaybackService playbackService;
    private final ExecutorService executor;
    private final AtomicLong requestCounter = new AtomicLong();
    private final AtomicReference<String> activeMessageKey = new AtomicReference<>("");
    private volatile Future<?> activeTask;

    public TextToSpeechService(TextToSpeechSettings settings, AudioPlaybackService playbackService) {
        this(settings, playbackService, Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat4j-tts-", 0).factory()));
    }

    TextToSpeechService(TextToSpeechSettings settings, AudioPlaybackService playbackService, ExecutorService executor) {
        this.settings = settings;
        this.playbackService = playbackService;
        this.executor = executor;
    }

    public static TextToSpeechService createDefault(SettingsRepository settingsRepo) {
        TextToSpeechProviderRegistry registry = TextToSpeechProviderRegistry.createDefault();
        return new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, registry),
                new JavaSoundAudioPlaybackService()
        );
    }

    public static TextToSpeechService disabled() {
        return new DisabledTextToSpeechService();
    }

    public boolean isReadAloudAvailable() {
        try {
            TextToSpeechSettings.Selection selection = settings.resolve();
            return selection.enabled() && selection.available();
        } catch (Exception e) {
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
        String normalizedText = StringUtils.trimToEmpty(text);
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
        } catch (Exception e) {
            report(errorHandler, "Unable to read Text to Speech settings.");
            return;
        }
        if (!selection.enabled()) {
            report(errorHandler, "Text to Speech is turned off.");
            return;
        }
        if (!selection.available()) {
            report(errorHandler, "%s requires %s.".formatted(selection.provider().displayName(), selection.provider().requiredEnvVar()));
            return;
        }

        stop();
        report(statusHandler, "Preparing read aloud...");
        long requestId = requestCounter.incrementAndGet();
        activeMessageKey.set(normalizedMessageKey);
        run(stateChangeHandler);
        try {
            activeTask = executor.submit(() -> synthesizeAndPlay(requestId, normalizedMessageKey, normalizedText, selection, errorHandler, statusHandler, stateChangeHandler));
        } catch (RejectedExecutionException e) {
            activeMessageKey.set("");
            run(stateChangeHandler);
            report(errorHandler, "Read aloud is not available in this window. Please reopen the conversation window and try again.");
        }
    }

    public void stop() {
        requestCounter.incrementAndGet();
        activeMessageKey.set("");
        Future<?> task = activeTask;
        activeTask = null;
        if (task != null) {
            task.cancel(true);
        }
        playbackService.stop();
    }

    public void dispose() {
        stop();
        executor.shutdownNow();
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
        try {
            TextToSpeechProvider provider = selection.provider();
            String responseFormat = GroqTextToSpeechProvider.ID.equals(provider.id()) ? "wav" : "mp3";
            for (String chunk : speechChunks(text, provider.maxInputCharacters())) {
                if (isStale(requestId, messageKey)) {
                    return;
                }
                TextToSpeechAudio audio = provider.synthesize(new TextToSpeechRequest(
                        provider.id(),
                        selection.model().id(),
                        selection.voice().id(),
                        chunk,
                        responseFormat
                ));
                if (isStale(requestId, messageKey)) {
                    return;
                }
                report(statusHandler, "Playing read aloud...");
                playbackService.play(audio);
            }
            report(statusHandler, "Read aloud complete.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!isStale(requestId, messageKey)) {
                log.warn("Read aloud failed: {}", e.toString());
                report(errorHandler, "Read aloud failed: %s".formatted(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName())));
            }
        } finally {
            if (!isStale(requestId, messageKey) && activeMessageKey.compareAndSet(messageKey, "")) {
                run(stateChangeHandler);
            }
        }
    }

    private boolean isStale(long requestId, String messageKey) {
        return requestId != requestCounter.get() || !Objects.equals(activeMessageKey.get(), messageKey);
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
        int punctuation = Math.max(
                Math.max(text.lastIndexOf('.', maxEnd), text.lastIndexOf('!', maxEnd)),
                text.lastIndexOf('?', maxEnd)
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

    private static final class DisabledTextToSpeechService extends TextToSpeechService {
        private DisabledTextToSpeechService() {
            super(
                    new TextToSpeechSettings(
                            new SettingsRepository(Path.of(System.getProperty("java.io.tmpdir"), "chat4j-disabled-tts.properties")),
                            new TextToSpeechProviderRegistry(emptyList())
                    ),
                    new AudioPlaybackService() {
                        @Override
                        public void play(TextToSpeechAudio audio) {
                        }

                        @Override
                        public void stop() {
                        }
                    },
                    Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat4j-disabled-tts-", 0).factory())
            );
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
        public void dispose() {
        }
    }
}
