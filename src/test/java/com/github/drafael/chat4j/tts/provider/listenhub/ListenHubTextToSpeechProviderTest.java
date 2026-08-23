package com.github.drafael.chat4j.tts.provider.listenhub;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.tts.provider.TextToSpeechCatalogItem;
import com.github.drafael.chat4j.tts.provider.TextToSpeechRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpClient;
import com.github.drafael.chat4j.tts.provider.TtsHttpRequest;
import com.github.drafael.chat4j.tts.provider.TtsHttpResponse;
import com.github.drafael.chat4j.tts.provider.TtsHttpTransport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.github.drafael.chat4j.tts.provider.TtsJsonTestSupport.read;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListenHubTextToSpeechProviderTest {

    private static final TextToSpeechRequest REQUEST = new TextToSpeechRequest(
            ListenHubTextToSpeechProvider.ID,
            "listenhub-tts",
            "travel-girl-english",
            "hello",
            "wav"
    );

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ListenHub exposes its fixed model, Mia fallback, and operational chunk size")
    void metadata_whenCreated_exposesFixedSelectionsAndChunkSize() {
        var subject = provider(request -> json("{}"), emptyMap());
        var staleModel = TextToSpeechCatalogItem.of("stale-model", "Stale model");

        assertThat(subject.id()).isEqualTo("listenhub");
        assertThat(subject.displayName()).isEqualTo("ListenHub");
        assertThat(subject.requiredEnvVar()).isEqualTo("LISTENHUB_API_KEY");
        assertThat(subject.defaultModel()).isEqualTo(TextToSpeechCatalogItem.of("listenhub-tts", "ListenHub TTS"));
        assertThat(subject.defaultVoice()).isEqualTo(TextToSpeechCatalogItem.of("travel-girl-english", "Mia"));
        assertThat(subject.bundledModels()).containsExactly(subject.defaultModel());
        assertThat(subject.bundledVoices()).containsExactly(subject.defaultVoice());
        assertThat(subject.fetchModels()).containsExactly(subject.defaultModel());
        assertThat(subject.normalizeModelSelection(null)).isEqualTo(subject.defaultModel());
        assertThat(subject.normalizeModelSelection(staleModel)).isEqualTo(subject.defaultModel());
        assertThat(subject.maxInputCharacters()).isEqualTo(1_000);
        assertThat(subject.defaultResponseFormat()).isEqualTo("mp3");
    }

    @Test
    @DisplayName("ListenHub discovers public and private voices from the unfiltered speaker catalog")
    void fetchVoices_whenCatalogContainsPublicAndPrivateSpeakers_mapsUsableEntries() throws Exception {
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = provider(request -> {
            captured[0] = request;
            return json("""
                    {
                      "code": 0,
                      "message": "Success",
                      "data": {
                        "items": [
                          {
                            "speakerId": "travel-girl-english",
                            "name": "Mia",
                            "profile": {"description": "Travel narrator"}
                          },
                          {
                            "speakerId": "private-clone-1",
                            "name": "Private Clone",
                            "profile": {"description": " Private   cloned voice "}
                          },
                          {"speakerId": "missing-name"}
                        ]
                      }
                    }
                    """);
        }, Map.of(ListenHubTextToSpeechProvider.ENV_VAR, "catalog-key"));

        List<TextToSpeechCatalogItem> voices = subject.fetchVoices();

        assertThat(captured[0].method()).isEqualTo("GET");
        assertThat(captured[0].uri().toString()).isEqualTo("https://api.marswave.ai/openapi/v1/speakers/list");
        assertThat(captured[0].uri().getQuery()).isNull();
        assertThat(captured[0].headers()).containsEntry("Authorization", "Bearer catalog-key");
        assertThat(voices).extracting(TextToSpeechCatalogItem::id)
                .containsExactly("travel-girl-english", "private-clone-1");
        assertThat(voices).extracting(TextToSpeechCatalogItem::label)
                .containsExactly("Mia", "Private Clone");
        assertThat(voices.get(1).description()).isEqualTo("Private cloned voice");
    }

    @Test
    @DisplayName("An explicitly successful empty ListenHub catalog falls back to Mia")
    void fetchVoices_whenCatalogIsEmpty_returnsBundledMia() throws Exception {
        var subject = provider(request -> json("""
                {"code":0,"message":"Success","data":{"items":[]}}
                """), Map.of(ListenHubTextToSpeechProvider.ENV_VAR, "catalog-key"));

        List<TextToSpeechCatalogItem> voices = subject.fetchVoices();

        assertThat(voices).containsExactly(subject.defaultVoice());
    }

    @ParameterizedTest
    @MethodSource("invalidCatalogResponses")
    @DisplayName("ListenHub rejects structurally invalid voice catalog envelopes")
    void fetchVoices_whenSuccessEnvelopeIsInvalid_throws(String responseBody) {
        var subject = provider(
                request -> json(responseBody),
                Map.of(ListenHubTextToSpeechProvider.ENV_VAR, "catalog-key")
        );

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("voice catalog response");
    }

    @Test
    @DisplayName("A nonempty ListenHub catalog containing no usable voices is rejected")
    void fetchVoices_whenCatalogContainsNoUsableVoices_throws() {
        var subject = provider(request -> json("""
                {"code":0,"data":{"items":[{}, {"speakerId":"id-only"}]}}
                """), Map.of(ListenHubTextToSpeechProvider.ENV_VAR, "catalog-key"));

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not contain valid voices");
    }

    @Test
    @DisplayName("ListenHub catalog errors retain their application code and normalized message")
    void fetchVoices_whenApplicationCodeIsNonzero_throwsCodeAndMessage() {
        var subject = provider(request -> json("""
                {"code":29998,"message":" Rate   limit exceeded "}
                """), Map.of(ListenHubTextToSpeechProvider.ENV_VAR, "catalog-key"));

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ListenHub request failed (code 29998): Rate limit exceeded");
    }

    @Test
    @DisplayName("ListenHub application errors remain identifiable when no message is provided")
    void fetchVoices_whenApplicationMessageIsMissing_throwsCodeOnly() {
        var subject = provider(request -> json("""
                {"code":20001,"data":null}
                """), Map.of(ListenHubTextToSpeechProvider.ENV_VAR, "catalog-key"));

        assertThatThrownBy(subject::fetchVoices)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ListenHub request failed (code 20001)");
    }

    @Test
    @DisplayName("ListenHub synthesis sends the selected voice and fixed MP3 request shape")
    void synthesize_whenCalled_sendsExpectedRequestAndPreservesMp3Bytes() throws Exception {
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        byte[] mp3 = {1, 2, 3, 4};
        var subject = provider(request -> {
            captured[0] = request;
            return new TtsHttpResponse(
                    200,
                    Map.of("Content-Type", List.of(" AuDiO/MpEg ; codecs=mp3 ")),
                    mp3
            );
        }, Map.of(ListenHubTextToSpeechProvider.ENV_VAR, "resolved-key"));
        var request = new TextToSpeechRequest(
                ListenHubTextToSpeechProvider.ID,
                "ignored-model",
                "private-clone-1",
                "Text to read",
                "wav"
        );

        var audio = subject.synthesize(request, "request-key");

        assertThat(captured[0].method()).isEqualTo("POST");
        assertThat(captured[0].uri().toString()).isEqualTo("https://api.marswave.ai/openapi/v1/tts");
        assertThat(captured[0].headers())
                .containsEntry("Authorization", "Bearer request-key")
                .containsEntry("Content-Type", "application/json")
                .containsEntry("Accept", "audio/mpeg");
        ListenHubApi.SynthesisRequest requestBody = read(captured[0].body(), ListenHubApi.SynthesisRequest.class);
        assertThat(requestBody).isEqualTo(new ListenHubApi.SynthesisRequest("Text to read", "private-clone-1", "mp3"));
        assertThat(new String(captured[0].body(), StandardCharsets.UTF_8))
                .doesNotContain("model")
                .doesNotContain("speed");
        assertThat(audio.bytes()).containsExactly(mp3);
        assertThat(audio.contentType()).isEqualTo(" AuDiO/MpEg ; codecs=mp3 ");
        assertThat(audio.format()).isEqualTo("mp3");
    }

    @Test
    @DisplayName("The one-argument ListenHub synthesis overload resolves the current credential")
    void synthesize_whenApiKeyIsNotProvided_resolvesCurrentCredential() throws Exception {
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = provider(request -> {
            captured[0] = request;
            return mp3(new byte[]{1});
        }, Map.of(ListenHubTextToSpeechProvider.ENV_VAR, "resolved-key"));

        subject.synthesize(REQUEST);

        assertThat(captured[0].headers()).containsEntry("Authorization", "Bearer resolved-key");
    }

    @Test
    @DisplayName("ListenHub synthesis uses Mia when the selected voice is blank")
    void synthesize_whenVoiceIsBlank_usesMia() throws Exception {
        TtsHttpRequest[] captured = new TtsHttpRequest[1];
        var subject = provider(request -> {
            captured[0] = request;
            return mp3(new byte[]{1});
        }, emptyMap());
        var request = new TextToSpeechRequest(
                ListenHubTextToSpeechProvider.ID,
                "listenhub-tts",
                " ",
                "hello",
                "mp3"
        );

        subject.synthesize(request, "request-key");

        ListenHubApi.SynthesisRequest requestBody = read(captured[0].body(), ListenHubApi.SynthesisRequest.class);
        assertThat(requestBody.voice()).isEqualTo("travel-girl-english");
    }

    @Test
    @DisplayName("ListenHub synthesis errors retain their application code and message")
    void synthesize_whenJsonApplicationErrorIsReturned_throwsCodeAndMessage() {
        var subject = provider(request -> new TtsHttpResponse(
                200,
                emptyMap(),
                "{\"code\":29998,\"message\":\"Rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8)
        ), emptyMap());

        assertThatThrownBy(() -> subject.synthesize(REQUEST, "request-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ListenHub request failed (code 29998): Rate limit exceeded");
    }

    @Test
    @DisplayName("ListenHub rejects an empty synthesis body even when labeled as MP3")
    void synthesize_whenAudioBodyIsEmpty_throws() {
        var subject = provider(request -> mp3(new byte[0]), emptyMap());

        assertThatThrownBy(() -> subject.synthesize(REQUEST, "request-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty audio response");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "audio/ogg", "application/json"})
    @DisplayName("ListenHub rejects missing, wrong audio, and malformed JSON response types")
    void synthesize_whenResponseTypeIsUnexpected_throws(String contentType) {
        Map<String, List<String>> headers = contentType.isEmpty()
                ? emptyMap()
                : Map.of("content-type", List.of(contentType));
        String responseBody = "application/json".equals(contentType) ? "{\"code\":0}" : "not-json";
        var subject = provider(request -> new TtsHttpResponse(
                200,
                headers,
                responseBody.getBytes(StandardCharsets.UTF_8)
        ), emptyMap());

        assertThatThrownBy(() -> subject.synthesize(REQUEST, "request-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unexpected response");
    }

    private ListenHubTextToSpeechProvider provider(
            TtsHttpTransport transport,
            Map<String, String> shellEnvironment
    ) {
        var resolver = new CredentialResolver(
                new ApiTokenVault(StoragePaths.ofConfigHome(tempDir.resolve("credentials"))),
                emptyMap(),
                shellEnvironment
        );
        return new ListenHubTextToSpeechProvider(new TtsHttpClient(transport), resolver);
    }

    private static Stream<String> invalidCatalogResponses() {
        return Stream.of(
                "null",
                "{}",
                "{\"code\":\"0\",\"data\":{\"items\":[]}}",
                "{\"code\":2147483648,\"data\":{\"items\":[]}}",
                "{\"code\":0}",
                "{\"code\":0,\"data\":{\"items\":{}}}"
        );
    }

    private static TtsHttpResponse json(String body) {
        return new TtsHttpResponse(
                200,
                Map.of("content-type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static TtsHttpResponse mp3(byte[] body) {
        return new TtsHttpResponse(200, Map.of("content-type", List.of("audio/mpeg")), body);
    }
}
