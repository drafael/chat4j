package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.LocalSpeechToTextModelReference;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import edu.cmu.sphinx.api.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public class Sphinx4SpeechToTextProvider implements SpeechToTextProvider {

    public static final String ID = Sphinx4ModelManagementService.PROVIDER_ID;

    private final Sphinx4ModelValidator validator;
    private final Sphinx4RecognizerAdapter recognizerAdapter;

    public Sphinx4SpeechToTextProvider() {
        this(new Sphinx4ModelValidator(), Sphinx4RecognizerAdapter.defaultAdapter());
    }

    Sphinx4SpeechToTextProvider(Sphinx4ModelValidator validator, Sphinx4RecognizerAdapter recognizerAdapter) {
        this.validator = validator;
        this.recognizerAdapter = recognizerAdapter;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Sphinx4";
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
        return "Using Sphinx4 locally.";
    }

    @Override
    public String unavailableMessage() {
        return "Download or add a Sphinx4 model to enable transcription.";
    }

    @Override
    public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
        return emptyList();
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception {
        if (context.cancelled()) {
            throw new SpeechToTextException("Transcription canceled.");
        }
        LocalSpeechToTextModelReference modelReference = context.localModelReference();
        if (modelReference == null) {
            throw new SpeechToTextException("Select an installed Sphinx4 model to enable transcription.");
        }
        if (!Files.isDirectory(modelReference.directory())) {
            throw new SpeechToTextException("Selected Sphinx4 model is missing.");
        }
        Sphinx4ModelMetadata metadata = validator.readMetadata(modelReference.directory())
                .orElseThrow(() -> new SpeechToTextException("Selected Sphinx4 model is incomplete."));
        Path acoustic = safePath(modelReference.directory(), metadata.acousticModelPath());
        Path dictionary = safePath(modelReference.directory(), metadata.dictionaryPath());
        Path languageModel = safePath(modelReference.directory(), metadata.languageModelPath());
        if (!Files.isDirectory(acoustic) || !Files.isRegularFile(dictionary) || !Files.isRegularFile(languageModel)) {
            throw new SpeechToTextException("Selected Sphinx4 model is incomplete.");
        }
        try {
            Configuration configuration = validator.configuration(acoustic, dictionary, languageModel, metadata.sampleRateHz());
            return new SpeechToTextResult(transcribeWithSphinx4(request, context, configuration, metadata));
        } catch (LinkageError e) {
            throw new SpeechToTextException("Sphinx4 runtime could not load this model.", e);
        }
    }

    private String transcribeWithSphinx4(
            SpeechToTextRequest request,
            SpeechToTextProviderContext context,
            Configuration configuration,
            Sphinx4ModelMetadata metadata
    ) throws Exception {
        Path converted = null;
        try {
            Path audio = compatibleAudioFile(request.audioFile(), context, metadata.sampleRateHz());
            converted = audio.equals(request.audioFile()) ? null : audio;
            if (context.cancelled()) {
                throw new SpeechToTextException("Transcription canceled.");
            }
            try (AudioInputStream input = AudioSystem.getAudioInputStream(audio.toFile());
                    Sphinx4RecognizerAdapter.RecognizerSession recognizer = recognizerAdapter.create(configuration)) {
                recognizer.startRecognition(input);
                StringBuilder transcript = new StringBuilder();
                String hypothesis;
                while ((hypothesis = recognizer.nextHypothesis()) != null) {
                    if (context.cancelled()) {
                        throw new SpeechToTextException("Transcription canceled.");
                    }
                    String text = StringUtils.trimToEmpty(hypothesis);
                    if (StringUtils.isNotBlank(text)) {
                        if (!transcript.isEmpty()) {
                            transcript.append(' ');
                        }
                        transcript.append(text);
                    }
                }
                if (context.cancelled()) {
                    throw new SpeechToTextException("Transcription canceled.");
                }
                String text = transcript.toString().replaceAll("\\s+", " ").trim();
                if (text.isBlank()) {
                    throw new SpeechToTextException("No speech was recognized.");
                }
                return text;
            }
        } catch (SpeechToTextException e) {
            throw e;
        } catch (Exception e) {
            throw new SpeechToTextException("Sphinx4 runtime could not load this model.", e);
        } finally {
            if (converted != null) {
                try {
                    Files.deleteIfExists(converted);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Path compatibleAudioFile(Path sourceFile, SpeechToTextProviderContext context, int sampleRateHz) throws Exception {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(sourceFile.toFile())) {
            AudioFormat sourceFormat = source.getFormat();
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRateHz,
                    16,
                    1,
                    2,
                    sampleRateHz,
                    false
            );
            if (target.matches(sourceFormat)) {
                return sourceFile;
            }
            if (!AudioSystem.isConversionSupported(target, sourceFormat)) {
                throw new SpeechToTextException("Sphinx4 cannot convert recorded audio to the selected model format.");
            }
            Path tempDirectory = context.tempDirectory() == null ? sourceFile.getParent() : context.tempDirectory();
            Files.createDirectories(tempDirectory);
            Path converted = Files.createTempFile(tempDirectory, "chat4j-sphinx4-converted-", ".wav");
            boolean completed = false;
            try {
                setOwnerOnly(converted);
                try (AudioInputStream convertedStream = AudioSystem.getAudioInputStream(target, source)) {
                    AudioSystem.write(convertedStream, javax.sound.sampled.AudioFileFormat.Type.WAVE, converted.toFile());
                }
                completed = true;
                return converted;
            } finally {
                if (!completed) {
                    try {
                        Files.deleteIfExists(converted);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private Path safePath(Path modelDirectory, String relativePath) throws SpeechToTextException {
        Path resolved = validator.safeRelativePath(modelDirectory, relativePath);
        if (resolved == null) {
            throw new SpeechToTextException("Selected Sphinx4 model is incomplete.");
        }
        return resolved;
    }

    private void setOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
        }
    }
}
