package com.github.drafael.chat4j.tts.provider.listenhub;

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
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public class ListenHubTextToSpeechProvider extends AbstractHttpTextToSpeechProvider {

    public static final String ID = "listenhub";
    public static final String ENV_VAR = "LISTENHUB_API_KEY";
    private static final String BASE_URL = "https://api.marswave.ai/openapi/v1";
    private static final int READ_ALOUD_CHUNK_CHARACTERS = 1_000;
    private static final TextToSpeechCatalogItem DEFAULT_MODEL = TextToSpeechCatalogItem.of("listenhub-tts", "ListenHub TTS");
    private static final TextToSpeechCatalogItem DEFAULT_VOICE = TextToSpeechCatalogItem.of("travel-girl-english", "Mia");
    private static final List<TextToSpeechCatalogItem> BUNDLED_MODELS = List.of(DEFAULT_MODEL);
    private static final List<TextToSpeechCatalogItem> BUNDLED_VOICES = List.of(DEFAULT_VOICE);

    public ListenHubTextToSpeechProvider(TtsHttpTransport transport, CredentialResolver credentialResolver) {
        super(transport, credentialResolver);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "ListenHub";
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
        return READ_ALOUD_CHUNK_CHARACTERS;
    }

    @Override
    public TextToSpeechCatalogItem normalizeModelSelection(TextToSpeechCatalogItem model) {
        return DEFAULT_MODEL;
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchModels() {
        return BUNDLED_MODELS;
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        TtsHttpResponse response = get(URI.create("%s/speakers/list".formatted(BASE_URL)), authHeaders(apiKey()));
        JsonNode root = jsonBody(response);
        requireApplicationSuccess(root, "ListenHub voice catalog response was invalid.");
        JsonNode items = root.path("data").get("items");
        if (items == null || !items.isArray()) {
            throw new IllegalStateException("ListenHub voice catalog response was invalid.");
        }

        List<TextToSpeechCatalogItem> voices = new ArrayList<>();
        items.forEach(item -> {
            String id = StringUtils.trimToEmpty(item.path("speakerId").asText(""));
            String label = StringUtils.trimToEmpty(item.path("name").asText(""));
            if (StringUtils.isBlank(id) || StringUtils.isBlank(label)) {
                return;
            }
            String description = StringUtils.normalizeSpace(item.path("profile").path("description").asText(""));
            voices.add(new TextToSpeechCatalogItem(id, label, description));
        });
        if (!items.isEmpty() && voices.isEmpty()) {
            throw new IllegalStateException("ListenHub voice catalog response did not contain valid voices.");
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
        body.put("voice", StringUtils.defaultIfBlank(request.voiceId(), DEFAULT_VOICE.id()));
        body.put("response_format", "mp3");
        TtsHttpResponse response = postJson(
                URI.create("%s/tts".formatted(BASE_URL)),
                jsonHeaders(apiKey),
                body
        );
        return mp3Audio(response);
    }

    private Map<String, String> authHeaders(String apiKey) {
        return Map.of("Authorization", "Bearer %s".formatted(apiKey));
    }

    private Map<String, String> jsonHeaders(String apiKey) {
        return Map.of(
                "Authorization", "Bearer %s".formatted(apiKey),
                "Content-Type", "application/json",
                "Accept", "audio/mpeg"
        );
    }

    private static TextToSpeechAudio mp3Audio(TtsHttpResponse response) {
        byte[] bytes = response.body();
        if (bytes.length == 0) {
            throw new IllegalStateException("ListenHub TTS returned an empty audio response.");
        }
        String contentType = response.firstHeader("content-type");
        String mediaType = StringUtils.substringBefore(StringUtils.defaultString(contentType), ";").trim();
        if (Strings.CI.equals(mediaType, "audio/mpeg")) {
            return new TextToSpeechAudio(bytes, contentType, "mp3");
        }
        throwApplicationErrorIfPresent(bytes);
        throw new IllegalStateException("ListenHub TTS returned an unexpected response.");
    }

    private static void requireApplicationSuccess(JsonNode root, String invalidResponseMessage) {
        Integer code = applicationCode(root);
        if (code == null) {
            throw new IllegalStateException(invalidResponseMessage);
        }
        if (code != 0) {
            throw applicationFailure(root, code);
        }
    }

    private static void throwApplicationErrorIfPresent(byte[] body) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            return;
        }
        Integer code = applicationCode(root);
        if (code != null && code != 0) {
            throw applicationFailure(root, code);
        }
    }

    private static Integer applicationCode(JsonNode root) {
        JsonNode code = root == null ? null : root.get("code");
        return code != null && code.isIntegralNumber() && code.canConvertToInt() ? code.intValue() : null;
    }

    private static IllegalStateException applicationFailure(JsonNode root, int code) {
        String message = StringUtils.normalizeSpace(root.path("message").asText(""));
        String suffix = StringUtils.isBlank(message) ? "" : ": %s".formatted(message);
        return new IllegalStateException("ListenHub request failed (code %d)%s".formatted(code, suffix));
    }
}
