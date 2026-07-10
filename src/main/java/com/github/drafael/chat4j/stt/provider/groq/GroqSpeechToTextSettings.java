package com.github.drafael.chat4j.stt.provider.groq;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.AbstractSpeechToTextProviderSettings;

public class GroqSpeechToTextSettings extends AbstractSpeechToTextProviderSettings {

    public GroqSpeechToTextSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, GroqSpeechToTextProvider.ID);
    }
}
