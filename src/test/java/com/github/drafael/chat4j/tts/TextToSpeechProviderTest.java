package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.elevenlabs.ElevenLabsTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.groq.GroqTextToSpeechProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.provider.deepgram.DeepgramTextToSpeechProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextToSpeechProviderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        CredentialResolver.init(emptyMap());
    }

    @Test
    @DisplayName("Default registry includes Deepgram without changing existing HTTP provider order")
    void createDefault_whenCalled_includesDeepgramBeforeExistingNetworkProviders() {
        var subject = TextToSpeechProviderRegistry.createDefault();

        assertThat(subject.providers()).extracting(TextToSpeechProvider::id)
                .containsSubsequence(
                        DeepgramTextToSpeechProvider.ID,
                        GroqTextToSpeechProvider.ID,
                        ElevenLabsTextToSpeechProvider.ID
                );
    }

    @Test
    @DisplayName("ElevenLabs parses TTS-capable models and voices")
    void elevenLabsCatalogs_validResponses_parsesModelsAndVoices() throws Exception {
        CredentialResolver.init(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new ElevenLabsTextToSpeechProvider(request -> {
            if (request.uri().getPath().equals("/v1/models")) {
                return json("""
                        [
                          {"model_id":"eleven_flash_v2_5","name":"Flash","can_do_text_to_speech":true},
                          {"model_id":"not-tts","name":"Not TTS","can_do_text_to_speech":false}
                        ]
                        """);
            }
            return json("""
                    {"voices":[{"voice_id":"voice-1","name":"Voice One","description":"Warm"}]}
                    """);
        });

        var models = subject.fetchModels();
        var voices = subject.fetchVoices();

        assertThat(models).extracting(TextToSpeechCatalogItem::id).containsExactly("eleven_flash_v2_5");
        assertThat(voices).extracting(TextToSpeechCatalogItem::id).containsExactly("voice-1");
        assertThat(voices.getFirst().description()).isEqualTo("Warm");
    }

    @Test
    @DisplayName("ElevenLabs model discovery rejects a missing model array")
    void fetchModels_whenElevenLabsModelArrayMissing_throws() {
        CredentialResolver.init(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new ElevenLabsTextToSpeechProvider(request -> json("{}"));

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("ElevenLabs model discovery rejects entries without model ids")
    void fetchModels_whenElevenLabsModelIdsMissing_throws() {
        CredentialResolver.init(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new ElevenLabsTextToSpeechProvider(request -> json("[{}]"));

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid model IDs");
    }

    @Test
    @DisplayName("ElevenLabs voice discovery rejects a missing voice array")
    void fetchVoices_whenElevenLabsVoiceArrayMissing_throws() {
        CredentialResolver.init(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new ElevenLabsTextToSpeechProvider(request -> json("{}"));

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("ElevenLabs voice discovery rejects entries without voice ids")
    void fetchVoices_whenElevenLabsVoiceIdsMissing_throws() {
        CredentialResolver.init(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new ElevenLabsTextToSpeechProvider(request -> json("{\"voices\":[{}]}"));

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid voices");
    }

    @Test
    @DisplayName("Groq model discovery keeps current TTS models without showing obsolete PlayAI")
    void fetchModels_whenGroqTtsModelPresent_keepsCurrentTtsModel() throws Exception {
        CredentialResolver.init(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new GroqTextToSpeechProvider(request -> json("""
                {"data":[{"id":"llama-3.3-70b"},{"id":"playai-tts"},{"id":"canopylabs/orpheus-v1-english"}]}
                """));

        var models = subject.fetchModels();

        assertThat(models).extracting(TextToSpeechCatalogItem::id).containsExactly("canopylabs/orpheus-v1-english");
    }

    @Test
    @DisplayName("Groq model discovery rejects a missing data array")
    void fetchModels_whenGroqDataArrayMissing_throws() {
        CredentialResolver.init(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new GroqTextToSpeechProvider(request -> json("{}"));

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("Groq model discovery rejects entries without model ids")
    void fetchModels_whenGroqModelIdsMissing_throws() {
        CredentialResolver.init(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new GroqTextToSpeechProvider(request -> json("{\"data\":[{}]}"));

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid model IDs");
    }

    @Test
    @DisplayName("Groq voices are scoped to the selected Orpheus model language")
    void groqVoicesForModel_whenModelLanguageChanges_filtersVoices() {
        var subject = new GroqTextToSpeechProvider(request -> json("{}"));

        var englishVoices = subject.voicesForModel(
                TextToSpeechCatalogItem.of("canopylabs/orpheus-v1-english", "English"),
                subject.bundledVoices()
        );
        var arabicVoices = subject.voicesForModel(
                TextToSpeechCatalogItem.of("canopylabs/orpheus-arabic-saudi", "Arabic"),
                subject.bundledVoices()
        );
        var fallbackEnglishVoices = subject.voicesForModel(
                TextToSpeechCatalogItem.of("canopylabs/orpheus-v1-english", "English"),
                List.of(TextToSpeechCatalogItem.of("abdullah", "Abdullah"))
        );

        assertThat(englishVoices).extracting(TextToSpeechCatalogItem::id).contains("hannah").doesNotContain("abdullah");
        assertThat(arabicVoices).extracting(TextToSpeechCatalogItem::id).contains("abdullah").doesNotContain("hannah");
        assertThat(fallbackEnglishVoices).extracting(TextToSpeechCatalogItem::id).contains("hannah").doesNotContain("abdullah");
    }

    @Test
    @DisplayName("Groq synthesis maps obsolete PlayAI selections to current Orpheus defaults")
    void groqSynthesize_legacyPlayAiSelection_sendsCurrentDefaults() throws Exception {
        CredentialResolver.init(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = new GroqTextToSpeechProvider(request -> {
            captured[0] = request;
            return new TtsHttpResponse(200, Map.of("content-type", List.of("audio/wav")), new byte[]{1, 2, 3});
        });

        subject.synthesize(new TextToSpeechRequest("groq", "playai-tts", "Arista-PlayAI", "hello", "wav"));

        var requestJson = OBJECT_MAPPER.readTree(captured[0].body());
        assertThat(requestJson.path("model").asText()).isEqualTo("canopylabs/orpheus-v1-english");
        assertThat(requestJson.path("voice").asText()).isEqualTo("hannah");
    }

    @Test
    @DisplayName("HTTP request and response records tolerate malformed headers")
    void httpRecords_malformedHeaders_sanitizeValues() {
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("Authorization", "Bearer test");
        requestHeaders.put("Broken", null);
        requestHeaders.put(null, "value");
        Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
        responseHeaders.put("content-type", List.of("audio/wav"));
        responseHeaders.put("broken", null);
        responseHeaders.put(null, List.of("ignored"));
        var nullHeaderValues = new ArrayList<String>();
        nullHeaderValues.add(null);
        responseHeaders.put("empty", nullHeaderValues);

        var request = new TtsHttpRequest("GET", URI.create("https://example.test"), requestHeaders, null);
        var response = new TtsHttpResponse(200, responseHeaders, new byte[0]);

        assertThat(request.headers()).containsOnlyKeys("Authorization");
        assertThat(response.firstHeader("Content-Type")).isEqualTo("audio/wav");
        assertThat(response.headers()).containsOnlyKeys("content-type");
    }

    @Test
    @DisplayName("Provider HTTP errors include safe API error message")
    void fetchModels_httpError_includesSafeApiMessage() {
        CredentialResolver.init(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = new GroqTextToSpeechProvider(request -> new TtsHttpResponse(
                400,
                Map.of("content-type", List.of("application/json")),
                "{\"error\":{\"message\":\"The model requires terms acceptance.\"}}".getBytes(StandardCharsets.UTF_8)
        ));

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400: The model requires terms acceptance.");
    }

    @Test
    @DisplayName("Provider HTTP errors include full ElevenLabs detail message")
    void synthesize_elevenLabsHttpError_includesFullDetailMessage() throws Exception {
        CredentialResolver.init(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        String message = "This request exceeds your quota of 10000. You have 34 credits remaining. Please upgrade your plan or wait until the quota resets before trying text to speech again.";
        var subject = new ElevenLabsTextToSpeechProvider(request -> new TtsHttpResponse(
                401,
                Map.of("content-type", List.of("application/json")),
                "{\"detail\":{\"message\":%s}}".formatted(OBJECT_MAPPER.writeValueAsString(message)).getBytes(StandardCharsets.UTF_8)
        ));

        assertThatThrownBy(() -> subject.synthesize(new TextToSpeechRequest(
                ElevenLabsTextToSpeechProvider.ID,
                "eleven_flash_v2_5",
                "EXAVITQu4vr4xnSDxMaL",
                "hello",
                "mp3"
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401: %s".formatted(message));
    }

    private static TtsHttpResponse json(String body) {
        return new TtsHttpResponse(
                200,
                Map.of("content-type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }
}
