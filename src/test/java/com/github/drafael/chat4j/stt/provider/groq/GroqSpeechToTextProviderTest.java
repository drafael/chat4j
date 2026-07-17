package com.github.drafael.chat4j.stt.provider.groq;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SttHttpRequest;
import com.github.drafael.chat4j.stt.provider.SttHttpResponse;
import com.github.drafael.chat4j.stt.provider.SttHttpTransport;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroqSpeechToTextProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Groq endpoint resolver rejects unsafe base URLs")
    void resolve_whenUnsafeBaseUrl_rejects() {
        assertThatThrownBy(() -> GroqSttEndpointResolver.resolve("file:/tmp/test"))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("absolute http(s)");
    }

    @Test
    @DisplayName("Speech to Text catalog items reject blank ids")
    void catalogItem_whenIdBlank_rejects() {
        assertThatThrownBy(() -> SpeechToTextCatalogItem.of(" ", "Blank"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Groq model HTTP failures are not authoritative")
    void fetchModels_whenResponseFails_throws() throws Exception {
        var subject = new GroqSpeechToTextProvider(new CapturingTransport(
                new SttHttpResponse(500, emptyMap(), "down".getBytes(StandardCharsets.UTF_8))
        ));

        assertThatThrownBy(() -> subject.fetchModels(context()))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("Groq model discovery rejects a missing data array")
    void fetchModels_whenDataArrayMissing_throws() throws Exception {
        var subject = new GroqSpeechToTextProvider(new CapturingTransport(
                new SttHttpResponse(200, emptyMap(), "{}".getBytes(StandardCharsets.UTF_8))
        ));

        assertThatThrownBy(() -> subject.fetchModels(context()))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("Groq model discovery rejects entries without model ids")
    void fetchModels_whenModelIdsMissing_throws() throws Exception {
        var subject = new GroqSpeechToTextProvider(new CapturingTransport(
                new SttHttpResponse(200, emptyMap(), "{\"data\":[{}]}".getBytes(StandardCharsets.UTF_8))
        ));

        assertThatThrownBy(() -> subject.fetchModels(context()))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("valid model IDs");
    }

    @Test
    @DisplayName("Groq transcription sends path-backed multipart request and parses text")
    void transcribe_whenResponseSuccessful_returnsTranscript() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var transport = new CapturingTransport(new SttHttpResponse(200, emptyMap(), "{\"text\":\" hello\\nworld \"}".getBytes(StandardCharsets.UTF_8)));
        var subject = new GroqSpeechToTextProvider(transport);

        var result = subject.transcribe(
                new SpeechToTextRequest("groq", "whisper-large-v3-turbo", audio, 1_000, Files.size(audio)),
                context()
        );

        assertThat(result.text()).isEqualTo("hello\nworld");
        assertThat(transport.request.headers()).containsEntry("Accept", "application/json");
        assertThat(transport.request.headers().get("Content-Type")).startsWith("multipart/form-data; boundary=");
        assertThat(transport.request.headers().get("Authorization")).isEqualTo("Bearer test-key");
    }

    @Test
    @DisplayName("Groq transcription rejects blank transcript text")
    void transcribe_whenTextBlank_rejects() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var subject = new GroqSpeechToTextProvider(new CapturingTransport(new SttHttpResponse(200, emptyMap(), "{\"text\":\"   \"}".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("groq", "whisper-large-v3-turbo", audio, 1_000, Files.size(audio)), context()))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("No speech");
    }

    @Test
    @DisplayName("Groq transcription maps non-2xx responses to safe errors")
    void transcribe_whenUnauthorized_usesSafeError() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var subject = new GroqSpeechToTextProvider(new CapturingTransport(new SttHttpResponse(401, emptyMap(), "secret body".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("groq", "whisper-large-v3-turbo", audio, 1_000, Files.size(audio)), context()))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("credentials");
    }

    private SpeechToTextProviderContext context() throws Exception {
        GroqSttEndpointResolver.Endpoint endpoint = GroqSttEndpointResolver.resolve("https://api.groq.com/openai/v1/");
        return new SpeechToTextProviderContext(
                endpoint.baseUri(),
                endpoint.transcriptionUri(),
                credentials(),
                () -> false,
                Duration.ofSeconds(10)
        );
    }

    private CredentialSource credentials() {
        return new CredentialSource() {
            @Override
            public boolean hasRequiredCredentials(String envVar) {
                return true;
            }

            @Override
            public String resolveRequiredApiKey(String envVar) {
                return "test-key";
            }
        };
    }

    private static final class CapturingTransport implements SttHttpTransport {
        private final SttHttpResponse response;
        private SttHttpRequest request;

        private CapturingTransport(SttHttpResponse response) {
            this.response = response;
        }

        @Override
        public SttHttpResponse send(SttHttpRequest request, SpeechToTextProviderContext.CancellationToken cancellationToken) {
            this.request = request;
            return response;
        }
    }
}
