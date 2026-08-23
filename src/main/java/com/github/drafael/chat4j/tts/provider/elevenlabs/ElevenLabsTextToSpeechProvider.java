package com.github.drafael.chat4j.tts.provider.elevenlabs;

import com.github.drafael.chat4j.tts.provider.AbstractHttpTextToSpeechProvider;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpClient;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public class ElevenLabsTextToSpeechProvider extends AbstractHttpTextToSpeechProvider {

    public static final String ID = "elevenlabs";
    public static final String ENV_VAR = "ELEVENLABS_API_KEY";
    private static final String BASE_URL = "https://api.elevenlabs.io";
    private static final TextToSpeechCatalogItem DEFAULT_MODEL = TextToSpeechCatalogItem.of("eleven_flash_v2_5", "Eleven Flash v2.5");
    private static final TextToSpeechCatalogItem DEFAULT_VOICE = TextToSpeechCatalogItem.of("EXAVITQu4vr4xnSDxMaL", "Sarah");
    private static final List<TextToSpeechCatalogItem> BUNDLED_MODELS = List.of(DEFAULT_MODEL);
    private static final List<TextToSpeechCatalogItem> BUNDLED_VOICES = List.of(DEFAULT_VOICE);

    public ElevenLabsTextToSpeechProvider(@NonNull TtsHttpClient httpClient, @NonNull CredentialResolver credentialResolver) {
        super(httpClient, credentialResolver);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "ElevenLabs";
    }

    @Override
    public String requiredEnvVar() {
        return ENV_VAR;
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
        return BUNDLED_MODELS;
    }

    @Override
    public List<TextToSpeechCatalogItem> bundledVoices() {
        return BUNDLED_VOICES;
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchModels() throws Exception {
        ElevenLabsApi.ModelsResponse response = getJson(
                URI.create("%s/v1/models".formatted(BASE_URL)),
                authHeaders(),
                ElevenLabsApi.ModelsResponse.class,
                "ElevenLabs model catalog response was invalid."
        );
        List<ElevenLabsApi.Model> modelArray = response.models();
        if (modelArray == null) {
            throw new IllegalStateException("ElevenLabs model catalog response was invalid.");
        }
        if (!modelArray.isEmpty() && modelArray.stream().noneMatch(model -> model != null && StringUtils.isNotBlank(modelId(model)))) {
            throw new IllegalStateException("ElevenLabs model catalog response did not contain valid model IDs.");
        }
        List<TextToSpeechCatalogItem> models = modelArray.stream()
                .filter(Objects::nonNull)
                .filter(model -> !model.textToSpeechCapabilityPresent() || Boolean.TRUE.equals(model.canDoTextToSpeech()))
                .map(ElevenLabsTextToSpeechProvider::modelItem)
                .filter(Objects::nonNull)
                .toList();
        return nonEmptyOrBundled(models, BUNDLED_MODELS);
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        ElevenLabsApi.VoicesResponse response = getJson(
                URI.create("%s/v1/voices".formatted(BASE_URL)),
                authHeaders(),
                ElevenLabsApi.VoicesResponse.class,
                "ElevenLabs voice catalog response was invalid."
        );
        List<ElevenLabsApi.Voice> voiceArray = response.voices();
        if (voiceArray == null) {
            throw new IllegalStateException("ElevenLabs voice catalog response was invalid.");
        }
        List<TextToSpeechCatalogItem> voices = voiceArray.stream()
                .filter(Objects::nonNull)
                .map(ElevenLabsTextToSpeechProvider::voiceItem)
                .filter(Objects::nonNull)
                .toList();
        if (!voiceArray.isEmpty() && voices.isEmpty()) {
            throw new IllegalStateException("ElevenLabs voice catalog response did not contain valid voices.");
        }
        return nonEmptyOrBundled(voices, BUNDLED_VOICES);
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception {
        return synthesize(request, apiKey());
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request, String apiKey) throws Exception {
        String voiceId = StringUtils.defaultIfBlank(request.voiceId(), DEFAULT_VOICE.id());
        var body = new ElevenLabsApi.SynthesisRequest(
                request.text(),
                StringUtils.defaultIfBlank(request.modelId(), DEFAULT_MODEL.id())
        );
        String encodedVoiceId = URLEncoder.encode(voiceId, StandardCharsets.UTF_8);
        URI uri = URI.create("%s/v1/text-to-speech/%s?output_format=mp3_44100_128".formatted(BASE_URL, encodedVoiceId));
        HttpExchangeResponse response = postJson(uri, jsonHeaders(apiKey), body);
        return audioBody(response, "mp3");
    }

    private Map<String, String> authHeaders() {
        return Map.of("xi-api-key", apiKey());
    }

    private Map<String, String> jsonHeaders(String apiKey) {
        return Map.of(
                "xi-api-key", apiKey,
                "Content-Type", "application/json",
                "Accept", "audio/mpeg"
        );
    }

    private static TextToSpeechCatalogItem modelItem(ElevenLabsApi.Model model) {
        String id = modelId(model);
        return StringUtils.isBlank(id) ? null : TextToSpeechCatalogItem.of(id, modelLabel(model));
    }

    private static String modelId(ElevenLabsApi.Model model) {
        return firstText(model.modelId(), model.id());
    }

    private static String modelLabel(ElevenLabsApi.Model model) {
        return firstText(model.name(), model.label(), model.modelId(), model.id());
    }

    private static TextToSpeechCatalogItem voiceItem(ElevenLabsApi.Voice voice) {
        String id = firstText(voice.voiceId(), voice.id());
        if (StringUtils.isBlank(id)) {
            return null;
        }
        String label = firstText(voice.name(), voice.label(), voice.voiceId(), voice.id());
        String description = firstText(voice.description(), voice.category());
        return new TextToSpeechCatalogItem(id, label, description);
    }

    private static String firstText(String... values) {
        return Arrays.stream(values)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }
}
