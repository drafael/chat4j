package com.github.drafael.chat4j.tts.provider.elevenlabs;

import com.github.drafael.chat4j.tts.provider.AbstractHttpTextToSpeechProvider;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.TtsHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

public class ElevenLabsTextToSpeechProvider extends AbstractHttpTextToSpeechProvider {

    public static final String ID = "elevenlabs";
    public static final String ENV_VAR = "ELEVENLABS_API_KEY";
    private static final String BASE_URL = "https://api.elevenlabs.io";
    private static final TextToSpeechCatalogItem DEFAULT_MODEL = TextToSpeechCatalogItem.of("eleven_flash_v2_5", "Eleven Flash v2.5");
    private static final TextToSpeechCatalogItem DEFAULT_VOICE = TextToSpeechCatalogItem.of("EXAVITQu4vr4xnSDxMaL", "Sarah");
    private static final List<TextToSpeechCatalogItem> BUNDLED_MODELS = List.of(DEFAULT_MODEL);
    private static final List<TextToSpeechCatalogItem> BUNDLED_VOICES = List.of(DEFAULT_VOICE);

    public ElevenLabsTextToSpeechProvider(TtsHttpTransport transport) {
        super(transport);
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
        TtsHttpResponse response = get(URI.create("%s/v1/models".formatted(BASE_URL)), authHeaders());
        List<TextToSpeechCatalogItem> models = new ArrayList<>();
        JsonNode root = jsonBody(response);
        JsonNode modelArray = root.isArray() ? root : root.path("models");
        modelArray.forEach(model -> {
            if (model.has("can_do_text_to_speech") && !model.path("can_do_text_to_speech").asBoolean(false)) {
                return;
            }
            String id = firstText(model, "model_id", "id");
            String label = firstText(model, "name", "label", "model_id", "id");
            if (StringUtils.isNotBlank(id)) {
                models.add(TextToSpeechCatalogItem.of(id, label));
            }
        });
        return nonEmptyOrBundled(models, BUNDLED_MODELS);
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        TtsHttpResponse response = get(URI.create("%s/v1/voices".formatted(BASE_URL)), authHeaders());
        List<TextToSpeechCatalogItem> voices = new ArrayList<>();
        jsonBody(response).path("voices").forEach(voice -> {
            String id = firstText(voice, "voice_id", "id");
            String label = firstText(voice, "name", "label", "voice_id", "id");
            String description = firstText(voice, "description", "category");
            if (StringUtils.isNotBlank(id)) {
                voices.add(new TextToSpeechCatalogItem(id, label, description));
            }
        });
        return nonEmptyOrBundled(voices, BUNDLED_VOICES);
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception {
        String voiceId = StringUtils.defaultIfBlank(request.voiceId(), DEFAULT_VOICE.id());
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("text", request.text());
        body.put("model_id", StringUtils.defaultIfBlank(request.modelId(), DEFAULT_MODEL.id()));
        String encodedVoiceId = URLEncoder.encode(voiceId, StandardCharsets.UTF_8);
        URI uri = URI.create("%s/v1/text-to-speech/%s?output_format=mp3_44100_128".formatted(BASE_URL, encodedVoiceId));
        TtsHttpResponse response = postJson(uri, jsonHeaders(), body);
        return audioBody(response, "mp3");
    }

    private Map<String, String> authHeaders() {
        return Map.of("xi-api-key", apiKey());
    }

    private Map<String, String> jsonHeaders() {
        return Map.of(
                "xi-api-key", apiKey(),
                "Content-Type", "application/json",
                "Accept", "audio/mpeg"
        );
    }

    private static String firstText(JsonNode node, String... fields) {
        return Arrays.stream(fields)
                .map(field -> node.path(field).asText(""))
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }
}
