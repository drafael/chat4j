package com.github.drafael.chat4j.tts.provider.groq;

import com.github.drafael.chat4j.tts.provider.AbstractHttpTextToSpeechProvider;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.TtsHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.util.stream.StreamSupport.stream;

public class GroqTextToSpeechProvider extends AbstractHttpTextToSpeechProvider {

    public static final String ID = "groq";
    public static final String ENV_VAR = "GROQ_API_KEY";
    private static final String BASE_URL = "https://api.groq.com/openai/v1";
    private static final int MAX_INPUT_CHARACTERS = 200;
    private static final TextToSpeechCatalogItem DEFAULT_MODEL = TextToSpeechCatalogItem.of("canopylabs/orpheus-v1-english", "Orpheus English");
    private static final TextToSpeechCatalogItem DEFAULT_VOICE = TextToSpeechCatalogItem.of("hannah", "Hannah");
    private static final TextToSpeechCatalogItem ARABIC_MODEL = TextToSpeechCatalogItem.of("canopylabs/orpheus-arabic-saudi", "Orpheus Arabic Saudi");
    private static final List<TextToSpeechCatalogItem> BUNDLED_MODELS = List.of(DEFAULT_MODEL, ARABIC_MODEL);
    private static final List<TextToSpeechCatalogItem> ENGLISH_VOICES = List.of(
            TextToSpeechCatalogItem.of("autumn", "Autumn"),
            TextToSpeechCatalogItem.of("diana", "Diana"),
            DEFAULT_VOICE,
            TextToSpeechCatalogItem.of("austin", "Austin"),
            TextToSpeechCatalogItem.of("daniel", "Daniel"),
            TextToSpeechCatalogItem.of("troy", "Troy")
    );
    private static final List<TextToSpeechCatalogItem> ARABIC_VOICES = List.of(
            TextToSpeechCatalogItem.of("abdullah", "Abdullah"),
            TextToSpeechCatalogItem.of("fahad", "Fahad"),
            TextToSpeechCatalogItem.of("sultan", "Sultan"),
            TextToSpeechCatalogItem.of("lulwa", "Lulwa"),
            TextToSpeechCatalogItem.of("noura", "Noura"),
            TextToSpeechCatalogItem.of("aisha", "Aisha")
    );
    private static final List<TextToSpeechCatalogItem> BUNDLED_VOICES = Stream.concat(ENGLISH_VOICES.stream(), ARABIC_VOICES.stream()).toList();

    public GroqTextToSpeechProvider(TtsHttpTransport transport) {
        super(transport);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Groq";
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
    public String defaultResponseFormat() {
        return "wav";
    }

    @Override
    public TextToSpeechCatalogItem normalizeModelSelection(TextToSpeechCatalogItem model) {
        return Strings.CS.equals(model.id(), "playai-tts") ? DEFAULT_MODEL : model;
    }

    @Override
    public TextToSpeechCatalogItem normalizeVoiceSelection(TextToSpeechCatalogItem voice) {
        return Strings.CS.endsWith(voice.id(), "-PlayAI") ? DEFAULT_VOICE : voice;
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchModels() throws Exception {
        TtsHttpResponse response = get(URI.create("%s/models".formatted(BASE_URL)), authHeaders());
        List<TextToSpeechCatalogItem> models = new ArrayList<>();
        JsonNode root = jsonBody(response);
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("Groq model catalog response was invalid.");
        }
        if (!data.isEmpty() && stream(data.spliterator(), false)
                .noneMatch(model -> StringUtils.isNotBlank(model.path("id").asText("")))) {
            throw new IllegalStateException("Groq model catalog response did not contain valid model IDs.");
        }
        data.forEach(model -> {
            String id = model.path("id").asText("");
            if (isCurrentSpeechModel(id)) {
                models.add(TextToSpeechCatalogItem.of(id, id));
            }
        });
        return nonEmptyOrBundled(models, BUNDLED_MODELS);
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() {
        return BUNDLED_VOICES;
    }

    @Override
    public List<TextToSpeechCatalogItem> voicesForModel(TextToSpeechCatalogItem model, List<TextToSpeechCatalogItem> voices) {
        List<TextToSpeechCatalogItem> allowed = isArabicModel(model == null ? "" : model.id()) ? ARABIC_VOICES : ENGLISH_VOICES;
        List<TextToSpeechCatalogItem> filtered = allowed.stream()
                .filter(allowedVoice -> containsVoice(voices, allowedVoice.id()))
                .toList();
        return filtered.isEmpty() ? allowed : filtered;
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        String modelId = normalizeModelId(request.modelId());
        body.put("model", modelId);
        body.put("voice", normalizeVoiceId(modelId, request.voiceId()));
        body.put("input", request.text());
        body.put("response_format", StringUtils.defaultIfBlank(request.responseFormat(), "wav"));
        TtsHttpResponse response = postJson(URI.create("%s/audio/speech".formatted(BASE_URL)), jsonHeaders(), body);
        return audioBody(response, "wav");
    }

    private String normalizeModelId(String modelId) {
        return Strings.CS.equals(modelId, "playai-tts")
                ? DEFAULT_MODEL.id()
                : StringUtils.defaultIfBlank(modelId, DEFAULT_MODEL.id());
    }

    private boolean isCurrentSpeechModel(String modelId) {
        return Strings.CI.contains(modelId, "orpheus")
                || (Strings.CI.contains(modelId, "tts") && !Strings.CI.equals(modelId, "playai-tts"))
                || Strings.CI.contains(modelId, "speech");
    }

    private String normalizeVoiceId(String modelId, String voiceId) {
        if (isArabicModel(modelId)) {
            return isArabicVoice(voiceId) ? voiceId : "abdullah";
        }
        return isEnglishVoice(voiceId) ? voiceId : DEFAULT_VOICE.id();
    }

    private boolean isArabicModel(String modelId) {
        return Strings.CS.contains(modelId, "arabic-saudi");
    }

    private boolean isEnglishVoice(String voiceId) {
        return containsVoice(ENGLISH_VOICES, voiceId);
    }

    private boolean isArabicVoice(String voiceId) {
        return containsVoice(ARABIC_VOICES, voiceId);
    }

    private static boolean containsVoice(List<TextToSpeechCatalogItem> voices, String voiceId) {
        return voices != null && voices.stream().anyMatch(voice -> Strings.CS.equals(voice.id(), voiceId));
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "Bearer %s".formatted(apiKey()));
    }

    private Map<String, String> jsonHeaders() {
        return Map.of(
                "Authorization", "Bearer %s".formatted(apiKey()),
                "Content-Type", "application/json",
                "Accept", "audio/wav, audio/mpeg"
        );
    }
}
