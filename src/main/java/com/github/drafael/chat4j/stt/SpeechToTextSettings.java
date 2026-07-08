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
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.assemblyai.AssemblyAiSttEndpointResolver;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.deepgram.DeepgramSttEndpointResolver;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.elevenlabs.ElevenLabsSttEndpointResolver;
import com.github.drafael.chat4j.stt.provider.groq.GroqSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.groq.GroqSttEndpointResolver;
import com.github.drafael.chat4j.stt.provider.vosk.VoskInstalledModel;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementService;
import com.github.drafael.chat4j.stt.provider.vosk.VoskModelManagementSnapshot;
import java.net.URI;
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
    private final VoskModelManagementService voskModelManagementService;

    public SpeechToTextSettings(
            SettingsRepository settingsRepo,
            SpeechToTextProviderRegistry providerRegistry,
            CredentialSource credentialSource,
            Path defaultModelDirectory
    ) {
        this(settingsRepo, providerRegistry, credentialSource, defaultModelDirectory, null);
    }

    public SpeechToTextSettings(
            SettingsRepository settingsRepo,
            SpeechToTextProviderRegistry providerRegistry,
            CredentialSource credentialSource,
            Path defaultModelDirectory,
            VoskModelManagementService voskModelManagementService
    ) {
        this.settingsRepo = settingsRepo;
        this.providerRegistry = providerRegistry;
        this.credentialSource = credentialSource;
        this.modelDirectory = new SpeechToTextModelDirectory(settingsRepo, defaultModelDirectory);
        this.voskModelManagementService = voskModelManagementService;
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
        if (SettingsKeys.STT_PROVIDER_VOSK.equals(provider.id())) {
            return resolveVosk(provider, maxDurationSeconds, directory);
        }
        SpeechToTextCatalogItem model = provider.normalizeModelSelection(selectedModel(provider));
        boolean available = provider.available(credentialSource);
        if (provider.supportsLocalModels()) {
            String status = available ? provider.availableMessage() : provider.unavailableMessage();
            return new SpeechToTextSettingsSnapshot(provider, model, available, maxDurationSeconds, directory, null, null, status);
        }
        try {
            SttEndpoint endpoint = resolveEndpoint(provider);
            String status = available ? provider.availableMessage() : provider.unavailableMessage();
            return new SpeechToTextSettingsSnapshot(provider, model, available, maxDurationSeconds, directory, endpoint.baseUri(), endpoint.transcriptionUri(), status);
        } catch (SpeechToTextException e) {
            return new SpeechToTextSettingsSnapshot(provider, model, false, maxDurationSeconds, directory, null, null, e.getMessage());
        }
    }

    public void validateSelectedVoskModelNow() {
        if (voskModelManagementService == null) {
            throw new IllegalStateException("Vosk model management is not available.");
        }
        voskModelManagementService.validateSelectedNow();
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

    public void clearModel(String providerId) {
        settingsRepo.updateBatch(batch -> {
            batch.remove(SettingsKeys.sttModelIdKey(providerId));
            batch.remove(SettingsKeys.sttModelLabelKey(providerId));
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

    private SpeechToTextSettingsSnapshot resolveVosk(SpeechToTextProvider provider, int maxDurationSeconds, Path directory) {
        if (voskModelManagementService == null) {
            return new SpeechToTextSettingsSnapshot(provider, null, false, maxDurationSeconds, directory, null, null, "Vosk model management is not available.");
        }
        VoskModelManagementSnapshot snapshot = voskModelManagementService.snapshot();
        VoskInstalledModel selected = snapshot.selectedModel();
        SpeechToTextCatalogItem model = selected == null ? null : SpeechToTextCatalogItem.of(selected.id(), selected.label(), selected.validationMessage());
        return new SpeechToTextSettingsSnapshot(
                provider,
                model,
                snapshot.readyToTranscribe(),
                maxDurationSeconds,
                directory,
                null,
                null,
                snapshot.statusMessage(),
                selected == null ? null : selected.reference()
        );
    }

    private SpeechToTextCatalogItem selectedModel(SpeechToTextProvider provider) {
        SpeechToTextCatalogItem fallback = provider.defaultModel();
        if (fallback == null) {
            return null;
        }
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

    private SttEndpoint resolveEndpoint(SpeechToTextProvider provider) throws SpeechToTextException {
        if (GroqSpeechToTextProvider.ID.equals(provider.id())) {
            String configured = ProviderRegistry.allProviders().stream()
                    .filter(def -> "Groq".equals(def.name()))
                    .findFirst()
                    .map(def -> new ProviderRuntimeSettingsResolver(settingsRepo).resolve(def).baseUrl())
                    .orElse(GroqSttEndpointResolver.DEFAULT_BASE_URL);
            GroqSttEndpointResolver.Endpoint endpoint = GroqSttEndpointResolver.resolve(configured);
            return new SttEndpoint(endpoint.baseUri(), endpoint.transcriptionUri());
        }
        if (ElevenLabsSpeechToTextProvider.ID.equals(provider.id())) {
            ElevenLabsSttEndpointResolver.Endpoint endpoint = ElevenLabsSttEndpointResolver.resolve(ElevenLabsSttEndpointResolver.DEFAULT_BASE_URL);
            return new SttEndpoint(endpoint.baseUri(), endpoint.transcriptionUri());
        }
        if (DeepgramSpeechToTextProvider.ID.equals(provider.id())) {
            DeepgramSttEndpointResolver.Endpoint endpoint = DeepgramSttEndpointResolver.resolve();
            return new SttEndpoint(endpoint.baseUri(), endpoint.transcriptionUri());
        }
        if (AssemblyAiSpeechToTextProvider.ID.equals(provider.id())) {
            AssemblyAiSttEndpointResolver.Endpoint endpoint = AssemblyAiSttEndpointResolver.resolve(AssemblyAiSttEndpointResolver.DEFAULT_BASE_URL);
            return new SttEndpoint(endpoint.baseUri(), endpoint.transcriptionUri());
        }
        throw new SpeechToTextException("%s Speech to Text endpoints are not configured.".formatted(provider.displayName()));
    }

    private record SttEndpoint(URI baseUri, URI transcriptionUri) {
    }

    private static String normalizeProviderId(String providerId) {
        return StringUtils.trimToEmpty(providerId).toLowerCase(Locale.ROOT);
    }
}
