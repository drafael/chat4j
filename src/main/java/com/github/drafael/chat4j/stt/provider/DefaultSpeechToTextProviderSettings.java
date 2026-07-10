package com.github.drafael.chat4j.stt.provider;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;

class DefaultSpeechToTextProviderSettings extends AbstractSpeechToTextProviderSettings {

    DefaultSpeechToTextProviderSettings(SettingsRepository settingsRepo, String providerId) {
        super(settingsRepo, providerId);
    }
}
