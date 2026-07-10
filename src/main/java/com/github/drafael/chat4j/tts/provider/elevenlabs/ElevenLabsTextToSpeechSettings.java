package com.github.drafael.chat4j.tts.provider.elevenlabs;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.AbstractTextToSpeechProviderSettings;

public class ElevenLabsTextToSpeechSettings extends AbstractTextToSpeechProviderSettings {

    public ElevenLabsTextToSpeechSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, ElevenLabsTextToSpeechProvider.ID);
    }
}
