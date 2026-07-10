package com.github.drafael.chat4j.stt.provider.vosk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.LocalSpeechToTextModelReference;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.apache.commons.lang3.StringUtils;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import static java.util.Collections.emptyList;

public class VoskSpeechToTextProvider implements SpeechToTextProvider {

    public static final String ID = "vosk";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Vosk";
    }

    @Override
    public String requiredEnvVar() {
        return "";
    }

    @Override
    public SpeechToTextCatalogItem defaultModel() {
        return null;
    }

    @Override
    public List<SpeechToTextCatalogItem> bundledModels() {
        return emptyList();
    }

    @Override
    public boolean supportsLocalModels() {
        return true;
    }

    @Override
    public boolean available(CredentialSource credentialSource) {
        return true;
    }

    @Override
    public String availableMessage() {
        return "Using Vosk locally.";
    }

    @Override
    public String unavailableMessage() {
        return "Download or add a Vosk model to enable transcription.";
    }

    @Override
    public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
        return emptyList();
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception {
        LocalSpeechToTextModelReference modelReference = context.localModelReference();
        if (modelReference == null) {
            throw new SpeechToTextException("Select an installed Vosk model to enable transcription.");
        }
        if (!Files.isDirectory(modelReference.directory())) {
            throw new SpeechToTextException("Selected Vosk model is missing.");
        }
        try {
            return new SpeechToTextResult(transcribeWithVosk(request, context, modelReference));
        } catch (LinkageError e) {
            throw new SpeechToTextException("Vosk native runtime is unavailable in this package or on this platform.", e);
        }
    }

    private String transcribeWithVosk(
            SpeechToTextRequest request,
            SpeechToTextProviderContext context,
            LocalSpeechToTextModelReference modelReference
    ) throws Exception {
        if (context.cancelled()) {
            throw new SpeechToTextException("Transcription canceled.");
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        try (AudioInputStream source = AudioSystem.getAudioInputStream(request.audioFile().toFile())) {
            AudioInputStream pcmStream = pcmStream(source);
            AudioFormat format = pcmStream.getFormat();
            Model model = null;
            Recognizer recognizer = null;
            try {
                model = new Model(modelReference.directory().toString());
                if (context.cancelled()) {
                    throw new SpeechToTextException("Transcription canceled.");
                }
                recognizer = new Recognizer(model, format.getSampleRate());
                return feed(recognizer, pcmStream, format, context);
            } finally {
                closeRecognizer(recognizer);
                closeModel(model);
                if (pcmStream != source) {
                    closeAudioStream(pcmStream);
                }
            }
        }
    }

    private AudioInputStream pcmStream(AudioInputStream source) {
        AudioFormat sourceFormat = source.getFormat();
        AudioFormat target = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.getSampleRate(),
                16,
                1,
                2,
                sourceFormat.getSampleRate(),
                false
        );
        if (target.matches(sourceFormat)) {
            return source;
        }
        return AudioSystem.getAudioInputStream(target, source);
    }

    private String feed(Recognizer recognizer, AudioInputStream stream, AudioFormat format, SpeechToTextProviderContext context) throws Exception {
        int frameSize = Math.max(1, format.getFrameSize());
        byte[] buffer = new byte[8192 - 8192 % frameSize];
        ByteArrayOutputStream partialFrame = new ByteArrayOutputStream(frameSize);
        StringBuilder transcript = new StringBuilder();
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if (context.cancelled()) {
                throw new SpeechToTextException("Transcription canceled.");
            }
            if (read == 0) {
                continue;
            }
            int total = read;
            byte[] chunk = buffer;
            if (partialFrame.size() > 0) {
                partialFrame.write(buffer, 0, read);
                chunk = partialFrame.toByteArray();
                total = chunk.length;
                partialFrame.reset();
            }
            int frameAligned = total - total % frameSize;
            if (frameAligned < total) {
                partialFrame.write(chunk, frameAligned, total - frameAligned);
            }
            if (frameAligned > 0 && recognizer.acceptWaveForm(chunk, frameAligned)) {
                appendTranscript(transcript, recognizer.getResult());
            }
        }
        if (partialFrame.size() > 0) {
            throw new SpeechToTextException("Recorded audio ended with an incomplete PCM frame.");
        }
        appendTranscript(transcript, recognizer.getFinalResult());
        String text = transcript.toString().replaceAll("\\s+", " ").trim();
        if (text.isBlank()) {
            throw new SpeechToTextException("No speech was recognized.");
        }
        return text;
    }

    private void appendTranscript(StringBuilder transcript, String json) throws Exception {
        JsonNode node;
        try {
            node = OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new SpeechToTextException("Vosk transcription response was invalid.", e);
        }
        String text = StringUtils.trimToEmpty(node.path("text").asText(""));
        if (StringUtils.isNotBlank(text)) {
            if (!transcript.isEmpty()) {
                transcript.append(' ');
            }
            transcript.append(text);
        }
    }

    private void closeRecognizer(Recognizer recognizer) {
        if (recognizer == null) {
            return;
        }
        try {
            recognizer.close();
        } catch (Exception ignored) {
        }
    }

    private void closeModel(Model model) {
        if (model == null) {
            return;
        }
        try {
            model.close();
        } catch (Exception ignored) {
        }
    }

    private void closeAudioStream(AudioInputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
        }
    }
}
