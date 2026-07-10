package com.github.drafael.chat4j.tts.provider.system;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.AbstractTextToSpeechProviderSettings;

public class SystemTextToSpeechSettings extends AbstractTextToSpeechProviderSettings {

    public SystemTextToSpeechSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, SystemTextToSpeechProvider.ID);
    }
}
