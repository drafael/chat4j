package com.github.drafael.chat4j.tts.provider.speechify;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CredentialMutationListener;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.CredentialTestSupport;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpClient;
import com.github.drafael.chat4j.tts.provider.TtsHttpRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.TtsHttpTransport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.github.drafael.chat4j.tts.provider.TtsJsonTestSupport.read;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeechifyTextToSpeechProviderTest {

    private static final TextToSpeechRequest REQUEST = new TextToSpeechRequest(
            SpeechifyTextToSpeechProvider.ID,
            "simba-3.2",
            "geffen_32",
            "Hello from Chat4J",
            "wav"
    );

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Speechify exposes credential and bundled model and voice defaults")
    void metadata_whenCreated_exposesSafeDefaults() {
        var subject = provider(request -> json("{}"), emptyMap());

        assertThat(subject.id()).isEqualTo("speechify");
        assertThat(subject.displayName()).isEqualTo("Speechify");
        assertThat(subject.requiredEnvVar()).isEqualTo("SPEECHIFY_API_KEY");
        assertThat(subject.defaultModel().id()).isEqualTo("simba-3.0");
        assertThat(subject.defaultModel().label()).isEqualTo("Simba 3.0");
        assertThat(subject.defaultVoice()).isEqualTo(TextToSpeechCatalogItem.of("geffen_32", "Geffen"));
        assertThat(subject.bundledModels()).extracting(TextToSpeechCatalogItem::id)
                .containsExactly("simba-3.0", "simba-3.2");
        assertThat(subject.bundledVoices()).containsExactly(subject.defaultVoice());
        assertThat(subject.maxInputCharacters()).isEqualTo(2_000);
        assertThat(subject.defaultResponseFormat()).isEqualTo("mp3");
        assertThat(subject.available()).isFalse();
    }

    @Test
    @DisplayName("Speechify uses CredentialResolver process and shell environment support")
    void credentials_whenSpeechifyKeyExists_makesProviderAvailable() {
        var subject = provider(request -> json("{}"), Map.of(SpeechifyTextToSpeechProvider.ENV_VAR, "shell-key"));

        assertThat(subject.available()).isTrue();
        assertThat(subject.apiKey()).isEqualTo("shell-key");
    }

    @Test
    @DisplayName("Speechify accepts a key saved through the encrypted token vault plumbing")
    void credentials_whenSpeechifyKeyIsSaved_resolvesVaultToken() {
        try (var credentials = CredentialTestSupport.create(
                StoragePaths.ofConfigHome(tempDir.resolve("saved-credentials")))) {
            credentials.mutationService().saveTokenOverride(
                    SpeechifyTextToSpeechProvider.ENV_VAR,
                    "saved-key".toCharArray(),
                    CredentialMutationListener.NO_OP
            );
            var subject = new SpeechifyTextToSpeechProvider(
                    new TtsHttpClient(request -> json("{}")),
                    credentials.resolver()
            );

            assertThat(subject.available()).isTrue();
            assertThat(subject.apiKey()).isEqualTo("saved-key");
            assertThat(subject.availableMessage()).contains("entered/stored API token");
        }
    }

    @Test
    @DisplayName("Speechify model discovery follows the official audio models contract")
    void fetchModels_whenCatalogIsValid_mapsModelsAndAuthorization() throws Exception {
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = provider(request -> {
            captured[0] = request;
            return json("""
                    {"models":[
                      {"id":"simba-3.0","name":"Simba 3.0","description":"Multilingual"},
                      {"id":"simba-3.2","name":"Simba 3.2","description":"English"}
                    ],"dialogue_models":[{"id":"simba-dialogue-1.0"}]}
                    """);
        }, Map.of(SpeechifyTextToSpeechProvider.ENV_VAR, "catalog-key"));

        var models = subject.fetchModels();

        assertThat(captured[0].method()).isEqualTo("GET");
        assertThat(captured[0].uri().toString()).isEqualTo("https://api.speechify.ai/v1/audio/models");
        assertThat(captured[0].headers()).containsEntry("Authorization", "Bearer catalog-key");
        assertThat(models).extracting(TextToSpeechCatalogItem::id)
                .containsExactly("simba-3.0", "simba-3.2");
        assertThat(models.getFirst().description()).isEqualTo("Multilingual");
    }

    @Test
    @DisplayName("Speechify voice discovery maps ids, display names, and concise metadata")
    void fetchVoices_whenCatalogIsValid_mapsVoices() throws Exception {
        var subject = provider(request -> json("""
                {"next_cursor":null,"has_more":false,"voices":[
                  {"id":"geffen_32","display_name":"Geffen","locale":"en-US","gender":"male","type":"shared"},
                  {"id":"clone-1","display_name":"My Voice","locale":"fr-FR","gender":"not_specified","type":"personal"},
                  {"display_name":"Missing id"}
                ]}
                """), Map.of(SpeechifyTextToSpeechProvider.ENV_VAR, "catalog-key"));

        var voices = subject.fetchVoices();

        assertThat(voices).extracting(TextToSpeechCatalogItem::id)
                .containsExactly("geffen_32", "clone-1");
        assertThat(voices.getFirst().label()).isEqualTo("Geffen");
        assertThat(voices.getFirst().description()).isEqualTo("en-US · male · shared");
        assertThat(voices.get(1).description()).isEqualTo("fr-FR · personal");
    }

    @Test
    @DisplayName("Empty authoritative Speechify catalogs use bundled fallbacks")
    void fetchCatalogs_whenArraysAreEmpty_returnsBundledDefaults() throws Exception {
        var subject = provider(request -> request.uri().getPath().endsWith("models")
                ? json("{\"models\":[]}")
                : json("{\"voices\":[]}"), Map.of(SpeechifyTextToSpeechProvider.ENV_VAR, "key"));

        assertThat(subject.fetchModels()).containsExactlyElementsOf(subject.bundledModels());
        assertThat(subject.fetchVoices()).containsExactlyElementsOf(subject.bundledVoices());
    }

    @ParameterizedTest
    @MethodSource("invalidCatalogs")
    @DisplayName("Speechify rejects malformed or unusable catalog responses")
    void fetchCatalogs_whenResponseIsInvalid_throws(boolean models, String body) {
        var subject = provider(request -> json(body), Map.of(SpeechifyTextToSpeechProvider.ENV_VAR, "key"));

        assertThatThrownBy(models ? subject::fetchModels : subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Speechify");
    }

    @Test
    @DisplayName("Speechify synthesis sends the documented request and decodes JSON Base64 MP3")
    void synthesize_whenCalled_sendsOfficialRequestAndDecodesAudio() throws Exception {
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        byte[] mp3 = {1, 2, 3, 4};
        var subject = provider(request -> {
            captured[0] = request;
            return json("""
                    {"audio_data":"%s","audio_format":"mp3","billable_characters_count":17,
                     "speech_marks":{"chunks":[],"end":17,"end_time":1,"start":0,"start_time":0,"type":"text"}}
                    """.formatted(Base64.getEncoder().encodeToString(mp3)));
        }, emptyMap());

        var audio = subject.synthesize(REQUEST, "request-key");

        assertThat(captured[0].method()).isEqualTo("POST");
        assertThat(captured[0].uri().toString()).isEqualTo("https://api.speechify.ai/v1/audio/speech");
        assertThat(captured[0].headers())
                .containsEntry("Authorization", "Bearer request-key")
                .containsEntry("Content-Type", "application/json")
                .containsEntry("Accept", "application/json");
        SpeechifyApi.SynthesisRequest body = read(captured[0].body(), SpeechifyApi.SynthesisRequest.class);
        assertThat(body).isEqualTo(new SpeechifyApi.SynthesisRequest(
                "Hello from Chat4J",
                "geffen_32",
                "mp3",
                "simba-3.2"
        ));
        assertThat(audio.bytes()).containsExactly(mp3);
        assertThat(audio.contentType()).isEqualTo("audio/mpeg");
        assertThat(audio.format()).isEqualTo("mp3");
    }

    @Test
    @DisplayName("Speechify synthesis resolves current credentials and applies blank defaults")
    void synthesize_whenCredentialAndSelectionsAreImplicit_resolvesCredentialAndDefaults() throws Exception {
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = provider(request -> {
            captured[0] = request;
            return json("""
                    {"audio_data":"AQ==","audio_format":"mp3"}
                    """);
        }, Map.of(SpeechifyTextToSpeechProvider.ENV_VAR, "resolved-key"));
        var request = new TextToSpeechRequest("speechify", " ", " ", "hello", "wav");

        subject.synthesize(request);

        SpeechifyApi.SynthesisRequest body = read(captured[0].body(), SpeechifyApi.SynthesisRequest.class);
        assertThat(captured[0].headers()).containsEntry("Authorization", "Bearer resolved-key");
        assertThat(body.model()).isEqualTo("simba-3.0");
        assertThat(body.voiceId()).isEqualTo("geffen_32");
    }

    @ParameterizedTest
    @MethodSource("invalidSpeechResponses")
    @DisplayName("Speechify synthesis rejects invalid JSON, format, Base64, and empty audio")
    void synthesize_whenResponseIsInvalid_throwsSafeError(String body) {
        var subject = provider(request -> json(body), emptyMap());

        assertThatThrownBy(() -> subject.synthesize(REQUEST, "request-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Speechify TTS")
                .hasMessageNotContaining("request-key");
    }

    @Test
    @DisplayName("Speechify HTTP errors expose only the provider's structured safe message")
    void synthesize_whenHttpFails_mapsStructuredErrorWithoutCredential() {
        var subject = provider(request -> new TtsHttpResponse(
                429,
                Map.of("content-type", List.of("application/json")),
                """
                        {"error":{"message":"Rate limit reached"}}
                        """.getBytes(StandardCharsets.UTF_8)
        ), emptyMap());

        assertThatThrownBy(() -> subject.synthesize(REQUEST, "secret-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Speechify TTS request failed with HTTP 429: Rate limit reached")
                .hasMessageNotContaining("secret-key");
    }

    private SpeechifyTextToSpeechProvider provider(
            TtsHttpTransport transport,
            Map<String, String> shellEnvironment
    ) {
        return new SpeechifyTextToSpeechProvider(
                new TtsHttpClient(transport),
                new CredentialResolver(
                        new ApiTokenVault(StoragePaths.ofConfigHome(tempDir.resolve("credentials"))),
                        emptyMap(),
                        shellEnvironment
                )
        );
    }

    private static Stream<Arguments> invalidCatalogs() {
        return Stream.of(
                Arguments.of(true, "not-json"),
                Arguments.of(true, """
                        {}
                        """),
                Arguments.of(true, """
                        {"models":[{}]}
                        """),
                Arguments.of(false, """
                        {}
                        """),
                Arguments.of(false, """
                        {"voices":[{}]}
                        """)
        );
    }

    private static Stream<String> invalidSpeechResponses() {
        return Stream.of(
                "not-json",
                """
                        {}
                        """,
                """
                        {"audio_data":"AQ==","audio_format":"wav"}
                        """,
                """
                        {"audio_data":"not base64!","audio_format":"mp3"}
                        """,
                """
                        {"audio_data":"","audio_format":"mp3"}
                        """
        );
    }

    private static TtsHttpResponse json(String body) {
        return new TtsHttpResponse(
                200,
                Map.of("content-type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }
}
