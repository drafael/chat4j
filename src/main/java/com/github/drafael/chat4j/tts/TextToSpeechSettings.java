package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import org.apache.commons.lang3.StringUtils;

public class TextToSpeechSettings {

    private final SettingsRepository settingsRepo;
    private final TextToSpeechProviderRegistry providerRegistry;

    public TextToSpeechSettings(SettingsRepository settingsRepo, TextToSpeechProviderRegistry providerRegistry) {
        this.settingsRepo = settingsRepo;
        this.providerRegistry = providerRegistry;
    }

    public Selection resolve() {
        String providerId = normalizeProviderId(settingsRepo.get(SettingsKeys.TTS_PROVIDER, SettingsKeys.TTS_PROVIDER_OFF));
        if (SettingsKeys.TTS_PROVIDER_OFF.equals(providerId)) {
            return Selection.off();
        }
        TextToSpeechProvider provider = providerRegistry.find(providerId).orElse(null);
        if (provider == null) {
            return Selection.off();
        }
        TextToSpeechCatalogItem model = selectedItem(
                SettingsKeys.ttsModelIdKey(provider.id()),
                SettingsKeys.ttsModelLabelKey(provider.id()),
                provider.defaultModel()
        );
        TextToSpeechCatalogItem voice = selectedItem(
                SettingsKeys.ttsVoiceIdKey(provider.id()),
                SettingsKeys.ttsVoiceLabelKey(provider.id()),
                provider.defaultVoice()
        );
        model = provider.normalizeModelSelection(model);
        voice = provider.normalizeVoiceSelection(voice);
        return new Selection(provider, model, voice, provider.available());
    }

    public void saveProvider(String providerId) {
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, normalizeProviderId(providerId));
    }

    public void saveModel(String providerId, TextToSpeechCatalogItem model) {
        saveItem(SettingsKeys.ttsModelIdKey(providerId), SettingsKeys.ttsModelLabelKey(providerId), model);
    }

    public void saveVoice(String providerId, TextToSpeechCatalogItem voice) {
        saveItem(SettingsKeys.ttsVoiceIdKey(providerId), SettingsKeys.ttsVoiceLabelKey(providerId), voice);
    }

    private TextToSpeechCatalogItem selectedItem(String idKey, String labelKey, TextToSpeechCatalogItem fallback) {
        String id = settingsRepo.get(idKey, fallback.id());
        String label = settingsRepo.get(labelKey, fallback.label());
        return new TextToSpeechCatalogItem(id, label, fallback.description());
    }

    private void saveItem(String idKey, String labelKey, TextToSpeechCatalogItem item) {
        settingsRepo.put(idKey, item.id());
        settingsRepo.put(labelKey, item.label());
    }

    private static String normalizeProviderId(String providerId) {
        String normalized = StringUtils.trimToEmpty(providerId).toLowerCase();
        return StringUtils.defaultIfBlank(normalized, SettingsKeys.TTS_PROVIDER_OFF);
    }

    public record Selection(TextToSpeechProvider provider, TextToSpeechCatalogItem model, TextToSpeechCatalogItem voice, boolean available) {

        public static Selection off() {
            return new Selection(null, null, null, false);
        }

        public boolean enabled() {
            return provider != null;
        }

        public String providerId() {
            return provider == null ? SettingsKeys.TTS_PROVIDER_OFF : provider.id();
        }

        @Override
        public String toString() {
            return "Selection[providerId=%s, model=%s, voice=%s, available=%s]".formatted(providerId(), model, voice, available);
        }
    }
}
