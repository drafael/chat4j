package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.persistence.catalog.SpeechCatalogKeySchema;
import com.github.drafael.chat4j.tts.provider.JavaNetTtsHttpTransport;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TtsHttpTransport;
import com.github.drafael.chat4j.tts.provider.deepgram.DeepgramTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.elevenlabs.ElevenLabsTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.groq.GroqTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.system.SystemTextToSpeechProvider;
import java.util.List;
import java.util.Optional;

import static java.util.Collections.emptyList;

public class TextToSpeechProviderRegistry {

    private final List<TextToSpeechProvider> providers;

    public TextToSpeechProviderRegistry(List<TextToSpeechProvider> providers) {
        this.providers = List.copyOf(providers == null ? emptyList() : providers);
        SpeechCatalogKeySchema.validateUniqueProviderSlugs(this.providers.stream().map(TextToSpeechProvider::id).toList());
    }

    public static TextToSpeechProviderRegistry createDefault() {
        TtsHttpTransport transport = new JavaNetTtsHttpTransport();
        return new TextToSpeechProviderRegistry(List.of(
                SystemTextToSpeechProvider.createDefault(),
                new DeepgramTextToSpeechProvider(transport),
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
