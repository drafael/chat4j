package com.github.drafael.chat4j.tts;

import java.util.List;
import java.util.Optional;

import static java.util.Collections.emptyList;

public class TextToSpeechProviderRegistry {

    private final List<TextToSpeechProvider> providers;

    public TextToSpeechProviderRegistry(List<TextToSpeechProvider> providers) {
        this.providers = List.copyOf(providers == null ? emptyList() : providers);
    }

    public static TextToSpeechProviderRegistry createDefault() {
        TtsHttpTransport transport = new JavaNetTtsHttpTransport();
        return new TextToSpeechProviderRegistry(List.of(
                SystemTextToSpeechProvider.createDefault(),
                new GroqTextToSpeechProvider(transport),
                new ElevenLabsTextToSpeechProvider(transport)
        ));
    }

    public List<TextToSpeechProvider> providers() {
        return providers;
    }

    public Optional<TextToSpeechProvider> find(String id) {
        return providers.stream()
                .filter(provider -> provider.id().equals(id))
                .findFirst();
    }
}
