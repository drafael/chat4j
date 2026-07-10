package com.github.drafael.chat4j.tts.provider;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.deepgram.DeepgramTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.deepgram.DeepgramTextToSpeechSettings;
import com.github.drafael.chat4j.tts.provider.elevenlabs.ElevenLabsTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.elevenlabs.ElevenLabsTextToSpeechSettings;
import com.github.drafael.chat4j.tts.provider.groq.GroqTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.groq.GroqTextToSpeechSettings;
import com.github.drafael.chat4j.tts.provider.system.SystemTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.system.SystemTextToSpeechSettings;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public final class TextToSpeechProviderSettingsFactory {

    private TextToSpeechProviderSettingsFactory() {
    }

    public static TextToSpeechProviderSettings forProvider(SettingsRepository settingsRepo, String providerId) {
        return switch (normalizeProviderId(providerId)) {
            case GroqTextToSpeechProvider.ID -> new GroqTextToSpeechSettings(settingsRepo);
            case ElevenLabsTextToSpeechProvider.ID -> new ElevenLabsTextToSpeechSettings(settingsRepo);
            case DeepgramTextToSpeechProvider.ID -> new DeepgramTextToSpeechSettings(settingsRepo);
            case SystemTextToSpeechProvider.ID -> new SystemTextToSpeechSettings(settingsRepo);
            default -> new DefaultTextToSpeechProviderSettings(settingsRepo, providerId);
        };
    }

    private static String normalizeProviderId(String providerId) {
        return StringUtils.trimToEmpty(providerId).toLowerCase(Locale.ROOT);
    }
}
