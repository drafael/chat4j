package com.github.drafael.chat4j.stt.provider.elevenlabs;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SttHttpRequest;
import com.github.drafael.chat4j.stt.provider.SttHttpResponse;
import com.github.drafael.chat4j.stt.provider.SttHttpTransport;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElevenLabsSpeechToTextProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ElevenLabs provider exposes metadata and bundled Scribe model")
    void metadata_whenCreated_usesElevenLabsDefaults() {
        var subject = new ElevenLabsSpeechToTextProvider(new CapturingTransport(success("{}")));

        assertThat(subject.id()).isEqualTo(ElevenLabsSpeechToTextProvider.ID);
        assertThat(subject.displayName()).isEqualTo("ElevenLabs");
        assertThat(subject.requiredEnvVar()).isEqualTo(ElevenLabsSpeechToTextProvider.ENV_VAR);
        assertThat(subject.defaultModel().id()).isEqualTo("scribe_v2");
        assertThat(subject.bundledModels()).extracting("id").containsExactly("scribe_v2");
    }

    @Test
    @DisplayName("ElevenLabs availability requires the ElevenLabs API key")
    void available_whenCredentialsMissing_returnsFalse() {
        var subject = new ElevenLabsSpeechToTextProvider(new CapturingTransport(success("{}")));

        assertThat(subject.available(credentials(false))).isFalse();
        assertThat(subject.available(credentials(true))).isTrue();
    }

    @Test
    @DisplayName("ElevenLabs model fetch without credentials is not authoritative")
    void fetchModels_whenCredentialsMissing_throwsWithoutRequest() throws Exception {
        var transport = new CapturingTransport(success("[]"));
        var subject = new ElevenLabsSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.fetchModels(context(credentials(false))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("requires credentials");
        assertThat(transport.request).isNull();
    }

    @Test
    @DisplayName("ElevenLabs model fetch parses and filters supported batch models")
    void fetchModels_whenCatalogContainsMixedModels_returnsBatchScribeModels() throws Exception {
        String body = """
                {"models":[
                  {"model_id":"eleven_flash_v2_5","name":"Flash","can_do_text_to_speech":true},
                  {"model_id":"scribe_v2_realtime","name":"Scribe Realtime"},
                  {"model_id":"scribe_v1","name":"Scribe v1"},
                  {"model_id":"custom_stt","name":"Custom STT","can_do_speech_to_text":true},
                  {"model_id":"bad\\tmodel","name":"Bad"},
                  {"model_id":"scribe_v2","name":"Scribe v2"},
                  {"model_id":"scribe_v2","name":"Duplicate"}
                ]}
                """;
        var subject = new ElevenLabsSpeechToTextProvider(new CapturingTransport(success(body)));

        var models = subject.fetchModels(context(credentials(true)));

        assertThat(models).extracting("id").containsExactly("scribe_v1", "custom_stt", "scribe_v2");
        assertThat(models.getFirst().label()).contains("deprecated");
    }

    @Test
    @DisplayName("ElevenLabs model fetch parses root array catalogs")
    void fetchModels_whenCatalogIsRootArray_parsesModels() throws Exception {
        var subject = new ElevenLabsSpeechToTextProvider(new CapturingTransport(success("[{\"model_id\":\"scribe_v2\",\"name\":\"Scribe v2\"}]")));

        var models = subject.fetchModels(context(credentials(true)));

        assertThat(models).extracting("id").containsExactly("scribe_v2");
    }

    @Test
    @DisplayName("ElevenLabs model fetch throws on failed remote catalog with credentials")
    void fetchModels_whenCatalogRequestFails_throws() {
        var subject = new ElevenLabsSpeechToTextProvider(new CapturingTransport(new SttHttpResponse(500, emptyMap(), "down".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> subject.fetchModels(context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("catalog refresh failed");
    }

    @Test
    @DisplayName("ElevenLabs model fetch throws on malformed or unsupported catalogs")
    void fetchModels_whenCatalogMalformedOrUnsupported_throws() {
        assertThatThrownBy(() -> new ElevenLabsSpeechToTextProvider(new CapturingTransport(success("not-json"))).fetchModels(context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> new ElevenLabsSpeechToTextProvider(new CapturingTransport(success("[{\"model_id\":\"eleven_flash_v2_5\",\"can_do_text_to_speech\":true}]"))).fetchModels(context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("did not include");
    }

    @Test
    @DisplayName("ElevenLabs model fetch passes cancellation token to transport")
    void fetchModels_whenCalled_passesCancellationToken() throws Exception {
        var transport = new CapturingTransport(success("[{\"model_id\":\"scribe_v2\"}]"));
        var subject = new ElevenLabsSpeechToTextProvider(transport);
        SpeechToTextProviderContext.CancellationToken token = () -> true;

        subject.fetchModels(context(credentials(true), token));

        assertThat(transport.cancellationToken).isSameAs(token);
        assertThat(transport.request.uri()).isEqualTo(URI.create("https://api.elevenlabs.io/v1/models"));
        assertThat(transport.request.headers()).containsEntry("xi-api-key", "test-key");
    }

    @Test
    @DisplayName("ElevenLabs transcription sends ElevenLabs multipart request")
    void transcribe_whenResponseSuccessful_sendsMultipartRequest() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var transport = new CapturingTransport(success("{\"text\":\" hello world \"}"));
        var subject = new ElevenLabsSpeechToTextProvider(transport);

        var result = subject.transcribe(new SpeechToTextRequest("elevenlabs", "scribe_v2", audio, 1_000, Files.size(audio)), context(credentials(true)));
        String body = bodyText(transport.request);

        assertThat(result.text()).isEqualTo("hello world");
        assertThat(transport.request.method()).isEqualTo("POST");
        assertThat(transport.request.uri()).isEqualTo(URI.create("https://api.elevenlabs.io/v1/speech-to-text"));
        assertThat(transport.request.headers()).containsEntry("xi-api-key", "test-key");
        assertThat(transport.request.headers()).doesNotContainKey("Authorization");
        assertThat(body).contains("name=\"model_id\"");
        assertThat(body).contains("scribe_v2");
        assertThat(body).contains("name=\"file\"; filename=\"recording.wav\"");
        assertThat(body).contains("Content-Type: audio/wav");
        assertThat(body).doesNotContain("name=\"model\"");
        assertThat(body).doesNotContain("response_format");
        assertThat(body).endsWith("--\r\n");
    }

    @Test
    @DisplayName("ElevenLabs multipart file names replace unsafe characters")
    void safeFileName_whenFileNameContainsHeaderUnsafeCharacters_replacesUnsafeCharacters() {
        assertThat(ElevenLabsSpeechToTextProvider.safeFileName("bad\"name\\test.WAV")).isEqualTo("bad_name_test.WAV");
    }

    @Test
    @DisplayName("ElevenLabs transcription rejects oversized uploads before sending")
    void transcribe_whenFileTooLarge_rejectsBeforeTransport() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var transport = new CapturingTransport(success("{\"text\":\"ignored\"}"));
        var subject = new ElevenLabsSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("elevenlabs", "scribe_v2", audio, 1_000, ElevenLabsSpeechToTextProvider.MAX_UPLOAD_BYTES + 1), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("too large");
        assertThat(transport.request).isNull();
    }

    @Test
    @DisplayName("ElevenLabs transcription rejects invalid and blank responses")
    void transcribe_whenResponseInvalidOrBlank_rejects() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");

        assertThatThrownBy(() -> new ElevenLabsSpeechToTextProvider(new CapturingTransport(success("not-json")))
                .transcribe(new SpeechToTextRequest("elevenlabs", "scribe_v2", audio, 1_000, Files.size(audio)), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> new ElevenLabsSpeechToTextProvider(new CapturingTransport(success("{\"text\":\"   \"}")))
                .transcribe(new SpeechToTextRequest("elevenlabs", "scribe_v2", audio, 1_000, Files.size(audio)), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("No speech");
    }

    @Test
    @DisplayName("ElevenLabs transcription maps safe HTTP errors")
    void transcribe_whenHttpError_usesSafeError() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var subject = new ElevenLabsSpeechToTextProvider(new CapturingTransport(new SttHttpResponse(422, emptyMap(), "{\"detail\":[{\"msg\":\"bad test-key input\"}]}".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("elevenlabs", "scribe_v2", audio, 1_000, Files.size(audio)), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("ElevenLabs transcription failed")
                .hasMessageContaining("bad **** input")
                .hasMessageNotContaining("test-key");
    }

    @Test
    @DisplayName("ElevenLabs transcription maps common HTTP status codes")
    void transcribe_whenCommonHttpErrors_usesSpecificMessages() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");

        assertTranscriptionError(audio, 401, "ElevenLabs credentials were rejected.");
        assertTranscriptionError(audio, 403, "ElevenLabs credentials were rejected.");
        assertTranscriptionError(audio, 404, "ElevenLabs speech-to-text endpoint or model was not found.");
        assertTranscriptionError(audio, 413, "Recording is too large to upload.");
        assertTranscriptionError(audio, 429, "ElevenLabs rate limit reached. Try again later.");
        assertTranscriptionError(audio, 500, "ElevenLabs speech-to-text is temporarily unavailable.");
    }

    @Test
    @DisplayName("ElevenLabs transcription passes cancellation token to transport")
    void transcribe_whenCalled_passesCancellationToken() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var transport = new CapturingTransport(success("{\"text\":\"hello\"}"));
        var subject = new ElevenLabsSpeechToTextProvider(transport);
        SpeechToTextProviderContext.CancellationToken token = () -> true;

        subject.transcribe(new SpeechToTextRequest("elevenlabs", "scribe_v2", audio, 1_000, Files.size(audio)), context(credentials(true), token));

        assertThat(transport.cancellationToken).isSameAs(token);
    }

    @Test
    @DisplayName("ElevenLabs transcription does not remap cancellation")
    void transcribe_whenTransportCancels_preservesCancellationMessage() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var subject = new ElevenLabsSpeechToTextProvider((request, cancellationToken) -> {
            throw new SpeechToTextException("Transcription canceled.");
        });

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("elevenlabs", "scribe_v2", audio, 1_000, Files.size(audio)), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("Transcription canceled.");
    }

    private void assertTranscriptionError(Path audio, int statusCode, String message) {
        var subject = new ElevenLabsSpeechToTextProvider(new CapturingTransport(new SttHttpResponse(statusCode, emptyMap(), "{}".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("elevenlabs", "scribe_v2", audio, 1_000, Files.size(audio)), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage(message);
    }

    private static SttHttpResponse success(String body) {
        return new SttHttpResponse(200, emptyMap(), body.getBytes(StandardCharsets.UTF_8));
    }

    private SpeechToTextProviderContext context(CredentialSource credentialSource) throws Exception {
        return context(credentialSource, () -> false);
    }

    private SpeechToTextProviderContext context(CredentialSource credentialSource, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        ElevenLabsSttEndpointResolver.Endpoint endpoint = ElevenLabsSttEndpointResolver.resolve(ElevenLabsSttEndpointResolver.DEFAULT_BASE_URL);
        return new SpeechToTextProviderContext(endpoint.baseUri(), endpoint.transcriptionUri(), credentialSource, cancellationToken, Duration.ofSeconds(10));
    }

    private CredentialSource credentials(boolean available) {
        return new CredentialSource() {
            @Override
            public boolean hasRequiredCredentials(String envVar) {
                return available;
            }

            @Override
            public String resolveRequiredApiKey(String envVar) {
                return available ? "test-key" : "";
            }
        };
    }

    private String bodyText(SttHttpRequest request) throws Exception {
        var subscriber = new BodySubscriber();
        request.bodyPublisher().subscribe(subscriber);
        return subscriber.body();
    }

    private static final class CapturingTransport implements SttHttpTransport {
        private final SttHttpResponse response;
        private SttHttpRequest request;
        private SpeechToTextProviderContext.CancellationToken cancellationToken;

        private CapturingTransport(SttHttpResponse response) {
            this.response = response;
        }

        @Override
        public SttHttpResponse send(SttHttpRequest request, SpeechToTextProviderContext.CancellationToken cancellationToken) {
            this.request = request;
            this.cancellationToken = cancellationToken;
            return response;
        }
    }

    private static final class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CountDownLatch done = new CountDownLatch(1);
        private Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            output.writeBytes(bytes);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        private String body() throws Exception {
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            if (error != null) {
                throw new IllegalStateException(error);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
