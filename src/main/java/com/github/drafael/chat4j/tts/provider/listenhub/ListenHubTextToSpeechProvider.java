package com.github.drafael.chat4j.tts.provider.listenhub;

import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.AbstractHttpTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpClient;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;
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

    public ListenHubTextToSpeechProvider(@NonNull TtsHttpClient httpClient, @NonNull CredentialResolver credentialResolver) {
        super(httpClient, credentialResolver);
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
        ListenHubApi.Response response = getJson(
                URI.create("%s/speakers/list".formatted(BASE_URL)),
                authHeaders(apiKey()),
                ListenHubApi.Response.class,
                "ListenHub voice catalog response was invalid."
        );
        requireApplicationSuccess(response, "ListenHub voice catalog response was invalid.");
        List<ListenHubApi.Voice> items = response.data() == null ? null : response.data().items();
        if (items == null) {
            throw new IllegalStateException("ListenHub voice catalog response was invalid.");
        }

        List<TextToSpeechCatalogItem> voices = items.stream()
                .filter(Objects::nonNull)
                .map(ListenHubTextToSpeechProvider::voiceItem)
                .filter(Objects::nonNull)
                .toList();
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
        var body = new ListenHubApi.SynthesisRequest(
                request.text(),
                StringUtils.defaultIfBlank(request.voiceId(), DEFAULT_VOICE.id()),
                "mp3"
        );
        HttpExchangeResponse response = postJson(
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

    private TextToSpeechAudio mp3Audio(HttpExchangeResponse response) {
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

    private static TextToSpeechCatalogItem voiceItem(ListenHubApi.Voice voice) {
        String id = StringUtils.trimToEmpty(voice.speakerId());
        String label = StringUtils.trimToEmpty(voice.name());
        if (StringUtils.isBlank(id) || StringUtils.isBlank(label)) {
            return null;
        }
        String description = voice.profile() == null
                ? ""
                : StringUtils.normalizeSpace(voice.profile().description());
        return new TextToSpeechCatalogItem(id, label, description);
    }

    private static void requireApplicationSuccess(ListenHubApi.Response response, String invalidResponseMessage) {
        Integer code = response == null ? null : response.code();
        if (code == null) {
            throw new IllegalStateException(invalidResponseMessage);
        }
        if (code != 0) {
            throw applicationFailure(response, code);
        }
    }

    private void throwApplicationErrorIfPresent(byte[] body) {
        tryJson(body, ListenHubApi.Response.class)
                .filter(response -> response.code() != null && response.code() != 0)
                .ifPresent(response -> {
                    throw applicationFailure(response, response.code());
                });
    }

    private static IllegalStateException applicationFailure(ListenHubApi.Response response, int code) {
        String message = StringUtils.normalizeSpace(response.message());
        String suffix = StringUtils.isBlank(message) ? "" : ": %s".formatted(message);
        return new IllegalStateException("ListenHub request failed (code %d)%s".formatted(code, suffix));
    }
}
