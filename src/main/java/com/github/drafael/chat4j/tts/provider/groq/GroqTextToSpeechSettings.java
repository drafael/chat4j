package com.github.drafael.chat4j.tts.provider.groq;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.AbstractTextToSpeechProviderSettings;

public class GroqTextToSpeechSettings extends AbstractTextToSpeechProviderSettings {

    public GroqTextToSpeechSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, GroqTextToSpeechProvider.ID);
    }
}
