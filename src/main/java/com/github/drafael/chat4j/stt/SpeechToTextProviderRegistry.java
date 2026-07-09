package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.stt.provider.JavaNetSttHttpTransport;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.vosk.VoskSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperEngine;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextProvider;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import org.apache.commons.lang3.Strings;

public class SpeechToTextProviderRegistry {

    private final List<SpeechToTextProvider> providers;

    public SpeechToTextProviderRegistry(@NonNull List<SpeechToTextProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public static SpeechToTextProviderRegistry createDefault() {
        return createDefault(new WhisperSpeechToTextProvider());
    }

    public static SpeechToTextProviderRegistry createDefault(WhisperEngine whisperEngine) {
        return createDefault(new WhisperSpeechToTextProvider(whisperEngine));
    }

    public static SpeechToTextProviderRegistry createDefault(WhisperSpeechToTextProvider whisperProvider) {
        JavaNetSttHttpTransport transport = new JavaNetSttHttpTransport();
        return new SpeechToTextProviderRegistry(List.of(
                new GroqSpeechToTextProvider(transport),
                new ElevenLabsSpeechToTextProvider(transport),
                new DeepgramSpeechToTextProvider(transport),
                new AssemblyAiSpeechToTextProvider(transport),
                whisperProvider,
                new VoskSpeechToTextProvider()
        ));
    }

    public List<SpeechToTextProvider> providers() {
        return providers;
    }

    public Optional<SpeechToTextProvider> find(String providerId) {
        return providers.stream()
                .filter(provider -> Strings.CI.equals(provider.id(), providerId))
                .findFirst();
    }
}
