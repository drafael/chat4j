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
    private static final TextToSpeechCatalogItem DEFAULT_VOICE_MODEL = new TextToSpeechCatalogItem(
            "aura-2-thalia-en",
            "thalia",
            "Clear, confident, energetic, enthusiastic"
    );
    private static final List<TextToSpeechCatalogItem> BUNDLED_VOICE_MODELS = List.of(DEFAULT_VOICE_MODEL);

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
        return DEFAULT_VOICE_MODEL;
    }

    @Override
    public TextToSpeechCatalogItem defaultVoice() {
        return DEFAULT_VOICE_MODEL;
    }

    @Override
    public List<TextToSpeechCatalogItem> bundledModels() {
        return BUNDLED_VOICE_MODELS;
    }

    @Override
    public List<TextToSpeechCatalogItem> bundledVoices() {
        return BUNDLED_VOICE_MODELS;
    }

    @Override
    public int maxInputCharacters() {
        return READ_ALOUD_CHUNK_CHARACTERS;
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchModels() throws Exception {
        return fetchVoiceModels();
    }

    @Override
    public List<TextToSpeechCatalogItem> fetchVoices() throws Exception {
        return fetchVoiceModels();
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

    @Override
    public String availableMessage() {
        return "Text is sent to Deepgram for speech synthesis. No API key is stored by Chat4J.";
    }

    private List<TextToSpeechCatalogItem> fetchVoiceModels() throws Exception {
        TtsHttpResponse response = get(URI.create("%s/models".formatted(BASE_URL)), authHeaders());
        List<TextToSpeechCatalogItem> voiceModels = new ArrayList<>();
        jsonBody(response).path("tts").forEach(model -> {
            String id = voiceModelId(model);
            if (StringUtils.isNotBlank(id)) {
                voiceModels.add(new TextToSpeechCatalogItem(id, voiceLabel(id, model), voiceDescription(model)));
            }
        });
        return nonEmptyOrBundled(voiceModels, BUNDLED_VOICE_MODELS);
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
        String normalized = StringUtils.defaultIfBlank(id, DEFAULT_VOICE_MODEL.id()).trim().toLowerCase(Locale.ROOT);
        return isDeepgramTtsVoiceModelId(normalized) ? normalized : DEFAULT_VOICE_MODEL.id();
    }

    private static boolean isDeepgramTtsVoiceModelId(String value) {
        return Strings.CS.startsWith(value, "aura-")
                && Strings.CS.endsWith(value, "-en")
                && value.split("-").length >= 4
                && value.chars().allMatch(DeepgramTextToSpeechProvider::isSafeModelIdCharacter);
    }

    private static boolean isSafeModelIdCharacter(int value) {
        return Character.isLetterOrDigit(value) || value == '-';
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
