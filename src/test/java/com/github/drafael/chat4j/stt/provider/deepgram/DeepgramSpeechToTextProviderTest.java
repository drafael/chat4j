package com.github.drafael.chat4j.stt.provider.deepgram;

import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.CredentialSource;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
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

class DeepgramSpeechToTextProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Deepgram provider exposes metadata and bundled Nova models")
    void metadata_whenCreated_usesDeepgramDefaults() {
        var subject = new DeepgramSpeechToTextProvider(new CapturingTransport(success("{}")));

        assertThat(subject.id()).isEqualTo(DeepgramSpeechToTextProvider.ID);
        assertThat(subject.displayName()).isEqualTo("Deepgram");
        assertThat(subject.requiredEnvVar()).isEqualTo(DeepgramSpeechToTextProvider.ENV_VAR);
        assertThat(subject.defaultModel().id()).isEqualTo("nova-3");
        assertThat(subject.bundledModels()).extracting("id").containsExactly("nova-3", "nova-3-general", "nova-2-general");
        assertThat(subject.bundledModels()).extracting("id").doesNotContain("nova-2");
    }

    @Test
    @DisplayName("Deepgram availability requires the Deepgram API key")
    void available_whenCredentialsMissing_returnsFalse() {
        var subject = new DeepgramSpeechToTextProvider(new CapturingTransport(success("{}")));

        assertThat(subject.available(credentials(false))).isFalse();
        assertThat(subject.available(credentials(true))).isTrue();
    }

    @Test
    @DisplayName("Deepgram endpoint resolver exposes official endpoints")
    void resolve_whenCalled_returnsOfficialEndpoints() throws Exception {
        DeepgramSttEndpointResolver.Endpoint endpoint = DeepgramSttEndpointResolver.resolve();

        assertThat(endpoint.baseUri()).isEqualTo(URI.create("https://api.deepgram.com"));
        assertThat(endpoint.transcriptionUri()).isEqualTo(URI.create("https://api.deepgram.com/v1/listen"));
        assertThat(endpoint.modelsUri()).isEqualTo(URI.create("https://api.deepgram.com/v1/models"));
    }

    @Test
    @DisplayName("Deepgram model fetch without credentials is not authoritative")
    void fetchModels_whenCredentialsMissing_throwsWithoutRequest() throws Exception {
        var transport = new CapturingTransport(success("{}"));
        var subject = new DeepgramSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.fetchModels(context(credentials(false))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("requires credentials");
        assertThat(transport.request).isNull();
    }

    @Test
    @DisplayName("Deepgram model fetch parses canonical batch STT models and ignores aliases")
    void fetchModels_whenCatalogContainsMixedModels_returnsCanonicalBatchSttModels() throws Exception {
        String body = """
                {
                  "tts":[{"canonical_name":"aura-2","name":"Aura"}],
                  "stt":[
                    {"canonical_name":"nova-3-general","name":"general","architecture":"nova-3","version":"2025-01-01","batch":true,"streaming":true},
                    {"canonical_name":"flux-general-en","name":"Flux Streaming","batch":false,"streaming":true},
                    {"name":"custom-batch","batch":true},
                    {"canonical_name":"general","name":"general","batch":true},
                    {"canonical_name":"general-dQw4w9WgXcQ","name":"general","batch":true},
                    {"canonical_name":"bad\\tmodel","name":"Bad","batch":true},
                    {"canonical_name":"nova-3-general","name":"Duplicate","batch":true}
                  ]
                }
                """;
        var subject = new DeepgramSpeechToTextProvider(new CapturingTransport(success(body)));

        var models = subject.fetchModels(context(credentials(true)));

        assertThat(models).extracting("id").containsExactly("nova-3-general");
        assertThat(models.getFirst().label()).isEqualTo("Deepgram Nova 3 General");
        assertThat(models.getFirst().description()).contains("Architecture: nova-3", "Version: 2025-01-01");
    }

    @Test
    @DisplayName("Deepgram model normalization drops catalog aliases to the default model")
    void normalizeModelSelection_whenModelIsAlias_usesDefaultModel() {
        var subject = new DeepgramSpeechToTextProvider(new CapturingTransport(success("{}")));

        var normalized = subject.normalizeModelSelection(SpeechToTextCatalogItem.of("general", "general"));

        assertThat(normalized.id()).isEqualTo("nova-3");
        assertThat(normalized.label()).isEqualTo("Deepgram Nova 3");
    }

    @Test
    @DisplayName("Deepgram model fetch throws on failed remote catalog with credentials")
    void fetchModels_whenCatalogRequestFails_throws() {
        var subject = new DeepgramSpeechToTextProvider(new CapturingTransport(new SttHttpResponse(500, emptyMap(), "down".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> subject.fetchModels(context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("catalog refresh failed");
    }

    @Test
    @DisplayName("Deepgram model fetch throws on malformed or unsupported catalogs")
    void fetchModels_whenCatalogMalformedOrUnsupported_throws() {
        assertThatThrownBy(() -> new DeepgramSpeechToTextProvider(new CapturingTransport(success("not-json"))).fetchModels(context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> new DeepgramSpeechToTextProvider(new CapturingTransport(success("{\"tts\":[],\"stt\":[{\"canonical_name\":\"flux\",\"batch\":false}]}"))).fetchModels(context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("did not include");
    }

    @Test
    @DisplayName("Deepgram model fetch passes cancellation token to transport")
    void fetchModels_whenCalled_passesCancellationToken() throws Exception {
        var transport = new CapturingTransport(success("{\"stt\":[{\"canonical_name\":\"nova-3\",\"batch\":true}]}"));
        var subject = new DeepgramSpeechToTextProvider(transport);
        SpeechToTextProviderContext.CancellationToken token = () -> true;

        subject.fetchModels(context(credentials(true), token));

        assertThat(transport.cancellationToken).isSameAs(token);
        assertThat(transport.request.uri()).isEqualTo(URI.create("https://api.deepgram.com/v1/models"));
        assertThat(transport.request.headers()).containsEntry("Authorization", "Token test-key");
        assertThat(transport.request.headers()).doesNotContainKeys("xi-api-key");
    }

    @Test
    @DisplayName("Deepgram transcription sends raw WAV body with encoded model query")
    void transcribe_whenResponseSuccessful_sendsRawWavRequest() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var transport = new CapturingTransport(success("{\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":\" hello world \"}]}]}}"));
        var subject = new DeepgramSpeechToTextProvider(transport);

        var result = subject.transcribe(new SpeechToTextRequest("deepgram", "nova 3/general", audio, 1_000, Files.size(audio)), context(credentials(true)));
        String body = bodyText(transport.request);

        assertThat(result.text()).isEqualTo("hello world");
        assertThat(transport.request.method()).isEqualTo("POST");
        assertThat(transport.request.uri()).isEqualTo(URI.create("https://api.deepgram.com/v1/listen?model=nova%203%2Fgeneral"));
        assertThat(transport.request.headers()).containsEntry("Authorization", "Token test-key");
        assertThat(transport.request.headers()).containsEntry("Content-Type", "audio/wav");
        assertThat(transport.request.headers()).doesNotContainKeys("xi-api-key");
        assertThat(body).isEqualTo("RIFF-test");
        assertThat(body).doesNotContain("multipart/form-data", "name=\"file\"", "smart_format", "diarize", "language", "punctuate", "utterances", "numerals", "keywords", "search", "replace", "alternatives", "callback");
    }

    @Test
    @DisplayName("Deepgram transcription falls back to Nova 3 for blank models")
    void transcribe_whenModelBlank_usesDefaultModel() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var transport = new CapturingTransport(success("{\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":\"hello\"}]}]}}"));
        var subject = new DeepgramSpeechToTextProvider(transport);

        subject.transcribe(new SpeechToTextRequest("deepgram", " ", audio, 1_000, Files.size(audio)), context(credentials(true)));

        assertThat(transport.request.uri()).isEqualTo(URI.create("https://api.deepgram.com/v1/listen?model=nova-3"));
    }

    @Test
    @DisplayName("Deepgram transcription rejects oversized uploads before sending")
    void transcribe_whenFileTooLarge_rejectsBeforeTransport() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var transport = new CapturingTransport(success("{}"));
        var subject = new DeepgramSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("deepgram", "nova-3", audio, 1_000, DeepgramSpeechToTextProvider.MAX_UPLOAD_BYTES + 1), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("too large");
        assertThat(transport.request).isNull();
    }

    @Test
    @DisplayName("Deepgram transcription rejects invalid and blank responses")
    void transcribe_whenResponseInvalidOrBlank_rejects() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");

        assertThatThrownBy(() -> new DeepgramSpeechToTextProvider(new CapturingTransport(success("not-json")))
                .transcribe(new SpeechToTextRequest("deepgram", "nova-3", audio, 1_000, Files.size(audio)), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("invalid");
        assertThatThrownBy(() -> new DeepgramSpeechToTextProvider(new CapturingTransport(success("{\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":\"   \"}]}]}}")))
                .transcribe(new SpeechToTextRequest("deepgram", "nova-3", audio, 1_000, Files.size(audio)), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("No speech");
    }

    @Test
    @DisplayName("Deepgram transcription maps safe HTTP errors")
    void transcribe_whenHttpError_usesSafeError() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var subject = new DeepgramSpeechToTextProvider(new CapturingTransport(new SttHttpResponse(422, emptyMap(), "{\"err_msg\":\"bad test-key input\"}".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("deepgram", "nova-3", audio, 1_000, Files.size(audio)), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("Deepgram transcription failed")
                .hasMessageContaining("bad **** input")
                .hasMessageNotContaining("test-key");
    }

    @Test
    @DisplayName("Deepgram transcription maps common HTTP status codes")
    void transcribe_whenCommonHttpErrors_usesSpecificMessages() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");

        assertTranscriptionError(audio, 401, "Deepgram credentials were rejected.");
        assertTranscriptionError(audio, 403, "Deepgram credentials were rejected.");
        assertTranscriptionError(audio, 404, "Deepgram speech-to-text endpoint or model was not found.");
        assertTranscriptionError(audio, 413, "Recording is too large to upload.");
        assertTranscriptionError(audio, 429, "Deepgram rate limit reached. Try again later.");
        assertTranscriptionError(audio, 500, "Deepgram speech-to-text is temporarily unavailable.");
    }

    @Test
    @DisplayName("Deepgram transcription passes cancellation token to transport")
    void transcribe_whenCalled_passesCancellationToken() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var transport = new CapturingTransport(success("{\"results\":{\"channels\":[{\"alternatives\":[{\"transcript\":\"hello\"}]}]}}"));
        var subject = new DeepgramSpeechToTextProvider(transport);
        SpeechToTextProviderContext.CancellationToken token = () -> true;

        subject.transcribe(new SpeechToTextRequest("deepgram", "nova-3", audio, 1_000, Files.size(audio)), context(credentials(true), token));

        assertThat(transport.cancellationToken).isSameAs(token);
    }

    @Test
    @DisplayName("Deepgram transcription does not remap cancellation")
    void transcribe_whenTransportCancels_preservesCancellationMessage() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        var subject = new DeepgramSpeechToTextProvider((request, cancellationToken) -> {
            throw new SpeechToTextException("Transcription canceled.");
        });

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("deepgram", "nova-3", audio, 1_000, Files.size(audio)), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("Transcription canceled.");
    }

    private void assertTranscriptionError(Path audio, int statusCode, String message) {
        var subject = new DeepgramSpeechToTextProvider(new CapturingTransport(new SttHttpResponse(statusCode, emptyMap(), "{}".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> subject.transcribe(new SpeechToTextRequest("deepgram", "nova-3", audio, 1_000, Files.size(audio)), context(credentials(true))))
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
        DeepgramSttEndpointResolver.Endpoint endpoint = DeepgramSttEndpointResolver.resolve();
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
