package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.tts.audio.AudioPlaybackService;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
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
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, "fake");
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
    @DisplayName("Read aloud toggles active message and stops on second click")
    void readAloud_whenSameMessageClickedAgain_stopsActivePlayback() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, "fake");
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
    @DisplayName("Read aloud strips markdown syntax before synthesis")
    void readAloud_markdownText_synthesizesPlainSpeechText() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, "fake");
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
    @DisplayName("Read aloud uses provider default response format")
    void readAloud_providerDefaultResponseFormat_sendsProviderFormat() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, "fake");
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
    @DisplayName("Read aloud uses provider unavailable message")
    void readAloud_providerUnavailable_reportsProviderMessage() throws Exception {
        var settingsRepo = new SettingsRepository(Files.createTempFile("chat4j-tts-service", ".properties"));
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, "unavailable");
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
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, "fake");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        var subject = new TextToSpeechService(
                new TextToSpeechSettings(settingsRepo, new TextToSpeechProviderRegistry(List.of(new FakeProvider()))),
                new RecordingPlaybackService(),
                executor
        );
        subject.dispose();
        var error = new AtomicReference<String>();

        subject.readAloud("message", "hello", error::set);

        assertThat(error.get()).contains("Read aloud is not available");
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
