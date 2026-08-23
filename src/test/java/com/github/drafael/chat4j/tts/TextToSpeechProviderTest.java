package com.github.drafael.chat4j.tts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpClient;
import com.github.drafael.chat4j.tts.provider.TtsHttpRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.TtsHttpTransport;
import com.github.drafael.chat4j.tts.provider.deepgram.DeepgramTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.elevenlabs.ElevenLabsTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.groq.GroqTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.listenhub.ListenHubTextToSpeechProvider;
import com.github.drafael.chat4j.tts.provider.speechify.SpeechifyTextToSpeechProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.github.drafael.chat4j.tts.provider.TtsJsonTestSupport.read;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextToSpeechProviderTest {

    @TempDir
    Path tempDir;

    private CredentialResolver credentialResolver;

    @BeforeEach
    void setUpCredentials() {
        credentialResolver = resolver(emptyMap());
    }

    @Test
    @DisplayName("Default registry includes all cloud TTS providers in stable network order")
    void createDefault_whenCalled_includesNewProvidersInExpectedNetworkOrder() {
        var subject = TextToSpeechProviderRegistry.createDefault(credentialResolver, emptyMap());

        assertThat(subject.providers()).extracting(TextToSpeechProvider::id)
                .containsSubsequence(
                        DeepgramTextToSpeechProvider.ID,
                        GroqTextToSpeechProvider.ID,
                        ElevenLabsTextToSpeechProvider.ID,
                        ListenHubTextToSpeechProvider.ID,
                        SpeechifyTextToSpeechProvider.ID
                );
    }

    @Test
    @DisplayName("ElevenLabs parses TTS-capable models and voices")
    void elevenLabsCatalogs_validResponses_parsesModelsAndVoices() throws Exception {
        credentialResolver = resolver(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = elevenLabsProvider(request -> {
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
        }, credentialResolver);

        var models = subject.fetchModels();
        var voices = subject.fetchVoices();

        assertThat(models).extracting(TextToSpeechCatalogItem::id).containsExactly("eleven_flash_v2_5");
        assertThat(voices).extracting(TextToSpeechCatalogItem::id).containsExactly("voice-1");
        assertThat(voices.getFirst().description()).isEqualTo("Warm");
    }

    @Test
    @DisplayName("ElevenLabs accepts the object-wrapped model catalog root")
    void fetchModels_whenElevenLabsModelsAreWrapped_parsesModels() throws Exception {
        credentialResolver = resolver(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = elevenLabsProvider(request -> json("""
                {"models":[{"id":"wrapped-model","label":"Wrapped Model","can_do_text_to_speech":true}],"extra":true}
                """), credentialResolver);

        var models = subject.fetchModels();

        assertThat(models).containsExactly(TextToSpeechCatalogItem.of("wrapped-model", "Wrapped Model"));
    }

    @Test
    @DisplayName("An explicitly empty ElevenLabs model catalog uses the bundled fallback")
    void fetchModels_whenElevenLabsModelArrayIsEmpty_returnsBundledModels() throws Exception {
        credentialResolver = resolver(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = elevenLabsProvider(request -> json("{\"models\":[]}"), credentialResolver);

        var models = subject.fetchModels();

        assertThat(models).containsExactlyElementsOf(subject.bundledModels());
    }

    @Test
    @DisplayName("ElevenLabs model discovery rejects a missing model array")
    void fetchModels_whenElevenLabsModelArrayMissing_throws() {
        credentialResolver = resolver(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = elevenLabsProvider(request -> json("{}"), credentialResolver);

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("ElevenLabs model discovery rejects entries without model ids")
    void fetchModels_whenElevenLabsModelIdsMissing_throws() {
        credentialResolver = resolver(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = elevenLabsProvider(request -> json("[{}]"), credentialResolver);

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid model IDs");
    }

    @Test
    @DisplayName("ElevenLabs voice discovery rejects a missing voice array")
    void fetchVoices_whenElevenLabsVoiceArrayMissing_throws() {
        credentialResolver = resolver(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = elevenLabsProvider(request -> json("{}"), credentialResolver);

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("ElevenLabs voice discovery rejects entries without voice ids")
    void fetchVoices_whenElevenLabsVoiceIdsMissing_throws() {
        credentialResolver = resolver(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = elevenLabsProvider(request -> json("{\"voices\":[{}]}"), credentialResolver);

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid voices");
    }

    @Test
    @DisplayName("ElevenLabs synthesis sends typed defaults to the encoded voice endpoint")
    void elevenLabsSynthesize_whenSelectionsAreBlank_sendsExpectedRequest() throws Exception {
        credentialResolver = resolver(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = elevenLabsProvider(request -> {
            captured[0] = request;
            return new TtsHttpResponse(200, Map.of("content-type", List.of("audio/mpeg")), new byte[]{1});
        }, credentialResolver);

        subject.synthesize(new TextToSpeechRequest(ElevenLabsTextToSpeechProvider.ID, " ", " ", "hello", "mp3"));

        ElevenLabsSynthesisRequest body = read(captured[0].body(), ElevenLabsSynthesisRequest.class);
        assertThat(body).isEqualTo(new ElevenLabsSynthesisRequest("hello", "eleven_flash_v2_5"));
        assertThat(captured[0].uri().toString())
                .isEqualTo("https://api.elevenlabs.io/v1/text-to-speech/EXAVITQu4vr4xnSDxMaL?output_format=mp3_44100_128");
        assertThat(captured[0].headers()).containsEntry("xi-api-key", "test-key");
    }

    @Test
    @DisplayName("Groq model discovery keeps current TTS models without showing obsolete PlayAI")
    void fetchModels_whenGroqTtsModelPresent_keepsCurrentTtsModel() throws Exception {
        credentialResolver = resolver(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = groqProvider(request -> json("""
                {"data":[{"id":"llama-3.3-70b"},{"id":"playai-tts"},{"id":"canopylabs/orpheus-v1-english"}]}
                """), credentialResolver);

        var models = subject.fetchModels();

        assertThat(models).extracting(TextToSpeechCatalogItem::id).containsExactly("canopylabs/orpheus-v1-english");
    }

    @Test
    @DisplayName("Groq model discovery rejects a missing data array")
    void fetchModels_whenGroqDataArrayMissing_throws() {
        credentialResolver = resolver(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = groqProvider(request -> json("{}"), credentialResolver);

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("Groq model discovery rejects entries without model ids")
    void fetchModels_whenGroqModelIdsMissing_throws() {
        credentialResolver = resolver(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = groqProvider(request -> json("{\"data\":[{}]}"), credentialResolver);

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid model IDs");
    }

    @Test
    @DisplayName("Groq voices are scoped to the selected Orpheus model language")
    void groqVoicesForModel_whenModelLanguageChanges_filtersVoices() {
        var subject = groqProvider(request -> json("{}"), credentialResolver);

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
        credentialResolver = resolver(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = groqProvider(request -> {
            captured[0] = request;
            return new TtsHttpResponse(200, Map.of("content-type", List.of("audio/wav")), new byte[]{1, 2, 3});
        }, credentialResolver);

        subject.synthesize(new TextToSpeechRequest("groq", "playai-tts", "Arista-PlayAI", "hello", "wav"));

        GroqSynthesisRequest requestBody = read(captured[0].body(), GroqSynthesisRequest.class);
        assertThat(requestBody.model()).isEqualTo("canopylabs/orpheus-v1-english");
        assertThat(requestBody.voice()).isEqualTo("hannah");
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
        credentialResolver = resolver(Map.of(GroqTextToSpeechProvider.ENV_VAR, "test-key"));
        var subject = groqProvider(request -> new TtsHttpResponse(
                400,
                Map.of("content-type", List.of("application/json")),
                "{\"error\":{\"message\":\"The model requires terms acceptance.\"}}".getBytes(StandardCharsets.UTF_8)
        ), credentialResolver);

        assertThatThrownBy(subject::fetchModels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 400: The model requires terms acceptance.");
    }

    @Test
    @DisplayName("Provider HTTP errors include full ElevenLabs detail message")
    void synthesize_elevenLabsHttpError_includesFullDetailMessage() throws Exception {
        credentialResolver = resolver(Map.of(ElevenLabsTextToSpeechProvider.ENV_VAR, "test-key"));
        String message = "This request exceeds your quota of 10000. You have 34 credits remaining. Please upgrade your plan or wait until the quota resets before trying text to speech again.";
        var subject = elevenLabsProvider(request -> new TtsHttpResponse(
                401,
                Map.of("content-type", List.of("application/json")),
                "{\"detail\":{\"message\":\"%s\"}}".formatted(message).getBytes(StandardCharsets.UTF_8)
        ), credentialResolver);

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


    private ElevenLabsTextToSpeechProvider elevenLabsProvider(
            TtsHttpTransport transport,
            CredentialResolver resolver
    ) {
        return new ElevenLabsTextToSpeechProvider(new TtsHttpClient(transport), resolver);
    }

    private GroqTextToSpeechProvider groqProvider(TtsHttpTransport transport, CredentialResolver resolver) {
        return new GroqTextToSpeechProvider(new TtsHttpClient(transport), resolver);
    }

    private CredentialResolver resolver(Map<String, String> shellEnvironment) {
        return new CredentialResolver(
                new ApiTokenVault(StoragePaths.ofConfigHome(tempDir.resolve("credentials"))),
                emptyMap(),
                shellEnvironment
        );
    }

    private static TtsHttpResponse json(String body) {
        return new TtsHttpResponse(
                200,
                Map.of("content-type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record GroqSynthesisRequest(
            String model,
            String voice,
            String input,
            @JsonProperty("response_format") String responseFormat
    ) {
    }

    private record ElevenLabsSynthesisRequest(
            String text,
            @JsonProperty("model_id") String modelId
    ) {
    }
}
