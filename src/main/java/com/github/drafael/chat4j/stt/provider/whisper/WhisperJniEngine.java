package com.github.drafael.chat4j.stt.provider.whisper;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import io.github.freshsupasulley.whisperjni.WhisperFullParams;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class WhisperJniEngine implements WhisperEngine {

    private static final AudioFormat TARGET_FORMAT = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16_000f, 16, 1, 2, 16_000f, false);

    private final WhisperNativeRuntime nativeRuntime;

    public WhisperJniEngine(WhisperNativeRuntime nativeRuntime) {
        this.nativeRuntime = nativeRuntime;
    }

    public WhisperJniEngine() {
        this(WhisperNativeRuntime.shared());
    }

    @Override
    public String transcribe(Path modelFile, Path wavFile, WhisperTranscriptionOptions options, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        checkCanceled(cancellationToken);
        if (!nativeRuntime.ensureLoaded()) {
            throw new SpeechToTextException(nativeRuntime.statusMessage());
        }
        float[] samples = readSamples(wavFile);
        checkCanceled(cancellationToken);
        return nativeRuntime.callWithNativeLock(() -> transcribeWithNativeLock(modelFile, samples, options, cancellationToken));
    }

    private String transcribeWithNativeLock(Path modelFile, float[] samples, WhisperTranscriptionOptions options, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        try (WhisperBinding binding = nativeRuntime.bindingFactory().create()) {
            checkCanceled(cancellationToken);
            WhisperContextHandle context = null;
            Throwable primary = null;
            try {
                context = binding.init(modelFile);
                if (context == null) {
                    throw new SpeechToTextException("Whisper.cpp transcription failed.");
                }
                checkCanceled(cancellationToken);
                WhisperFullParams params = params(options);
                int result = binding.full(context, params, samples, samples.length);
                if (result != 0) {
                    throw new SpeechToTextException("Whisper.cpp transcription failed.");
                }
                checkCanceled(cancellationToken);
                String text = readText(binding, context);
                if (StringUtils.isBlank(text)) {
                    throw new SpeechToTextException("No speech was recognized.");
                }
                return text;
            } catch (Throwable t) {
                primary = t;
                throw t;
            } finally {
                if (context != null) {
                    closeContext(context, primary);
                }
            }
        }
    }

    private WhisperFullParams params(WhisperTranscriptionOptions options) {
        WhisperFullParams params = new WhisperFullParams();
        params.nThreads = options.threads();
        params.noTimestamps = true;
        params.printProgress = false;
        params.printRealtime = false;
        params.printTimestamps = false;
        params.translate = false;
        params.noContext = true;
        params.detectLanguage = false;
        params.language = options.englishOnly() ? "en" : null;
        return params;
    }

    private String readText(WhisperBinding binding, WhisperContextHandle context) throws SpeechToTextException {
        int segments = binding.fullNSegments(context);
        if (segments < 0) {
            throw new SpeechToTextException("Whisper.cpp transcription failed.");
        }
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < segments; index++) {
            String segment = binding.fullGetSegmentText(context, index);
            if (segment == null) {
                throw new SpeechToTextException("Whisper.cpp transcription failed.");
            }
            if (StringUtils.isNotBlank(segment)) {
                parts.add(segment.trim());
            }
        }
        return StringUtils.normalizeSpace(String.join(" ", parts));
    }

    private static float[] readSamples(Path wavFile) throws SpeechToTextException {
        try (AudioInputStream original = AudioSystem.getAudioInputStream(wavFile.toFile())) {
            AudioInputStream converted = AudioSystem.isConversionSupported(TARGET_FORMAT, original.getFormat())
                    ? AudioSystem.getAudioInputStream(TARGET_FORMAT, original)
                    : original;
            AudioFormat format = converted.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    || Math.round(format.getSampleRate()) != 16_000
                    || format.getChannels() != 1
                    || format.getSampleSizeInBits() != 16
                    || format.isBigEndian()) {
                throw new SpeechToTextException("Recorded audio could not be prepared for Whisper.cpp.");
            }
            byte[] bytes = readAllBytes(converted);
            if (bytes.length % 2 != 0) {
                throw new SpeechToTextException("Recorded audio could not be prepared for Whisper.cpp.");
            }
            float[] samples = new float[bytes.length / 2];
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < samples.length; index++) {
                samples[index] = buffer.getShort() / 32768.0f;
            }
            return samples;
        } catch (SpeechToTextException e) {
            throw e;
        } catch (Exception e) {
            throw new SpeechToTextException("Recorded audio could not be prepared for Whisper.cpp.", e);
        }
    }

    private static byte[] readAllBytes(AudioInputStream input) throws Exception {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void checkCanceled(SpeechToTextProviderContext.CancellationToken cancellationToken) throws SpeechToTextException {
        if (cancellationToken != null && cancellationToken.cancelled()) {
            throw new SpeechToTextException("Transcription canceled.");
        }
    }

    private void closeContext(WhisperContextHandle context, Throwable primary) {
        try {
            context.close();
        } catch (Exception | LinkageError e) {
            if (primary == null) {
                log.warn("Whisper context close failed: {}", StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            } else {
                log.debug("Whisper context close failed after primary error: {}", StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            }
        }
    }
}
