package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public class TextToSpeechSettings {

    private final SettingsRepository settingsRepo;
    private final TextToSpeechProviderRegistry providerRegistry;

    public TextToSpeechSettings(SettingsRepository settingsRepo, TextToSpeechProviderRegistry providerRegistry) {
        this.settingsRepo = settingsRepo;
        this.providerRegistry = providerRegistry;
    }

    public Selection resolve() {
        String providerId = resolveProviderId();
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

    public boolean isProviderUnsetOrBlank() {
        return settingsRepo.get(SettingsKeys.TTS_PROVIDER)
                .map(StringUtils::isBlank)
                .orElse(true);
    }

    public void saveProvider(String providerId) {
        settingsRepo.put(SettingsKeys.TTS_PROVIDER, StringUtils.defaultIfBlank(normalizeProviderId(providerId), SettingsKeys.TTS_PROVIDER_OFF));
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

    private String resolveProviderId() {
        return settingsRepo.get(SettingsKeys.TTS_PROVIDER)
                .map(TextToSpeechSettings::normalizeProviderId)
                .filter(StringUtils::isNotBlank)
                .orElseGet(this::defaultProviderId);
    }

    private String defaultProviderId() {
        return providerRegistry.find(SettingsKeys.TTS_PROVIDER_SYSTEM)
                .filter(TextToSpeechProvider::available)
                .map(TextToSpeechProvider::id)
                .orElse(SettingsKeys.TTS_PROVIDER_OFF);
    }

    private static String normalizeProviderId(String providerId) {
        return StringUtils.trimToEmpty(providerId).toLowerCase(Locale.ROOT);
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
