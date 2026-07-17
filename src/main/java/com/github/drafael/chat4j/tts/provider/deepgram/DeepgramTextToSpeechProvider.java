package com.github.drafael.chat4j.tts.provider.deepgram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import com.github.drafael.chat4j.tts.provider.AbstractHttpTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.TtsHttpTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public class DeepgramTextToSpeechProvider extends AbstractHttpTextToSpeechProvider {

    public static final String ID = "deepgram";
    public static final String ENV_VAR = "DEEPGRAM_API_KEY";
    private static final String BASE_URL = "https://api.deepgram.com/v1";
    private static final int READ_ALOUD_CHUNK_CHARACTERS = 140;
    private static final int SAMPLE_RATE = 24000;
    private static final short CHANNELS = 1;
    private static final short BITS_PER_SAMPLE = 16;
    private static final TextToSpeechCatalogItem DEFAULT_MODEL = new TextToSpeechCatalogItem(
            "aura-2",
            "Aura 2",
            "Deepgram TTS model family"
    );
    private static final TextToSpeechCatalogItem DEFAULT_VOICE = new TextToSpeechCatalogItem(
            "aura-2-thalia-en",
            "thalia",
            "Clear, confident, energetic, enthusiastic"
    );
    private static final List<TextToSpeechCatalogItem> BUNDLED_MODELS = List.of(DEFAULT_MODEL);
    private static final List<TextToSpeechCatalogItem> BUNDLED_VOICES = List.of(DEFAULT_VOICE);

    public DeepgramTextToSpeechProvider(TtsHttpTransport transport) {
        super(transport);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Deepgram";
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
    public List<TextToSpeechCatalogItem> fetchModels() throws Exception {
        Map<String, TextToSpeechCatalogItem> modelFamilies = new LinkedHashMap<>();
        fetchVoiceModels().stream()
                .map(TextToSpeechCatalogItem::id)
                .map(DeepgramTextToSpeechProvider::modelFamilyId)
                .filter(StringUtils::isNotBlank)
                .map(DeepgramTextToSpeechProvider::modelFamilyItem)
                .forEach(model -> modelFamilies.putIfAbsent(model.id(), model));
        return nonEmptyOrBundled(List.copyOf(modelFamilies.values()), BUNDLED_MODELS);
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        return fetchVoiceModels();
    }

    @Override
    public TextToSpeechCatalogItem normalizeModelSelection(TextToSpeechCatalogItem model) {
        if (model == null) {
            return DEFAULT_MODEL;
        }
        String familyId = modelFamilyId(model.id());
        return StringUtils.isBlank(familyId) ? DEFAULT_MODEL : modelFamilyItem(familyId);
    }

    @Override
    public TextToSpeechCatalogItem normalizeVoiceSelection(TextToSpeechCatalogItem voice) {
        if (voice == null) {
            return DEFAULT_VOICE;
        }
        String originalId = StringUtils.trimToEmpty(voice.id()).toLowerCase(Locale.ROOT);
        String id = normalizeVoiceModelId(originalId);
        if (!id.equals(originalId)) {
            return DEFAULT_VOICE;
        }
        String description = DEFAULT_VOICE.id().equals(id) || !DEFAULT_VOICE.description().equals(voice.description())
                ? voice.description()
                : "";
        return new TextToSpeechCatalogItem(id, voice.label(), description);
    }

    @Override
    public List<TextToSpeechCatalogItem> voicesForModel(TextToSpeechCatalogItem model, List<TextToSpeechCatalogItem> voices) {
        if (voices == null || voices.isEmpty()) {
            return List.of();
        }
        String familyId = model == null ? DEFAULT_MODEL.id() : modelFamilyId(model.id());
        if (StringUtils.isBlank(familyId)) {
            return voices;
        }
        List<TextToSpeechCatalogItem> matchingVoices = voices.stream()
                .filter(voice -> Strings.CS.startsWith(voice.id(), "%s-".formatted(familyId)))
                .toList();
        return matchingVoices.isEmpty() ? voices : matchingVoices;
    }

    @Override
    public TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception {
        String modelId = normalizeVoiceModelId(StringUtils.defaultIfBlank(request.voiceId(), request.modelId()));
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("text", request.text());
        TtsHttpResponse response = postJson(
                URI.create("%s/speak?model=%s&encoding=linear16&container=none&sample_rate=%d".formatted(BASE_URL, modelId, SAMPLE_RATE)),
                jsonHeaders(),
                body
        );
        return new TextToSpeechAudio(wavBytes(response), "audio/wav", "wav");
    }

    private List<TextToSpeechCatalogItem> fetchVoiceModels() throws Exception {
        TtsHttpResponse response = get(URI.create("%s/models".formatted(BASE_URL)), authHeaders());
        List<TextToSpeechCatalogItem> voiceModels = new ArrayList<>();
        JsonNode root = jsonBody(response);
        JsonNode ttsModels = root == null ? null : root.path("tts");
        if (ttsModels == null || !ttsModels.isArray()) {
            throw new IllegalStateException("Deepgram model catalog response was invalid.");
        }
        ttsModels.forEach(model -> {
            String id = voiceModelId(model);
            if (StringUtils.isNotBlank(id)) {
                voiceModels.add(new TextToSpeechCatalogItem(id, voiceLabel(id, model), voiceDescription(model)));
            }
        });
        if (!ttsModels.isEmpty() && voiceModels.isEmpty()) {
            throw new IllegalStateException("Deepgram model catalog response did not contain valid TTS voices.");
        }
        return nonEmptyOrBundled(voiceModels, BUNDLED_VOICES);
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "Token %s".formatted(apiKey()));
    }

    private Map<String, String> jsonHeaders() {
        return Map.of(
                "Authorization", "Token %s".formatted(apiKey()),
                "Content-Type", "application/json",
                "Accept", "audio/l16"
        );
    }

    private static byte[] wavBytes(TtsHttpResponse response) {
        String contentType = StringUtils.defaultString(response.firstHeader("content-type")).toLowerCase(Locale.ROOT);
        if (!Strings.CS.startsWith(contentType, "audio/l16")) {
            throw new IllegalStateException("Deepgram TTS returned an unexpected audio content type.");
        }
        byte[] pcm = response.body();
        if (pcm.length % 2 != 0) {
            throw new IllegalStateException("Deepgram TTS returned malformed linear16 audio.");
        }

        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        short blockAlign = (short) (CHANNELS * BITS_PER_SAMPLE / 8);
        byte[] wav = new byte[44 + pcm.length];
        writeAscii(wav, 0, "RIFF");
        writeLittleEndianInt(wav, 4, wav.length - 8);
        writeAscii(wav, 8, "WAVE");
        writeAscii(wav, 12, "fmt ");
        writeLittleEndianInt(wav, 16, 16);
        writeLittleEndianShort(wav, 20, (short) 1);
        writeLittleEndianShort(wav, 22, CHANNELS);
        writeLittleEndianInt(wav, 24, SAMPLE_RATE);
        writeLittleEndianInt(wav, 28, byteRate);
        writeLittleEndianShort(wav, 32, blockAlign);
        writeLittleEndianShort(wav, 34, BITS_PER_SAMPLE);
        writeAscii(wav, 36, "data");
        writeLittleEndianInt(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static void writeAscii(byte[] bytes, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            bytes[offset + index] = (byte) value.charAt(index);
        }
    }

    private static void writeLittleEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(byte[] bytes, int offset, short value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static String voiceModelId(JsonNode model) {
        String canonicalName = StringUtils.trimToEmpty(model.path("canonical_name").asText(""));
        if (isDeepgramTtsVoiceModelId(canonicalName)) {
            return canonicalName.toLowerCase(Locale.ROOT);
        }
        String name = StringUtils.trimToEmpty(model.path("name").asText(""));
        return isDeepgramTtsVoiceModelId(name) ? name.toLowerCase(Locale.ROOT) : "";
    }

    private static String normalizeVoiceModelId(String id) {
        String normalized = StringUtils.defaultIfBlank(id, DEFAULT_VOICE.id()).trim().toLowerCase(Locale.ROOT);
        return isDeepgramTtsVoiceModelId(normalized) ? normalized : DEFAULT_VOICE.id();
    }

    private static boolean isDeepgramTtsVoiceModelId(String value) {
        String normalized = StringUtils.trimToEmpty(value);
        String[] parts = normalized.split("-", -1);
        return parts.length >= 4
                && "aura".equals(parts[0])
                && "en".equals(parts[parts.length - 1])
                && Arrays.stream(parts).allMatch(StringUtils::isNotBlank)
                && normalized.chars().allMatch(DeepgramTextToSpeechProvider::isSafeModelIdCharacter);
    }

    private static boolean isSafeModelIdCharacter(int value) {
        return Character.isLetterOrDigit(value) || value == '-';
    }

    private static String modelFamilyId(String id) {
        String normalized = StringUtils.trimToEmpty(id).toLowerCase(Locale.ROOT);
        if (isDeepgramTtsModelFamilyId(normalized)) {
            return normalized;
        }
        if (!isDeepgramTtsVoiceModelId(normalized)) {
            return "";
        }
        String[] parts = normalized.split("-");
        return "%s-%s".formatted(parts[0], parts[1]);
    }

    private static boolean isDeepgramTtsModelFamilyId(String value) {
        String normalized = StringUtils.trimToEmpty(value);
        String[] parts = normalized.split("-", -1);
        return parts.length == 2
                && "aura".equals(parts[0])
                && StringUtils.isNotBlank(parts[1])
                && normalized.chars().allMatch(DeepgramTextToSpeechProvider::isSafeModelIdCharacter);
    }

    private static TextToSpeechCatalogItem modelFamilyItem(String id) {
        return new TextToSpeechCatalogItem(id, modelFamilyLabel(id), "Deepgram TTS model family");
    }

    private static String modelFamilyLabel(String id) {
        String[] parts = id.split("-");
        if (parts.length != 2) {
            return id;
        }
        return "%s %s".formatted(StringUtils.capitalize(parts[0]), parts[1]);
    }

    private static String voiceLabel(String id, JsonNode model) {
        String name = StringUtils.trimToEmpty(model.path("name").asText(""));
        if (StringUtils.isNotBlank(name) && !isDeepgramTtsVoiceModelId(name)) {
            return name;
        }
        String[] parts = id.split("-");
        return parts.length >= 3 ? parts[2] : id;
    }

    private static String voiceDescription(JsonNode model) {
        JsonNode tags = model.path("metadata").path("tags");
        if (!tags.isArray()) {
            return "";
        }
        List<String> descriptions = new ArrayList<>();
        tags.forEach(tag -> {
            String value = StringUtils.normalizeSpace(tag.asText(""));
            if (StringUtils.isNotBlank(value)) {
                descriptions.add(value);
            }
        });
        return String.join(", ", descriptions);
    }
}
