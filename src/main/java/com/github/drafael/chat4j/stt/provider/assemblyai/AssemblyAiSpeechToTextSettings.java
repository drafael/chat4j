package com.github.drafael.chat4j.stt.provider.assemblyai;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.stt.provider.AbstractSpeechToTextProviderSettings;

public class AssemblyAiSpeechToTextSettings extends AbstractSpeechToTextProviderSettings {

    public AssemblyAiSpeechToTextSettings(SettingsRepository settingsRepo) {
        super(settingsRepo, AssemblyAiSpeechToTextProvider.ID);
    }
}
