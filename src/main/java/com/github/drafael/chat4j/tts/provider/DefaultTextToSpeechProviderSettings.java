package com.github.drafael.chat4j.tts.provider;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;

class DefaultTextToSpeechProviderSettings extends AbstractTextToSpeechProviderSettings {

    DefaultTextToSpeechProviderSettings(SettingsRepository settingsRepo, String providerId) {
        super(settingsRepo, providerId);
    }
}
