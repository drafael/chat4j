package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextSettings;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextSettings;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextSettings;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextSettings;
import com.github.drafael.chat4j.stt.provider.vosk.VoskSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.vosk.VoskSpeechToTextSettings;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextSettings;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public final class SpeechToTextProviderSettingsFactory {

    private SpeechToTextProviderSettingsFactory() {
    }

    public static SpeechToTextProviderSettings forProvider(SettingsRepository settingsRepo, String providerId) {
        return switch (normalizeProviderId(providerId)) {
            case GroqSpeechToTextProvider.ID -> new GroqSpeechToTextSettings(settingsRepo);
            case ElevenLabsSpeechToTextProvider.ID -> new ElevenLabsSpeechToTextSettings(settingsRepo);
            case DeepgramSpeechToTextProvider.ID -> new DeepgramSpeechToTextSettings(settingsRepo);
            case AssemblyAiSpeechToTextProvider.ID -> new AssemblyAiSpeechToTextSettings(settingsRepo);
            case WhisperSpeechToTextProvider.ID -> new WhisperSpeechToTextSettings(settingsRepo);
            case VoskSpeechToTextProvider.ID -> new VoskSpeechToTextSettings(settingsRepo);
            default -> new DefaultSpeechToTextProviderSettings(settingsRepo, providerId);
        };
    }

    private static String normalizeProviderId(String providerId) {
        return StringUtils.trimToEmpty(providerId).toLowerCase(Locale.ROOT);
    }
}
