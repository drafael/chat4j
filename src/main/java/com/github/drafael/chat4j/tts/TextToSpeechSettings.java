package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProviderSettings;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProviderSettingsFactory;
import com.github.drafael.chat4j.tts.provider.system.SystemTextToSpeechProvider;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public class TextToSpeechSettings {

    public static final String PROVIDER_KEY = "chat4j.tts.provider";
    public static final String PROVIDER_OFF = "off";

    private final SettingsRepository settingsRepo;
    private final TextToSpeechProviderRegistry providerRegistry;

    public TextToSpeechSettings(SettingsRepository settingsRepo, TextToSpeechProviderRegistry providerRegistry) {
        this.settingsRepo = settingsRepo;
        this.providerRegistry = providerRegistry;
    }

    public Selection resolve() {
        String providerId = resolveProviderId();
        if (PROVIDER_OFF.equals(providerId)) {
            return Selection.off();
        }
        TextToSpeechProvider provider = providerRegistry.find(providerId).orElse(null);
        if (provider == null) {
            return Selection.off();
        }
        TextToSpeechProviderSettings providerSettings = provider(provider.id());
        TextToSpeechCatalogItem model = provider.normalizeModelSelection(providerSettings.selectedModel(provider.defaultModel()));
        TextToSpeechCatalogItem voice = provider.normalizeVoiceSelection(providerSettings.selectedVoice(provider.defaultVoice()));
        return new Selection(provider, model, voice, provider.available());
    }

    public boolean isProviderUnsetOrBlank() {
        return settingsRepo.get(PROVIDER_KEY)
                .map(StringUtils::isBlank)
                .orElse(true);
    }

    public void saveProvider(String providerId) {
        settingsRepo.put(PROVIDER_KEY, StringUtils.defaultIfBlank(normalizeProviderId(providerId), PROVIDER_OFF));
    }

    public void saveModel(String providerId, TextToSpeechCatalogItem model) {
        provider(providerId).saveModel(model);
    }

    public void saveVoice(String providerId, TextToSpeechCatalogItem voice) {
        provider(providerId).saveVoice(voice);
    }

    public TextToSpeechProviderSettings provider(String providerId) {
        return TextToSpeechProviderSettingsFactory.forProvider(settingsRepo, providerId);
    }

    private String resolveProviderId() {
        return settingsRepo.get(PROVIDER_KEY)
                .map(TextToSpeechSettings::normalizeProviderId)
                .filter(StringUtils::isNotBlank)
                .orElseGet(this::defaultProviderId);
    }

    private String defaultProviderId() {
        return providerRegistry.find(SystemTextToSpeechProvider.ID)
                .filter(TextToSpeechProvider::available)
                .map(TextToSpeechProvider::id)
                .orElse(PROVIDER_OFF);
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
            return provider == null ? PROVIDER_OFF : provider.id();
        }

        @Override
        public String toString() {
            return "Selection[providerId=%s, model=%s, voice=%s, available=%s]".formatted(providerId(), model, voice, available);
        }
    }
}
