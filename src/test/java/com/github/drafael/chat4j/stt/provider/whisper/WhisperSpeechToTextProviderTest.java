package com.github.drafael.chat4j.stt.provider.whisper;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.LocalSpeechToTextModelReference;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhisperSpeechToTextProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Whisper provider advertises local no-credential metadata")
    void metadata_whenCreated_reportsLocalProvider() {
        var subject = new WhisperSpeechToTextProvider((modelFile, wavFile, options, cancellationToken) -> "hello");

        assertThat(subject.id()).isEqualTo("whisper");
        assertThat(subject.displayName()).isEqualTo("Whisper.cpp");
        assertThat(subject.requiredEnvVar()).isBlank();
        assertThat(subject.available(CredentialSource.SYSTEM)).isTrue();
        assertThat(subject.supportsLocalModels()).isTrue();
        assertThat(subject.defaultModel()).isNull();
        assertThat(subject.bundledModels()).isEmpty();
        assertThat(subject.fetchModels(context(null))).isEmpty();
    }

    @Test
    @DisplayName("Missing local model reference fails before native work")
    void transcribe_whenReferenceMissing_fails() throws Exception {
        var subject = new WhisperSpeechToTextProvider((modelFile, wavFile, options, cancellationToken) -> "hello");
        Path wav = Files.writeString(tempDir.resolve("audio.wav"), "not-a-real-wav");

        assertThatThrownBy(() -> subject.transcribe(request(wav), context(null)))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("Select an installed Whisper.cpp model to enable transcription.");
    }

    @Test
    @DisplayName("Valid local model reference calls engine with model.bin")
    void transcribe_whenReferenceValid_callsEngine() throws Exception {
        Path modelDir = Files.createDirectories(tempDir.resolve("model"));
        Files.writeString(modelDir.resolve("model.bin"), "fake");
        Path wav = Files.writeString(tempDir.resolve("audio.wav"), "fake wav");
        var called = new boolean[] {false};
        var subject = new WhisperSpeechToTextProvider((modelFile, wavFile, options, cancellationToken) -> {
            called[0] = true;
            assertThat(modelFile).isEqualTo(modelDir.resolve("model.bin").toAbsolutePath().normalize());
            assertThat(wavFile).isEqualTo(wav);
            assertThat(options.englishOnly()).isTrue();
            return " hello   world ";
        });

        var result = subject.transcribe(request(wav), context(new LocalSpeechToTextModelReference("tiny.en", modelDir, "fingerprint")));

        assertThat(called[0]).isTrue();
        assertThat(result.text()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("Provider rejects model id mismatch")
    void transcribe_whenModelIdMismatch_rejectsBeforeEngine() throws Exception {
        Path modelDir = Files.createDirectories(tempDir.resolve("model"));
        Files.writeString(modelDir.resolve("model.bin"), "fake");
        Path wav = Files.writeString(tempDir.resolve("audio.wav"), "fake wav");
        var subject = new WhisperSpeechToTextProvider((modelFile, wavFile, options, cancellationToken) -> "hello");

        assertThatThrownBy(() -> subject.transcribe(request(wav), context(new LocalSpeechToTextModelReference("base", modelDir, "fingerprint"))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("Selected Whisper.cpp model changed before transcription.");
    }

    @Test
    @DisplayName("Cancellation before native work fails without calling engine")
    void transcribe_whenCanceledBeforeEngine_fails() throws Exception {
        Path modelDir = Files.createDirectories(tempDir.resolve("model"));
        Files.writeString(modelDir.resolve("model.bin"), "fake");
        Path wav = Files.writeString(tempDir.resolve("audio.wav"), "fake wav");
        var subject = new WhisperSpeechToTextProvider((modelFile, wavFile, options, cancellationToken) -> {
            throw new AssertionError("engine should not run");
        });

        assertThatThrownBy(() -> subject.transcribe(request(wav), context(new LocalSpeechToTextModelReference("tiny.en", modelDir, "fingerprint"), true)))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("Transcription canceled.");
    }

    private SpeechToTextRequest request(Path wav) {
        return new SpeechToTextRequest("whisper", "tiny.en", wav, 1000, 10);
    }

    private SpeechToTextProviderContext context(LocalSpeechToTextModelReference reference) {
        return context(reference, false);
    }

    private SpeechToTextProviderContext context(LocalSpeechToTextModelReference reference, boolean canceled) {
        return new SpeechToTextProviderContext(null, null, CredentialSource.SYSTEM, () -> canceled, Duration.ofSeconds(1), reference);
    }
}
