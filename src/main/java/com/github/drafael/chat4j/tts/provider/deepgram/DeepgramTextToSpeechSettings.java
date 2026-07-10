package com.github.drafael.chat4j.tts.provider.deepgram;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.AbstractTextToSpeechProviderSettings;

public class DeepgramTextToSpeechSettings extends AbstractTextToSpeechProviderSettings {

    public DeepgramTextToSpeechSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, DeepgramTextToSpeechProvider.ID);
    }
}
