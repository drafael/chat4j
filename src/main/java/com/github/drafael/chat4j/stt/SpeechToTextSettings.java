package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.settings.ProviderRuntimeSettingsResolver;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.model.SpeechToTextModelDirectory;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSttEndpointResolver;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public class SpeechToTextSettings {

    public static final int DEFAULT_MAX_DURATION_SECONDS = 600;
    public static final int MIN_MAX_DURATION_SECONDS = 1;
    public static final int MAX_MAX_DURATION_SECONDS = 600;

    private final SettingsRepository settingsRepo;
    private final SpeechToTextProviderRegistry providerRegistry;
    private final CredentialSource credentialSource;
    private final SpeechToTextModelDirectory modelDirectory;

    public SpeechToTextSettings(
            SettingsRepository settingsRepo,
            SpeechToTextProviderRegistry providerRegistry,
            CredentialSource credentialSource,
            Path defaultModelDirectory
    ) {
        this.settingsRepo = settingsRepo;
        this.providerRegistry = providerRegistry;
        this.credentialSource = credentialSource;
        this.modelDirectory = new SpeechToTextModelDirectory(settingsRepo, defaultModelDirectory);
    }

    public SpeechToTextSettingsSnapshot resolve() {
        int maxDurationSeconds = resolveMaxDurationSeconds();
        Path directory = modelDirectory.resolve();
        String providerId = resolveProviderId();
        if (SettingsKeys.STT_PROVIDER_OFF.equals(providerId)) {
            return SpeechToTextSettingsSnapshot.off(maxDurationSeconds, directory);
        }
        SpeechToTextProvider provider = providerRegistry.find(providerId).orElse(null);
        if (provider == null) {
            return SpeechToTextSettingsSnapshot.off(maxDurationSeconds, directory);
        }
        SpeechToTextCatalogItem model = provider.normalizeModelSelection(selectedModel(provider));
        boolean available = provider.available(credentialSource);
        try {
            GroqSttEndpointResolver.Endpoint endpoint = resolveEndpoint(provider);
            String status = available ? provider.availableMessage() : provider.unavailableMessage();
            return new SpeechToTextSettingsSnapshot(provider, model, available, maxDurationSeconds, directory, endpoint.baseUri(), endpoint.transcriptionUri(), status);
        } catch (SpeechToTextException e) {
            return new SpeechToTextSettingsSnapshot(provider, model, false, maxDurationSeconds, directory, null, null, e.getMessage());
        }
    }

    public void saveProvider(String providerId) {
        settingsRepo.put(SettingsKeys.STT_PROVIDER, StringUtils.defaultIfBlank(normalizeProviderId(providerId), SettingsKeys.STT_PROVIDER_OFF));
    }

    public void saveModel(String providerId, SpeechToTextCatalogItem model) {
        settingsRepo.updateBatch(batch -> {
            batch.put(SettingsKeys.sttModelIdKey(providerId), model.id());
            batch.put(SettingsKeys.sttModelLabelKey(providerId), model.label());
        });
    }

    public void saveMaxDurationSeconds(int seconds) {
        validateMaxDurationSeconds(seconds);
        settingsRepo.put(SettingsKeys.STT_RECORDING_MAX_DURATION_SECONDS, Integer.toString(seconds));
    }

    public Path saveModelDirectory(String rawPath) throws Exception {
        return modelDirectory.saveAndCreate(rawPath);
    }

    public SpeechToTextModelDirectory modelDirectory() {
        return modelDirectory;
    }

    public int resolveMaxDurationSeconds() {
        String value = settingsRepo.get(SettingsKeys.STT_RECORDING_MAX_DURATION_SECONDS, "");
        if (StringUtils.isBlank(value)) {
            return DEFAULT_MAX_DURATION_SECONDS;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= MIN_MAX_DURATION_SECONDS && parsed <= MAX_MAX_DURATION_SECONDS ? parsed : DEFAULT_MAX_DURATION_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_DURATION_SECONDS;
        }
    }

    public static void validateMaxDurationSeconds(int seconds) {
        if (seconds < MIN_MAX_DURATION_SECONDS || seconds > MAX_MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("Max recording duration must be between 1 and 600 seconds.");
        }
    }

    private SpeechToTextCatalogItem selectedModel(SpeechToTextProvider provider) {
        SpeechToTextCatalogItem fallback = provider.defaultModel();
        String id = StringUtils.defaultIfBlank(settingsRepo.get(SettingsKeys.sttModelIdKey(provider.id()), fallback.id()), fallback.id());
        String label = settingsRepo.get(SettingsKeys.sttModelLabelKey(provider.id()), fallback.label());
        return new SpeechToTextCatalogItem(id, label, fallback.description());
    }

    private String resolveProviderId() {
        return settingsRepo.get(SettingsKeys.STT_PROVIDER)
                .map(SpeechToTextSettings::normalizeProviderId)
                .filter(StringUtils::isNotBlank)
                .orElse(SettingsKeys.STT_PROVIDER_OFF);
    }

    private GroqSttEndpointResolver.Endpoint resolveEndpoint(SpeechToTextProvider provider) throws SpeechToTextException {
        if (GroqSpeechToTextProvider.ID.equals(provider.id())) {
            String configured = ProviderRegistry.allProviders().stream()
                    .filter(def -> "Groq".equals(def.name()))
                    .findFirst()
                    .map(def -> new ProviderRuntimeSettingsResolver(settingsRepo).resolve(def).baseUrl())
                    .orElse(GroqSttEndpointResolver.DEFAULT_BASE_URL);
            return GroqSttEndpointResolver.resolve(configured);
        }
        return GroqSttEndpointResolver.resolve(GroqSttEndpointResolver.DEFAULT_BASE_URL);
    }

    private static String normalizeProviderId(String providerId) {
        return StringUtils.trimToEmpty(providerId).toLowerCase(Locale.ROOT);
    }
}
