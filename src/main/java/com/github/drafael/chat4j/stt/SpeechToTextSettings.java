package com.github.drafael.chat4j.stt;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.settings.ProviderRuntimeSettingsResolver;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.model.SpeechToTextModelDirectory;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderSettings;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderSettingsFactory;
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
import com.github.drafael.chat4j.stt.provider.vosk.VoskSpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperInstalledModel;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementService;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperModelManagementSnapshot;
import com.github.drafael.chat4j.stt.provider.whisper.WhisperSpeechToTextProvider;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

public class SpeechToTextSettings {

    public static final String PROVIDER_KEY = "chat4j.stt.provider";
    public static final String PROVIDER_OFF = "off";
    public static final String RECORDING_MAX_DURATION_SECONDS_KEY = "chat4j.stt.recording.maxDurationSeconds";
    public static final int DEFAULT_MAX_DURATION_SECONDS = 600;
    public static final int MIN_MAX_DURATION_SECONDS = 1;
    public static final int MAX_MAX_DURATION_SECONDS = 600;

    private final SettingsRepository settingsRepo;
    private final SpeechToTextProviderRegistry providerRegistry;
    private final CredentialSource credentialSource;
    private final SpeechToTextModelDirectory modelDirectory;
    private final VoskModelManagementService voskModelManagementService;
    private final WhisperModelManagementService whisperModelManagementService;

    public SpeechToTextSettings(
            SettingsRepository settingsRepo,
            SpeechToTextProviderRegistry providerRegistry,
            CredentialSource credentialSource,
            Path defaultModelDirectory
    ) {
        this(settingsRepo, providerRegistry, credentialSource, defaultModelDirectory, null, null);
    }

    public SpeechToTextSettings(
            SettingsRepository settingsRepo,
            SpeechToTextProviderRegistry providerRegistry,
            CredentialSource credentialSource,
            Path defaultModelDirectory,
            VoskModelManagementService voskModelManagementService
    ) {
        this(settingsRepo, providerRegistry, credentialSource, defaultModelDirectory, voskModelManagementService, null);
    }

    public SpeechToTextSettings(
            SettingsRepository settingsRepo,
            SpeechToTextProviderRegistry providerRegistry,
            CredentialSource credentialSource,
            Path defaultModelDirectory,
            VoskModelManagementService voskModelManagementService,
            WhisperModelManagementService whisperModelManagementService
    ) {
        this.settingsRepo = settingsRepo;
        this.providerRegistry = providerRegistry;
        this.credentialSource = credentialSource;
        this.modelDirectory = new SpeechToTextModelDirectory(settingsRepo, defaultModelDirectory);
        this.voskModelManagementService = voskModelManagementService;
        this.whisperModelManagementService = whisperModelManagementService;
    }

    public SpeechToTextSettingsSnapshot resolve() {
        int maxDurationSeconds = resolveMaxDurationSeconds();
        Path directory = modelDirectory.resolve();
        String providerId = resolveProviderId();
        if (PROVIDER_OFF.equals(providerId)) {
            return SpeechToTextSettingsSnapshot.off(maxDurationSeconds, directory);
        }
        SpeechToTextProvider provider = providerRegistry.find(providerId).orElse(null);
        if (provider == null) {
            return SpeechToTextSettingsSnapshot.off(maxDurationSeconds, directory);
        }
        if (VoskSpeechToTextProvider.ID.equals(provider.id())) {
            return resolveVosk(provider, maxDurationSeconds, directory);
        }
        if (WhisperSpeechToTextProvider.ID.equals(provider.id())) {
            return resolveWhisper(provider, maxDurationSeconds, directory);
        }
        SpeechToTextCatalogItem model = provider.normalizeModelSelection(selectedModel(provider));
        boolean available = provider.available(credentialSource);
        if (provider.supportsLocalModels()) {
            String status = available ? provider.availableMessage() : provider.unavailableMessage();
            return snapshotBuilder(provider, model, available, maxDurationSeconds, directory)
                    .statusMessage(status)
                    .build();
        }
        try {
            SttEndpoint endpoint = resolveEndpoint(provider);
            String status = available ? provider.availableMessage() : provider.unavailableMessage();
            return snapshotBuilder(provider, model, available, maxDurationSeconds, directory)
                    .baseUri(endpoint.baseUri())
                    .transcriptionUri(endpoint.transcriptionUri())
                    .statusMessage(status)
                    .build();
        } catch (SpeechToTextException e) {
            return snapshotBuilder(provider, model, false, maxDurationSeconds, directory)
                    .statusMessage(e.getMessage())
                    .build();
        }
    }

    public void validateSelectedVoskModelNow() {
        if (voskModelManagementService == null) {
            throw new IllegalStateException("Vosk model management is not available.");
        }
        voskModelManagementService.validateSelectedNow();
    }

    public void validateSelectedWhisperModelNow() {
        if (whisperModelManagementService == null) {
            throw new IllegalStateException("Whisper.cpp model management is not available.");
        }
        whisperModelManagementService.validateSelectedNow();
    }

    public void saveProvider(String providerId) {
        settingsRepo.put(PROVIDER_KEY, StringUtils.defaultIfBlank(normalizeProviderId(providerId), PROVIDER_OFF));
    }

    public void saveModel(String providerId, SpeechToTextCatalogItem model) {
        provider(providerId).saveModel(model);
    }

    public void clearModel(String providerId) {
        provider(providerId).clearModel();
    }

    public void saveMaxDurationSeconds(int seconds) {
        validateMaxDurationSeconds(seconds);
        settingsRepo.put(RECORDING_MAX_DURATION_SECONDS_KEY, Integer.toString(seconds));
    }

    public Path saveModelDirectory(String rawPath) throws Exception {
        return modelDirectory.saveAndCreate(rawPath);
    }

    public SpeechToTextModelDirectory modelDirectory() {
        return modelDirectory;
    }

    public SpeechToTextProviderSettings provider(String providerId) {
        return SpeechToTextProviderSettingsFactory.forProvider(settingsRepo, providerId);
    }

    public int resolveMaxDurationSeconds() {
        String value = settingsRepo.get(RECORDING_MAX_DURATION_SECONDS_KEY, "");
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
            return snapshotBuilder(provider, null, false, maxDurationSeconds, directory)
                    .statusMessage("Vosk model management is not available.")
                    .build();
        }
        VoskModelManagementSnapshot snapshot = voskModelManagementService.snapshot();
        VoskInstalledModel selected = snapshot.selectedModel();
        SpeechToTextCatalogItem model = selected == null ? null : SpeechToTextCatalogItem.of(selected.id(), selected.label(), selected.validationMessage());
        return snapshotBuilder(provider, model, snapshot.readyToTranscribe(), maxDurationSeconds, directory)
                .statusMessage(snapshot.statusMessage())
                .localModelReference(selected == null ? null : selected.reference())
                .build();
    }

    private SpeechToTextSettingsSnapshot resolveWhisper(SpeechToTextProvider provider, int maxDurationSeconds, Path directory) {
        if (whisperModelManagementService == null) {
            return snapshotBuilder(provider, null, false, maxDurationSeconds, directory)
                    .statusMessage("Whisper.cpp model management is not available.")
                    .build();
        }
        WhisperModelManagementSnapshot snapshot = whisperModelManagementService.snapshot();
        WhisperInstalledModel selected = snapshot.selectedModel();
        SpeechToTextCatalogItem model = selected == null ? null : SpeechToTextCatalogItem.of(selected.id(), selected.label(), selected.validationMessage());
        return snapshotBuilder(provider, model, snapshot.readyToTranscribe(), maxDurationSeconds, directory)
                .statusMessage(snapshot.statusMessage())
                .localModelReference(selected == null ? null : selected.reference())
                .build();
    }

    private SpeechToTextSettingsSnapshot.SpeechToTextSettingsSnapshotBuilder snapshotBuilder(
            SpeechToTextProvider provider,
            SpeechToTextCatalogItem model,
            boolean available,
            int maxDurationSeconds,
            Path directory
    ) {
        return SpeechToTextSettingsSnapshot.builder()
                .provider(provider)
                .model(model)
                .available(available)
                .maxDurationSeconds(maxDurationSeconds)
                .modelDirectory(directory);
    }

    private SpeechToTextCatalogItem selectedModel(SpeechToTextProvider provider) {
        return provider(provider.id()).selectedModel(provider.defaultModel());
    }

    private String resolveProviderId() {
        return settingsRepo.get(PROVIDER_KEY)
                .map(SpeechToTextSettings::normalizeProviderId)
                .filter(StringUtils::isNotBlank)
                .orElse(PROVIDER_OFF);
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
