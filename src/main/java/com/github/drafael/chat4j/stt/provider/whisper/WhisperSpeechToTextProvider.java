package com.github.drafael.chat4j.stt.provider.whisper;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.LocalSpeechToTextModelReference;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public class WhisperSpeechToTextProvider implements SpeechToTextProvider {

    public static final String ID = "whisper";

    private final WhisperEngine engine;

    public WhisperSpeechToTextProvider() {
        this(new WhisperJniEngine());
    }

    public WhisperSpeechToTextProvider(WhisperEngine engine) {
        this.engine = engine;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Whisper.cpp";
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
    public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
        return emptyList();
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception {
        if (context.cancelled()) {
            throw new SpeechToTextException("Transcription canceled.");
        }
        LocalSpeechToTextModelReference reference = context.localModelReference();
        if (reference == null) {
            throw new SpeechToTextException("Select an installed Whisper.cpp model to enable transcription.");
        }
        if (!Objects.equals(request.modelId(), reference.modelId())) {
            throw new SpeechToTextException("Selected Whisper.cpp model changed before transcription.");
        }
        Path directory = reference.directory().toAbsolutePath().normalize();
        Path modelFile = directory.resolve("model.bin").normalize();
        if (!modelFile.startsWith(directory) || !Files.isRegularFile(modelFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(modelFile)) {
            throw new SpeechToTextException("Selected Whisper.cpp model is missing.");
        }
        String text = engine.transcribe(
                modelFile,
                request.audioFile(),
                WhisperTranscriptionOptions.fromModelId(request.modelId()),
                context.cancellationToken()
        );
        if (context.cancelled()) {
            throw new SpeechToTextException("Transcription canceled.");
        }
        if (StringUtils.isBlank(text)) {
            throw new SpeechToTextException("No speech was recognized.");
        }
        return new SpeechToTextResult(StringUtils.normalizeSpace(text));
    }
}
