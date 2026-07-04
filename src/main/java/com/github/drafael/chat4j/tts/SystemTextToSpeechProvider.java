package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

class SystemTextToSpeechProvider implements TextToSpeechProvider {

    static final TextToSpeechCatalogItem DEFAULT_MODEL = TextToSpeechCatalogItem.of("system", "System TTS");
    static final TextToSpeechCatalogItem DEFAULT_VOICE = TextToSpeechCatalogItem.of("system-default", "System Default");

    private final SystemTtsBackend backend;

    SystemTextToSpeechProvider(SystemTtsBackend backend) {
        this.backend = backend == null ? new UnavailableSystemTtsBackend() : backend;
    }

    static SystemTextToSpeechProvider createDefault() {
        SystemTtsProcessRunner runner = new SystemTtsProcessRunner();
        SystemTtsExecutableLocator locator = new SystemTtsExecutableLocator();
        SystemTtsBackendFactory factory = new SystemTtsBackendFactory();
        SystemTtsBackend backend = factory.createDefault(
                System.getProperty("os.name"),
                CredentialResolver.mergedEnvironment(),
                runner,
                locator
        );
        return new SystemTextToSpeechProvider(backend);
    }

    @Override
    public String id() {
        return SettingsKeys.TTS_PROVIDER_SYSTEM;
    }

    @Override
    public String displayName() {
        return "System";
    }

    @Override
    public String requiredEnvVar() {
        return null;
    }

    @Override
    public TextToSpeechCatalogItem defaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    public TextToSpeechCatalogItem defaultVoice() {
        return DEFAULT_VOICE;
    }

    @Override
    public List<TextToSpeechCatalogItem> bundledModels() {
        return List.of(DEFAULT_MODEL);
    }

    @Override
    public List<TextToSpeechCatalogItem> bundledVoices() {
        return List.of(DEFAULT_VOICE);
    }

    @Override
    public boolean available() {
        return backend.available();
    }

    @Override
    public String defaultResponseFormat() {
        return backend.defaultResponseFormat();
    }

    @Override
    public String unavailableLabel() {
        return "%s (unavailable)".formatted(displayName());
    }

    @Override
    public String unavailableMessage() {
        return backend.unavailableMessage();
    }

    @Override
    public String availableMessage() {
        return backend.availableMessage();
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchModels() {
        return bundledModels();
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        try {
            return defaultFirst(backend.fetchVoices());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            return bundledVoices();
        }
    }

    @Override
    public List<TextToSpeechCatalogItem> voicesForModel(TextToSpeechCatalogItem model, List<TextToSpeechCatalogItem> voices) {
        return defaultFirst(voices);
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception {
        if (isDefaultVoice(request.voiceId())) {
            return backend.synthesize(request);
        }
        try {
            return backend.synthesize(request);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw e;
            }
            if (isVoiceSelectionFailure(e)) {
                return backend.synthesize(defaultVoiceRequest(request));
            }
            throw e;
        }
    }

    static boolean isDefaultVoice(String voiceId) {
        return StringUtils.isBlank(voiceId) || Strings.CS.equals(voiceId, DEFAULT_VOICE.id());
    }

    private static boolean isVoiceSelectionFailure(Exception e) {
        String message = StringUtils.defaultString(e.getMessage());
        return Strings.CI.contains(message, "voice");
    }

    private static TextToSpeechRequest defaultVoiceRequest(TextToSpeechRequest request) {
        return new TextToSpeechRequest(
                request.providerId(),
                request.modelId(),
                DEFAULT_VOICE.id(),
                request.text(),
                request.responseFormat()
        );
    }

    private static List<TextToSpeechCatalogItem> defaultFirst(List<TextToSpeechCatalogItem> voices) {
        Map<String, TextToSpeechCatalogItem> byId = new LinkedHashMap<>();
        byId.put(DEFAULT_VOICE.id(), DEFAULT_VOICE);
        if (voices != null) {
            voices.stream()
                    .filter(voice -> voice != null && StringUtils.isNotBlank(voice.id()))
                    .forEach(voice -> byId.putIfAbsent(voice.id(), voice));
        }
        return List.copyOf(byId.values());
    }
}
