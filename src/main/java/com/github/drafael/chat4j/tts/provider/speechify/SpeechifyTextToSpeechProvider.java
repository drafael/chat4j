package com.github.drafael.chat4j.tts.provider.speechify;

import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.AbstractHttpTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpClient;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.NonNull;
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

    public SpeechifyTextToSpeechProvider(@NonNull TtsHttpClient httpClient, @NonNull CredentialResolver credentialResolver) {
        super(httpClient, credentialResolver);
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
        SpeechifyApi.ModelsResponse response = getJson(
                URI.create("%s/audio/models".formatted(BASE_URL)),
                authHeaders(apiKey()),
                SpeechifyApi.ModelsResponse.class,
                "Speechify model catalog response was invalid."
        );
        List<SpeechifyApi.Model> items = response.models();
        if (items == null) {
            throw new IllegalStateException("Speechify model catalog response was invalid.");
        }

        List<TextToSpeechCatalogItem> models = items.stream()
                .filter(Objects::nonNull)
                .map(SpeechifyTextToSpeechProvider::modelItem)
                .filter(Objects::nonNull)
                .toList();
        if (!items.isEmpty() && models.isEmpty()) {
            throw new IllegalStateException("Speechify model catalog response did not contain valid models.");
        }
        return nonEmptyOrBundled(models, BUNDLED_MODELS);
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        SpeechifyApi.VoicesResponse response = getJson(
                URI.create("%s/voices".formatted(BASE_URL)),
                authHeaders(apiKey()),
                SpeechifyApi.VoicesResponse.class,
                "Speechify voice catalog response was invalid."
        );
        List<SpeechifyApi.Voice> items = response.voices();
        if (items == null) {
            throw new IllegalStateException("Speechify voice catalog response was invalid.");
        }

        List<TextToSpeechCatalogItem> voices = items.stream()
                .filter(Objects::nonNull)
                .map(SpeechifyTextToSpeechProvider::voiceItem)
                .filter(Objects::nonNull)
                .toList();
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
        var body = new SpeechifyApi.SynthesisRequest(
                request.text(),
                StringUtils.defaultIfBlank(request.voiceId(), DEFAULT_VOICE.id()),
                "mp3",
                StringUtils.defaultIfBlank(request.modelId(), DEFAULT_MODEL.id())
        );
        SpeechifyApi.SynthesisResponse response = postJson(
                URI.create("%s/audio/speech".formatted(BASE_URL)),
                jsonHeaders(apiKey),
                body,
                SpeechifyApi.SynthesisResponse.class,
                "Speechify TTS returned an invalid response."
        );
        return speechAudio(response);
    }

    private static TextToSpeechAudio speechAudio(SpeechifyApi.SynthesisResponse response) {
        String audioData = response.audioData();
        String format = response.audioFormat();
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

    private static TextToSpeechCatalogItem modelItem(SpeechifyApi.Model model) {
        String id = text(model.id());
        if (StringUtils.isBlank(id)) {
            return null;
        }
        return new TextToSpeechCatalogItem(id, StringUtils.defaultIfBlank(text(model.name()), id), text(model.description()));
    }

    private static TextToSpeechCatalogItem voiceItem(SpeechifyApi.Voice voice) {
        String id = text(voice.id());
        if (StringUtils.isBlank(id)) {
            return null;
        }
        return new TextToSpeechCatalogItem(
                id,
                StringUtils.defaultIfBlank(text(voice.displayName()), id),
                voiceDescription(voice)
        );
    }

    private static String text(String value) {
        return StringUtils.normalizeSpace(value);
    }

    private static String voiceDescription(SpeechifyApi.Voice voice) {
        List<String> parts = new ArrayList<>();
        String locale = text(voice.locale());
        String gender = text(voice.gender());
        String type = text(voice.type());
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
