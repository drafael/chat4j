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

class DeepgramTextToSpeechProviderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        CredentialResolver.init(emptyMap());
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
    @DisplayName("Deepgram catalog discovery reads canonical TTS voice model ids")
    void fetchModels_whenModelsEndpointReturnsTtsItems_usesCanonicalVoiceModelIds() throws Exception {
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

        List<TextToSpeechCatalogItem> models = subject.fetchModels();

        assertThat(models).extracting(TextToSpeechCatalogItem::id).containsExactly("aura-2-thalia-en");
        assertThat(models.getFirst().label()).isEqualTo("thalia");
        assertThat(models.getFirst().description()).isEqualTo("feminine, clear");
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

        subject.synthesize(new TextToSpeechRequest(DeepgramTextToSpeechProvider.ID, "aura-2", "aura-2", "hello", "mp3"));

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
