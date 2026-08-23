package com.github.drafael.chat4j.tts.provider.speechify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.AbstractHttpTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.TtsHttpTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Speechify Build text-to-speech integration. */
public class SpeechifyTextToSpeechProvider extends AbstractHttpTextToSpeechProvider {

    public static final String ID = "speechify";
    public static final String ENV_VAR = "SPEECHIFY_API_KEY";
    private static final String BASE_URL = "https://api.speechify.ai/v1";
    private static final int MAX_INPUT_CHARACTERS = 2_000;
    private static final TextToSpeechCatalogItem DEFAULT_MODEL = new TextToSpeechCatalogItem(
            "simba-3.0",
            "Simba 3.0",
            "Speechify's multilingual streaming-native model"
    );
    private static final TextToSpeechCatalogItem RECOMMENDED_ENGLISH_MODEL = new TextToSpeechCatalogItem(
            "simba-3.2",
            "Simba 3.2",
            "Speechify's recommended English model"
    );
    private static final TextToSpeechCatalogItem DEFAULT_VOICE = TextToSpeechCatalogItem.of("geffen_32", "Geffen");
    private static final List<TextToSpeechCatalogItem> BUNDLED_MODELS = List.of(DEFAULT_MODEL, RECOMMENDED_ENGLISH_MODEL);
    private static final List<TextToSpeechCatalogItem> BUNDLED_VOICES = List.of(DEFAULT_VOICE);

    public SpeechifyTextToSpeechProvider(TtsHttpTransport transport, CredentialResolver credentialResolver) {
        super(transport, credentialResolver);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Speechify";
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
    public int maxInputCharacters() {
        return MAX_INPUT_CHARACTERS;
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchModels() throws Exception {
        TtsHttpResponse response = get(URI.create("%s/audio/models".formatted(BASE_URL)), authHeaders(apiKey()));
        JsonNode root = catalogRoot(response, "Speechify model catalog response was invalid.");
        JsonNode items = root == null ? null : root.get("models");
        if (items == null || !items.isArray()) {
            throw new IllegalStateException("Speechify model catalog response was invalid.");
        }

        List<TextToSpeechCatalogItem> models = new ArrayList<>();
        items.forEach(model -> {
            String id = text(model, "id");
            String label = text(model, "name");
            if (StringUtils.isNotBlank(id)) {
                models.add(new TextToSpeechCatalogItem(
                        id,
                        StringUtils.defaultIfBlank(label, id),
                        text(model, "description")
                ));
            }
        });
        if (!items.isEmpty() && models.isEmpty()) {
            throw new IllegalStateException("Speechify model catalog response did not contain valid models.");
        }
        return nonEmptyOrBundled(models, BUNDLED_MODELS);
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        TtsHttpResponse response = get(URI.create("%s/voices".formatted(BASE_URL)), authHeaders(apiKey()));
        JsonNode root = catalogRoot(response, "Speechify voice catalog response was invalid.");
        JsonNode items = root == null ? null : root.get("voices");
        if (items == null || !items.isArray()) {
            throw new IllegalStateException("Speechify voice catalog response was invalid.");
        }

        List<TextToSpeechCatalogItem> voices = new ArrayList<>();
        items.forEach(voice -> {
            String id = text(voice, "id");
            String label = text(voice, "display_name");
            if (StringUtils.isNotBlank(id)) {
                voices.add(new TextToSpeechCatalogItem(
                        id,
                        StringUtils.defaultIfBlank(label, id),
                        voiceDescription(voice)
                ));
            }
        });
        if (!items.isEmpty() && voices.isEmpty()) {
            throw new IllegalStateException("Speechify voice catalog response did not contain valid voices.");
        }
        return nonEmptyOrBundled(voices, BUNDLED_VOICES);
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception {
        return synthesize(request, apiKey());
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request, String apiKey) throws Exception {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("input", request.text());
        body.put("voice_id", StringUtils.defaultIfBlank(request.voiceId(), DEFAULT_VOICE.id()));
        body.put("audio_format", "mp3");
        body.put("model", StringUtils.defaultIfBlank(request.modelId(), DEFAULT_MODEL.id()));
        TtsHttpResponse response = postJson(
                URI.create("%s/audio/speech".formatted(BASE_URL)),
                jsonHeaders(apiKey),
                body
        );
        return speechAudio(response);
    }

    private JsonNode catalogRoot(TtsHttpResponse response, String invalidResponseMessage) {
        try {
            return jsonBody(response);
        } catch (Exception e) {
            throw new IllegalStateException(invalidResponseMessage);
        }
    }

    private static TextToSpeechAudio speechAudio(TtsHttpResponse response) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Speechify TTS returned an invalid response.");
        }
        String audioData = root == null ? "" : root.path("audio_data").asText("");
        String format = root == null ? "" : root.path("audio_format").asText("");
        if (StringUtils.isBlank(audioData) || !Strings.CI.equals(format, "mp3")) {
            throw new IllegalStateException("Speechify TTS returned an invalid MP3 response.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(audioData);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Speechify TTS returned invalid Base64 audio.");
        }
        if (bytes.length == 0) {
            throw new IllegalStateException("Speechify TTS returned an empty audio response.");
        }
        return new TextToSpeechAudio(bytes, "audio/mpeg", "mp3");
    }

    private static Map<String, String> authHeaders(String apiKey) {
        return Map.of("Authorization", "Bearer %s".formatted(apiKey));
    }

    private static Map<String, String> jsonHeaders(String apiKey) {
        return Map.of(
                "Authorization", "Bearer %s".formatted(apiKey),
                "Content-Type", "application/json",
                "Accept", "application/json"
        );
    }

    private static String text(JsonNode node, String field) {
        return StringUtils.normalizeSpace(node.path(field).asText(""));
    }

    private static String voiceDescription(JsonNode voice) {
        List<String> parts = new ArrayList<>();
        String locale = text(voice, "locale");
        String gender = text(voice, "gender");
        String type = text(voice, "type");
        if (StringUtils.isNotBlank(locale)) {
            parts.add(locale);
        }
        if (StringUtils.isNotBlank(gender) && !Strings.CI.equals(gender, "not_specified")) {
            parts.add(gender.toLowerCase(Locale.ROOT));
        }
        if (StringUtils.isNotBlank(type)) {
            parts.add(type.toLowerCase(Locale.ROOT));
        }
        return String.join(" · ", parts);
    }
}
