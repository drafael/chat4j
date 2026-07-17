package com.github.drafael.chat4j.tts.provider.deepgram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepgramTextToSpeechProviderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        CredentialResolver.init(emptyMap());
    }

    @Test
    @DisplayName("Deepgram exposes a model family and a default voice as separate selections")
    void metadata_whenCreated_separatesModelAndVoiceSelections() {
        var subject = new DeepgramTextToSpeechProvider(request -> json("{}"));

        assertThat(subject.defaultModel().id()).isEqualTo("aura-2");
        assertThat(subject.defaultVoice().id()).isEqualTo("aura-2-thalia-en");
        assertThat(subject.bundledModels()).extracting(TextToSpeechCatalogItem::id).containsExactly("aura-2");
        assertThat(subject.bundledVoices()).extracting(TextToSpeechCatalogItem::id).containsExactly("aura-2-thalia-en");
    }

    @Test
    @DisplayName("Deepgram uses documented Aura voice model as the model query parameter")
    void synthesize_whenCalled_sendsVoiceModelToSpeakEndpoint() throws Exception {
        CredentialResolver.init(Map.of(DeepgramTextToSpeechProvider.ENV_VAR, "test-key"));
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = new DeepgramTextToSpeechProvider(request -> {
            captured[0] = request;
            return new TtsHttpResponse(200, Map.of("content-type", List.of("audio/l16;rate=24000")), new byte[]{1, 2, 3, 4});
        });

        var audio = subject.synthesize(new TextToSpeechRequest(
                DeepgramTextToSpeechProvider.ID,
                "aura-2-asteria-en",
                "aura-2-thalia-en",
                "hello",
                "mp3"
        ));

        assertThat(captured[0].method()).isEqualTo("POST");
        assertThat(captured[0].uri().getPath()).isEqualTo("/v1/speak");
        assertThat(queryParameter(captured[0], "model")).isEqualTo("aura-2-thalia-en");
        assertThat(queryParameter(captured[0], "encoding")).isEqualTo("linear16");
        assertThat(queryParameter(captured[0], "container")).isEqualTo("none");
        assertThat(queryParameter(captured[0], "sample_rate")).isEqualTo("24000");
        assertThat(captured[0].headers().get("Authorization")).startsWith("Token ");
        assertThat(captured[0].headers()).containsEntry("Accept", "audio/l16");
        assertThat(OBJECT_MAPPER.readTree(captured[0].body()).path("text").asText()).isEqualTo("hello");
        assertThat(audio.contentType()).isEqualTo("audio/wav");
        assertThat(audio.format()).isEqualTo("wav");
        assertThat(new String(audio.bytes(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(audio.bytes(), 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(audio.bytes()).hasSize(48);
    }

    @Test
    @DisplayName("Deepgram read aloud uses short chunks for fast first audio")
    void maxInputCharacters_whenAsked_usesResponsiveReadAloudChunkSize() {
        var subject = new DeepgramTextToSpeechProvider(request -> json("{}"));

        assertThat(subject.maxInputCharacters()).isEqualTo(140);
    }

    @Test
    @DisplayName("Deepgram catalog discovery exposes model families separately from voices")
    void fetchModels_whenModelsEndpointReturnsTtsItems_returnsModelFamilies() throws Exception {
        CredentialResolver.init(Map.of(DeepgramTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new DeepgramTextToSpeechProvider(request -> json("""
                {
                  "stt": [{"canonical_name":"nova-3-general"}],
                  "tts": [
                    {"name":"thalia","canonical_name":"aura-2-thalia-en","metadata":{"tags":["feminine","clear"]}},
                    {"name":"asteria","canonical_name":"aura-2-asteria-en","metadata":{"tags":["feminine","warm"]}},
                    {"name":"not-a-voice","canonical_name":"nova-3-general"}
                  ]
                }
                """));

        List<TextToSpeechCatalogItem> models = subject.fetchModels();

        assertThat(models).extracting(TextToSpeechCatalogItem::id).containsExactly("aura-2");
        assertThat(models.getFirst().label()).isEqualTo("Aura 2");
    }

    @Test
    @DisplayName("Deepgram catalog discovery reads canonical TTS voice model ids as voices")
    void fetchVoices_whenModelsEndpointReturnsTtsItems_usesCanonicalVoiceModelIds() throws Exception {
        CredentialResolver.init(Map.of(DeepgramTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new DeepgramTextToSpeechProvider(request -> json("""
                {
                  "stt": [{"canonical_name":"nova-3-general"}],
                  "tts": [
                    {"name":"thalia","canonical_name":"aura-2-thalia-en","metadata":{"tags":["feminine","clear"]}},
                    {"name":"not-a-voice","canonical_name":"nova-3-general"}
                  ]
                }
                """));

        List<TextToSpeechCatalogItem> voices = subject.fetchVoices();

        assertThat(voices).extracting(TextToSpeechCatalogItem::id).containsExactly("aura-2-thalia-en");
        assertThat(voices.getFirst().label()).isEqualTo("thalia");
        assertThat(voices.getFirst().description()).isEqualTo("feminine, clear");
    }

    @Test
    @DisplayName("Deepgram model discovery rejects a missing TTS array")
    void fetchModels_whenTtsArrayMissing_throws() {
        CredentialResolver.init(Map.of(DeepgramTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new DeepgramTextToSpeechProvider(request -> json("{}"));

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("Deepgram voice discovery rejects a missing TTS array")
    void fetchVoices_whenTtsArrayMissing_throws() {
        CredentialResolver.init(Map.of(DeepgramTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new DeepgramTextToSpeechProvider(request -> json("{}"));

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("Deepgram voice discovery rejects entries without canonical ids")
    void fetchVoices_whenCanonicalIdsMissing_throws() {
        CredentialResolver.init(Map.of(DeepgramTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new DeepgramTextToSpeechProvider(request -> json("{\"tts\":[{}]}"));

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid TTS voices");
    }

    @Test
    @DisplayName("Deepgram voice list follows the selected model family")
    void voicesForModel_whenMultipleAuraFamiliesExist_filtersByFamily() {
        var subject = new DeepgramTextToSpeechProvider(request -> json("{}"));
        List<TextToSpeechCatalogItem> voices = List.of(
                TextToSpeechCatalogItem.of("aura-2-thalia-en", "thalia"),
                TextToSpeechCatalogItem.of("aura-3-luna-en", "luna")
        );

        List<TextToSpeechCatalogItem> filtered = subject.voicesForModel(TextToSpeechCatalogItem.of("aura-3", "Aura 3"), voices);

        assertThat(filtered).extracting(TextToSpeechCatalogItem::id).containsExactly("aura-3-luna-en");
    }

    @Test
    @DisplayName("Deepgram normalizes legacy saved voice-model model selections to the model family")
    void normalizeModelSelection_whenSavedModelContainsVoiceModel_returnsModelFamily() {
        var subject = new DeepgramTextToSpeechProvider(request -> json("{}"));

        TextToSpeechCatalogItem model = subject.normalizeModelSelection(TextToSpeechCatalogItem.of("aura-2-thalia-en", "thalia"));

        assertThat(model.id()).isEqualTo("aura-2");
        assertThat(model.label()).isEqualTo("Aura 2");
    }

    @Test
    @DisplayName("Deepgram does not copy the default voice description onto other saved voices")
    void normalizeVoiceSelection_whenSavedVoiceUsesFallbackDescription_clearsMisleadingDescription() {
        var subject = new DeepgramTextToSpeechProvider(request -> json("{}"));
        var savedVoice = new TextToSpeechCatalogItem(
                "aura-2-zeus-en",
                "zeus",
                subject.defaultVoice().description()
        );

        TextToSpeechCatalogItem voice = subject.normalizeVoiceSelection(savedVoice);

        assertThat(voice.id()).isEqualTo("aura-2-zeus-en");
        assertThat(voice.description()).isBlank();
    }

    @Test
    @DisplayName("Deepgram falls back to its documented default voice model for invalid selections")
    void synthesize_whenSelectionIsInvalid_usesDefaultVoiceModel() throws Exception {
        CredentialResolver.init(Map.of(DeepgramTextToSpeechProvider.ENV_VAR, "test-key"));
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = new DeepgramTextToSpeechProvider(request -> {
            captured[0] = request;
            return new TtsHttpResponse(200, Map.of("content-type", List.of("audio/l16;rate=24000")), new byte[]{1, 2});
        });

        subject.synthesize(new TextToSpeechRequest(DeepgramTextToSpeechProvider.ID, "aura-2", "aura-2--en", "hello", "mp3"));

        assertThat(queryParameter(captured[0], "model")).isEqualTo("aura-2-thalia-en");
    }

    private static TtsHttpResponse json(String body) {
        return new TtsHttpResponse(
                200,
                Map.of("content-type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String queryParameter(TtsHttpRequest request, String name) {
        return List.of(request.uri().getRawQuery().split("&")).stream()
                .map(parameter -> parameter.split("=", 2))
                .filter(parts -> parts.length == 2)
                .filter(parts -> name.equals(parts[0]))
                .map(parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
                .findFirst()
                .orElse("");
    }
}
