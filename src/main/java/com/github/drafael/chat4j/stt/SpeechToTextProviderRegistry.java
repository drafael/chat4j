package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.stt.provider.JavaNetSttHttpTransport;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
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
        return new SpeechToTextProviderRegistry(List.of(new GroqSpeechToTextProvider(new JavaNetSttHttpTransport())));
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
