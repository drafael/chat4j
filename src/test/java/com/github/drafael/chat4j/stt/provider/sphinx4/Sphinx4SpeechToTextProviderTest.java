package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.github.drafael.chat4j.stt.audio.WavFileWriter;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.LocalSpeechToTextModelReference;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import edu.cmu.sphinx.api.Configuration;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class Sphinx4SpeechToTextProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Sphinx4 provider feeds raw PCM, not WAV container bytes, to recognizer")
    void transcribe_whenAudioIsWav_stripsHeaderBeforeRecognizerInput() throws Exception {
        Path model = createModel(tempDir.resolve("model"));
        Path audio = tempDir.resolve("recording.wav");
        writeWav(audio, new byte[]{1, 0, 2, 0, 3, 0, 4, 0});
        var adapter = new CapturingRecognizerAdapter();
        var provider = new Sphinx4SpeechToTextProvider(new Sphinx4ModelValidator(), adapter);
        var context = new SpeechToTextProviderContext(
                null,
                null,
                CredentialSource.SYSTEM,
                SpeechToTextProviderContext.CancellationToken.never(),
                Duration.ofSeconds(30),
                new LocalSpeechToTextModelReference("local-test", model, "fingerprint"),
                tempDir.resolve("stt-temp")
        );

        var result = provider.transcribe(new SpeechToTextRequest(Sphinx4SpeechToTextProvider.ID, "local-test", audio, 100, Files.size(audio)), context);

        assertThat(result.text()).isEqualTo("hello world");
        assertThat(adapter.firstBytes).containsExactly(1, 0, 2, 0);
        assertThat(new String(adapter.firstBytes)).isNotEqualTo("RIFF");
    }

    @Test
    @DisplayName("Sphinx4 provider deletes converted temp WAV after transcription")
    void transcribe_whenAudioRequiresConversion_deletesConvertedTempFile() throws Exception {
        Path model = createModel(tempDir.resolve("model-convert"), 8_000);
        Path audio = tempDir.resolve("recording-16k.wav");
        writeWav(audio, 16_000, new byte[]{1, 0, 2, 0, 3, 0, 4, 0});
        Path sttTemp = tempDir.resolve("stt-temp-convert");
        var provider = new Sphinx4SpeechToTextProvider(new Sphinx4ModelValidator(), new CapturingRecognizerAdapter());
        var context = new SpeechToTextProviderContext(
                null,
                null,
                CredentialSource.SYSTEM,
                SpeechToTextProviderContext.CancellationToken.never(),
                Duration.ofSeconds(30),
                new LocalSpeechToTextModelReference("local-test", model, "fingerprint"),
                sttTemp
        );

        var result = provider.transcribe(new SpeechToTextRequest(Sphinx4SpeechToTextProvider.ID, "local-test", audio, 100, Files.size(audio)), context);

        assertThat(result.text()).isEqualTo("hello world");
        try (var files = Files.list(sttTemp)) {
            assertThat(files).isEmpty();
        }
    }

    private Path createModel(Path model) throws Exception {
        return createModel(model, 16_000);
    }

    private Path createModel(Path model, int sampleRateHz) throws Exception {
        Files.createDirectories(model.resolve("acoustic"));
        Files.createDirectories(model.resolve("dict"));
        Files.createDirectories(model.resolve("lm"));
        Files.writeString(model.resolve("acoustic/mdef"), "mdef");
        Files.writeString(model.resolve("acoustic/means"), "means");
        Files.writeString(model.resolve("acoustic/variances"), "variances");
        Files.writeString(model.resolve("acoustic/transition_matrices"), "transition");
        Files.writeString(model.resolve("dict/model.dict"), "WORD W ER D");
        Files.writeString(model.resolve("lm/model.lm"), "lm");
        var metadata = new Sphinx4ModelMetadata(
                1,
                "local-test",
                "Local Test",
                "Custom",
                List.of(),
                "acoustic",
                "dict/model.dict",
                "lm/model.lm",
                sampleRateHz,
                List.of("acoustic/mdef", "dict/model.dict", "lm/model.lm"),
                "local-test",
                1,
                false
        );
        new Sphinx4ModelValidator().writeMetadata(model, metadata);
        return model;
    }

    private void writeWav(Path path, byte[] pcm) throws Exception {
        writeWav(path, 16_000, pcm);
    }

    private void writeWav(Path path, int sampleRateHz, byte[] pcm) throws Exception {
        var format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRateHz, 16, 1, 2, sampleRateHz, false);
        try (var writer = new WavFileWriter(path, format)) {
            writer.write(pcm, 0, pcm.length);
            writer.finalizeAudio(100);
        }
    }

    private static final class CapturingRecognizerAdapter implements Sphinx4RecognizerAdapter {
        private byte[] firstBytes = new byte[0];
        private boolean returned;

        @Override
        public RecognizerSession create(Configuration configuration) {
            return new RecognizerSession() {
                @Override
                public void startRecognition(InputStream inputStream) throws Exception {
                    firstBytes = inputStream.readNBytes(4);
                    byte[] remaining = inputStream.readAllBytes();
                    assertThat(remaining).isNotEmpty();
                }

                @Override
                public String nextHypothesis() {
                    if (returned) {
                        return null;
                    }
                    returned = true;
                    return "hello world";
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
