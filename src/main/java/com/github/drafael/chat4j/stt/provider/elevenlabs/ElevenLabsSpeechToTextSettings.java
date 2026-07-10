package com.github.drafael.chat4j.stt.provider.elevenlabs;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.AbstractSpeechToTextProviderSettings;

public class ElevenLabsSpeechToTextSettings extends AbstractSpeechToTextProviderSettings {

    public ElevenLabsSpeechToTextSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, ElevenLabsSpeechToTextProvider.ID);
    }
}
