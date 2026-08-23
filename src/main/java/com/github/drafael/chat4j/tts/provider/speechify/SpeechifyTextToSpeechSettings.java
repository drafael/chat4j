package com.github.drafael.chat4j.tts.provider.speechify;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.AbstractTextToSpeechProviderSettings;

public class SpeechifyTextToSpeechSettings extends AbstractTextToSpeechProviderSettings {

    public SpeechifyTextToSpeechSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, SpeechifyTextToSpeechProvider.ID);
    }
}
