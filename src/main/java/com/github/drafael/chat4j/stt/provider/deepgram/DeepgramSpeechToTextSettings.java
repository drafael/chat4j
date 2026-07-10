package com.github.drafael.chat4j.stt.provider.deepgram;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.AbstractSpeechToTextProviderSettings;

public class DeepgramSpeechToTextSettings extends AbstractSpeechToTextProviderSettings {

    public DeepgramSpeechToTextSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, DeepgramSpeechToTextProvider.ID);
    }
}
