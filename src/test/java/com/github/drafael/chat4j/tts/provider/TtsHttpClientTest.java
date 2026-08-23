package com.github.drafael.chat4j.tts.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtsHttpClientTest {

    @Test
    @DisplayName("GET requests preserve their URI and headers without a body")
    void get_whenCalled_buildsExpectedRequest() throws Exception {
        var captured = new AtomicReference<TtsHttpRequest>();
        var subject = new TtsHttpClient(request -> {
            captured.set(request);
            return json("{}");
        });
        URI uri = URI.create("https://example.test/models");

        subject.get(uri, Map.of("Authorization", "Bearer secret"));

        assertThat(captured.get().method()).isEqualTo("GET");
        assertThat(captured.get().uri()).isEqualTo(uri);
        assertThat(captured.get().headers()).containsEntry("Authorization", "Bearer secret");
        assertThat(captured.get().body()).isEmpty();
    }

    @Test
    @DisplayName("JSON POST requests serialize record property names and preserve transport metadata")
    void postJson_whenRecordProvided_serializesExpectedRequest() throws Exception {
        var captured = new AtomicReference<TtsHttpRequest>();
        var subject = new TtsHttpClient(request -> {
            captured.set(request);
            return json("{}");
        });
        URI uri = URI.create("https://example.test/speech");

        subject.postJson(uri, Map.of("Content-Type", "application/json"), new TestRequest("hello", "voice-1"));

        assertThat(captured.get().method()).isEqualTo("POST");
        assertThat(captured.get().uri()).isEqualTo(uri);
        assertThat(captured.get().headers()).containsEntry("Content-Type", "application/json");
        assertThat(new String(captured.get().body(), StandardCharsets.UTF_8))
                .isEqualTo("{\"input\":\"hello\",\"voice_id\":\"voice-1\"}");
    }

    @Test
    @DisplayName("Typed decoding ignores additive response properties declared by the wire record")
    void readJson_whenResponseHasUnknownProperties_returnsTypedResponse() {
        var subject = new TtsHttpClient(request -> json("{}"));

        TestResponse response = subject.readJson(
                "{\"value\":\"ok\",\"new_field\":42}".getBytes(StandardCharsets.UTF_8),
                TestResponse.class,
                "Response was invalid."
        );

        assertThat(response.value()).isEqualTo("ok");
    }

    @Test
    @DisplayName("Typed decoding replaces parser diagnostics with the endpoint message")
    void readJson_whenBodyIsMalformed_throwsStableMessage() {
        var subject = new TtsHttpClient(request -> json("{}"));

        assertThatThrownBy(() -> subject.readJson(
                "not-json secret-body".getBytes(StandardCharsets.UTF_8),
                TestResponse.class,
                "Provider response was invalid."
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider response was invalid.")
                .hasMessageNotContaining("secret-body");
    }

    @Test
    @DisplayName("Optional typed decoding is empty for binary and malformed bodies")
    void tryReadJson_whenBodyIsNotJson_returnsEmpty() {
        var subject = new TtsHttpClient(request -> json("{}"));

        assertThat(subject.tryReadJson(new byte[]{1, 2, 3}, TestResponse.class)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("safeErrorDetails")
    @DisplayName("Safe error extraction retains supported message shapes and precedence")
    void safeErrorDetail_whenStructuredErrorReturned_extractsExpectedMessage(String body, String expected) {
        var subject = new TtsHttpClient(request -> json("{}"));

        String detail = subject.safeErrorDetail(new TtsHttpResponse(400, emptyMap(), body.getBytes(StandardCharsets.UTF_8)));

        assertThat(detail).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json private-body", "<html>private body</html>"})
    @DisplayName("Safe error extraction suppresses blank, malformed, and HTML bodies")
    void safeErrorDetail_whenBodyIsUnsafe_returnsBlank(String body) {
        var subject = new TtsHttpClient(request -> json("{}"));

        String detail = subject.safeErrorDetail(new TtsHttpResponse(500, emptyMap(), body.getBytes(StandardCharsets.UTF_8)));

        assertThat(detail).isBlank();
    }

    private static Stream<Arguments> safeErrorDetails() {
        return Stream.of(
                Arguments.of(
                        "{\"error\":{\"message\":\" Error   message \"},\"detail\":{\"message\":\"detail\"},\"message\":\"top\"}",
                        "Error message"
                ),
                Arguments.of("{\"detail\":{\"message\":\"Detail message\"},\"message\":\"top\"}", "Detail message"),
                Arguments.of("{\"message\":\"Top message\",\"detail\":\"string detail\"}", "Top message"),
                Arguments.of("{\"detail\":\"String detail\"}", "String detail")
        );
    }

    private static TtsHttpResponse json(String body) {
        return new TtsHttpResponse(
                200,
                Map.of("content-type", List.of("application/json")),
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record TestRequest(String input, @JsonProperty("voice_id") String voiceId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TestResponse(String value) {
    }
}
