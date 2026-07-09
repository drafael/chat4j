package com.github.drafael.chat4j.stt.provider.whisper;

import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import java.nio.file.Path;

public interface WhisperEngine {
    String transcribe(Path modelFile, Path wavFile, WhisperTranscriptionOptions options, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception;
}
