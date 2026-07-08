package com.github.drafael.chat4j.stt.provider.assemblyai;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssemblyAiSpeechToTextProviderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("AssemblyAI provider exposes metadata and bundled models")
    void metadata_whenCreated_usesAssemblyAiDefaults() {
        var subject = new AssemblyAiSpeechToTextProvider(new SequentialTransport());

        assertThat(subject.id()).isEqualTo(AssemblyAiSpeechToTextProvider.ID);
        assertThat(subject.displayName()).isEqualTo("AssemblyAI");
        assertThat(subject.requiredEnvVar()).isEqualTo(AssemblyAiSpeechToTextProvider.ENV_VAR);
        assertThat(subject.defaultModel().id()).isEqualTo("assemblyai-auto");
        assertThat(subject.bundledModels()).extracting("id").containsExactly("assemblyai-auto", "universal-3-5-pro", "universal-2");
    }

    @Test
    @DisplayName("AssemblyAI availability requires the AssemblyAI API key")
    void available_whenCredentialsMissing_returnsFalse() {
        var subject = new AssemblyAiSpeechToTextProvider(new SequentialTransport());

        assertThat(subject.available(credentials(false))).isFalse();
        assertThat(subject.available(credentials(true))).isTrue();
    }

    @Test
    @DisplayName("AssemblyAI model fetch returns bundled models without transport calls")
    void fetchModels_whenCalled_returnsBundledModelsAndDoesNotCallTransport() throws Exception {
        var transport = new SequentialTransport(success("{}"));
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        var models = subject.fetchModels(context(credentials(true)));

        assertThat(models).extracting("id").containsExactly("assemblyai-auto", "universal-3-5-pro", "universal-2");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    @DisplayName("AssemblyAI model normalization rejects unknown or unsafe persisted IDs")
    void normalizeModelSelection_whenUnknownOrUnsafe_usesDefaultAutoModel() {
        var subject = new AssemblyAiSpeechToTextProvider(new SequentialTransport());

        assertThat(subject.normalizeModelSelection(null).id()).isEqualTo("assemblyai-auto");
        assertThat(subject.normalizeModelSelection(SpeechToTextCatalogItem.of("unknown", "Unknown")).id()).isEqualTo("assemblyai-auto");
        assertThat(subject.normalizeModelSelection(SpeechToTextCatalogItem.of("bad\tmodel", "Bad")).id()).isEqualTo("assemblyai-auto");
        assertThat(subject.normalizeModelSelection(SpeechToTextCatalogItem.of("universal-2", "Custom Label")).label()).isEqualTo("AssemblyAI Universal-2");
    }

    @Test
    @DisplayName("AssemblyAI transcription with automatic model uploads, submits without speech_models, polls, and deletes")
    void transcribe_whenCompletedWithAutoModel_omitsSpeechModelsAndReturnsTranscript() throws Exception {
        Path audio = audioFile();
        var transport = new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.assemblyai.example/audio.wav\"}"),
                success("{\"id\":\"tx 123\"}"),
                success("{\"status\":\"completed\",\"text\":\" hello world \"}"),
                success("{}")
        );
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        var result = subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true)));

        assertThat(result.text()).isEqualTo("hello world");
        assertThat(transport.requests).hasSize(4);
        assertThat(transport.requests.get(0).method()).isEqualTo("POST");
        assertThat(transport.requests.get(0).uri()).isEqualTo(URI.create("https://api.assemblyai.com/v2/upload"));
        assertThat(transport.requests.get(0).headers()).containsEntry("Authorization", "test-key");
        assertThat(transport.requests.get(0).headers()).containsEntry("Content-Type", "application/octet-stream");
        assertThat(transport.requests.get(0).headers().get("Authorization")).doesNotContain("Bearer", "Token");
        assertThat(bodyText(transport.requests.get(0))).isEqualTo("RIFF-test");

        assertThat(transport.requests.get(1).uri()).isEqualTo(URI.create("https://api.assemblyai.com/v2/transcript"));
        assertThat(transport.requests.get(1).headers()).containsEntry("Authorization", "test-key");
        assertThat(bodyText(transport.requests.get(1))).contains("\"audio_url\":\"https://cdn.assemblyai.example/audio.wav\"");
        assertThat(bodyText(transport.requests.get(1))).doesNotContain("speech_models");

        assertThat(transport.requests.get(2).method()).isEqualTo("GET");
        assertThat(transport.requests.get(2).uri()).isEqualTo(URI.create("https://api.assemblyai.com/v2/transcript/tx%20123"));
        assertThat(transport.requests.get(2).headers()).containsEntry("Authorization", "test-key");
        assertThat(transport.requests.get(3).method()).isEqualTo("DELETE");
        assertThat(transport.requests.get(3).uri()).isEqualTo(URI.create("https://api.assemblyai.com/v2/transcript/tx%20123"));
        assertThat(transport.requests.get(3).headers()).containsEntry("Authorization", "test-key");
    }

    @Test
    @DisplayName("AssemblyAI transcription sends a one-item speech_models array for specific models")
    void transcribe_whenSpecificModelSelected_sendsSingleSpeechModel() throws Exception {
        Path audio = audioFile();
        var transport = successfulTransport("done");
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        subject.transcribe(request("universal-3-5-pro", audio, 1_000), context(credentials(true)));

        assertThat(bodyText(transport.requests.get(1))).contains("\"speech_models\":[\"universal-3-5-pro\"]");
    }

    @Test
    @DisplayName("AssemblyAI transcription maps common upload errors")
    void transcribe_whenUploadFails_mapsCredentialsRateLimitAndServerErrors() throws Exception {
        Path audio = audioFile();

        assertTranscriptionError(audio, 401, "AssemblyAI credentials were rejected.");
        assertTranscriptionError(audio, 403, "AssemblyAI credentials were rejected.");
        assertTranscriptionError(audio, 404, "AssemblyAI speech-to-text endpoint or transcript job was not found.");
        assertTranscriptionError(audio, 413, "Recording is too large to upload.");
        assertTranscriptionError(audio, 429, "AssemblyAI rate limit reached. Try again later.");
        assertTranscriptionError(audio, 500, "AssemblyAI speech-to-text is temporarily unavailable.");
    }

    @Test
    @DisplayName("AssemblyAI transcription rejects oversized uploads before sending")
    void transcribe_whenFileTooLarge_rejectsBeforeTransport() throws Exception {
        Path audio = audioFile();
        var transport = new SequentialTransport(success("{}"));
        var subject = new AssemblyAiSpeechToTextProvider(transport);
        var request = new SpeechToTextRequest("assemblyai", "assemblyai-auto", audio, 1_000, AssemblyAiSpeechToTextProvider.MAX_UPLOAD_BYTES + 1);

        assertThatThrownBy(() -> subject.transcribe(request, context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("Recording is too large to upload.");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    @DisplayName("AssemblyAI transcription includes sanitized submit error detail")
    void transcribe_whenSubmitFails_includesSafeErrorDetail() throws Exception {
        Path audio = audioFile();
        var transport = new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                new SttHttpResponse(422, emptyMap(), "{\"error\":{\"message\":\"bad test-key input\"}}".getBytes(StandardCharsets.UTF_8))
        );
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("AssemblyAI transcription failed")
                .hasMessageContaining("bad **** input")
                .hasMessageNotContaining("test-key");
    }

    @Test
    @DisplayName("AssemblyAI transcription maps poll HTTP errors and deletes the transcript")
    void transcribe_whenPollHttpError_mapsCommonStatusCodes() throws Exception {
        Path audio = audioFile();
        var transport = new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                success("{\"id\":\"tx123\"}"),
                new SttHttpResponse(429, emptyMap(), "{}".getBytes(StandardCharsets.UTF_8)),
                success("{}")
        );
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("AssemblyAI rate limit reached. Try again later.");
        assertThat(transport.requests).extracting("method").containsExactly("POST", "POST", "GET", "DELETE");
    }

    @Test
    @DisplayName("AssemblyAI transcription rejects invalid upload and submit responses")
    void transcribe_whenUploadOrSubmitResponseInvalid_throws() throws Exception {
        Path audio = audioFile();

        assertThatThrownBy(() -> new AssemblyAiSpeechToTextProvider(new SequentialTransport(success("{\"upload_url\":\"bad\nurl\"}")))
                .transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("upload response was invalid");
        assertThatThrownBy(() -> new AssemblyAiSpeechToTextProvider(new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                success("{\"id\":\"bad\nid\"}")
        )).transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("transcription response was invalid");
    }

    @Test
    @DisplayName("AssemblyAI transcription fails on error or unknown poll statuses and deletes transcript")
    void transcribe_whenPollReturnsErrorOrUnknownStatus_throwsAndAttemptsDelete() throws Exception {
        Path audio = audioFile();
        var errorTransport = new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                success("{\"id\":\"tx123\"}"),
                success("{\"status\":\"error\",\"error\":\"bad test-key input\"}"),
                success("{}")
        );
        var unknownTransport = new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                success("{\"id\":\"tx123\"}"),
                success("{\"status\":\"surprise\"}"),
                success("{}")
        );

        assertThatThrownBy(() -> new AssemblyAiSpeechToTextProvider(errorTransport).transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("bad **** input")
                .hasMessageNotContaining("test-key");
        assertThat(errorTransport.requests).extracting("method").containsExactly("POST", "POST", "GET", "DELETE");
        assertThatThrownBy(() -> new AssemblyAiSpeechToTextProvider(unknownTransport).transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("response was invalid");
        assertThat(unknownTransport.requests).extracting("method").containsExactly("POST", "POST", "GET", "DELETE");
    }

    @Test
    @DisplayName("AssemblyAI polling deadline clamps short and long recordings")
    void pollingDeadline_whenDurationMillisVaries_clampsToSupportedRange() {
        assertThat(AssemblyAiSpeechToTextProvider.pollingDeadline(1_000)).isEqualTo(Duration.ofMinutes(2));
        assertThat(AssemblyAiSpeechToTextProvider.pollingDeadline(600_000)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("AssemblyAI transcription times out deterministically and deletes transcript")
    void transcribe_whenPollingDeadlineExpires_throwsAndAttemptsDelete() throws Exception {
        Path audio = audioFile();
        var clock = new MutableClock();
        var transport = new QueuedPollTransport(success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"), success("{\"id\":\"tx123\"}"));
        var subject = new AssemblyAiSpeechToTextProvider(transport, clock, clock::advance, Duration.ofSeconds(3), Duration.ofSeconds(30));

        assertThatThrownBy(() -> subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("AssemblyAI transcription timed out.");
        assertThat(transport.requests).extracting("method").contains("DELETE");
    }

    @Test
    @DisplayName("AssemblyAI transcription treats blank completed text as no speech and deletes transcript")
    void transcribe_whenCompletedTextBlank_throwsNoSpeechAndAttemptsDelete() throws Exception {
        Path audio = audioFile();
        var transport = new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                success("{\"id\":\"tx123\"}"),
                success("{\"status\":\"completed\",\"text\":\"   \"}"),
                success("{}")
        );
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("No speech was recorded.");
        assertThat(transport.requests).extracting("method").containsExactly("POST", "POST", "GET", "DELETE");
    }

    @Test
    @DisplayName("AssemblyAI transcription exits promptly when cancelled before upload")
    void transcribe_whenAlreadyCancelledBeforeUpload_exitsWithoutSending() throws Exception {
        Path audio = audioFile();
        var transport = new SequentialTransport();
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true), () -> true)))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("Transcription canceled.");
        assertThat(transport.requests).isEmpty();
    }

    @Test
    @DisplayName("AssemblyAI transcription cancellation after upload skips submit")
    void transcribe_whenCancelledAfterUploadBeforeSubmit_exitsPromptlyWithoutSubmit() throws Exception {
        Path audio = audioFile();
        var token = new MutableCancellationToken();
        var transport = new SequentialTransport(success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"));
        transport.afterSend = request -> token.cancelled = true;
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true), token)))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("Transcription canceled.");
        assertThat(transport.requests).extracting("method").containsExactly("POST");
    }

    @Test
    @DisplayName("AssemblyAI transcription cancellation after submit uses non-cancelled cleanup token")
    void transcribe_whenCancelledAfterSubmitBeforeOrDuringPolling_exitsPromptlyAndAttemptsDelete() throws Exception {
        Path audio = audioFile();
        var token = new MutableCancellationToken();
        var transport = new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                success("{\"id\":\"tx123\"}"),
                success("{}")
        );
        transport.afterSend = request -> {
            if ("POST".equals(request.method()) && request.uri().getPath().endsWith("/transcript")) {
                token.cancelled = true;
            }
        };
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        assertThatThrownBy(() -> subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true), token)))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage("Transcription canceled.");
        assertThat(transport.requests).extracting("method").containsExactly("POST", "POST", "DELETE");
        assertThat(transport.tokens.get(2).cancelled()).isFalse();
    }

    @Test
    @DisplayName("AssemblyAI interrupted poll waits restore interrupt status and delete transcript")
    void transcribe_whenPollWaitInterrupted_restoresInterruptAndAttemptsDelete() throws Exception {
        Path audio = audioFile();
        var transport = new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                success("{\"id\":\"tx123\"}"),
                success("{\"status\":\"queued\"}"),
                success("{}")
        );
        var subject = new AssemblyAiSpeechToTextProvider(transport, Clock.systemUTC(), duration -> {
            throw new InterruptedException("stop");
        }, Duration.ofSeconds(3), Duration.ofMillis(250));

        try {
            assertThatThrownBy(() -> subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                    .isInstanceOf(SpeechToTextException.class)
                    .hasMessage("Transcription canceled.");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(transport.requests).extracting("method").containsExactly("POST", "POST", "GET", "DELETE");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("AssemblyAI cleanup failures preserve a successful transcript")
    void transcribe_whenCleanupDeleteFails_preservesSuccessfulTranscript() throws Exception {
        Path audio = audioFile();
        var transport = new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                success("{\"id\":\"tx123\"}"),
                success("{\"status\":\"completed\",\"text\":\"done\"}")
        );
        transport.failDelete = true;
        var subject = new AssemblyAiSpeechToTextProvider(transport);

        var result = subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true)));

        assertThat(result.text()).isEqualTo("done");
        assertThat(transport.requests).extracting("method").containsExactly("POST", "POST", "GET", "DELETE");
    }

    private void assertTranscriptionError(Path audio, int statusCode, String message) {
        var subject = new AssemblyAiSpeechToTextProvider(new SequentialTransport(new SttHttpResponse(statusCode, emptyMap(), "{}".getBytes(StandardCharsets.UTF_8))));

        assertThatThrownBy(() -> subject.transcribe(request("assemblyai-auto", audio, 1_000), context(credentials(true))))
                .isInstanceOf(SpeechToTextException.class)
                .hasMessage(message);
    }

    private SequentialTransport successfulTransport(String text) {
        return new SequentialTransport(
                success("{\"upload_url\":\"https://cdn.example/audio.wav\"}"),
                success("{\"id\":\"tx123\"}"),
                success("{\"status\":\"completed\",\"text\":\"%s\"}".formatted(text)),
                success("{}")
        );
    }

    private Path audioFile() throws Exception {
        Path audio = tempDir.resolve("recording.wav");
        Files.writeString(audio, "RIFF-test");
        return audio;
    }

    private SpeechToTextRequest request(String modelId, Path audio, long durationMillis) throws Exception {
        return new SpeechToTextRequest("assemblyai", modelId, audio, durationMillis, Files.size(audio));
    }

    private SpeechToTextProviderContext context(CredentialSource credentialSource) throws Exception {
        return context(credentialSource, () -> false);
    }

    private SpeechToTextProviderContext context(CredentialSource credentialSource, SpeechToTextProviderContext.CancellationToken cancellationToken) throws Exception {
        AssemblyAiSttEndpointResolver.Endpoint endpoint = AssemblyAiSttEndpointResolver.resolve(AssemblyAiSttEndpointResolver.DEFAULT_BASE_URL);
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

    private static SttHttpResponse success(String body) {
        return new SttHttpResponse(200, emptyMap(), body.getBytes(StandardCharsets.UTF_8));
    }

    private String bodyText(SttHttpRequest request) throws Exception {
        var subscriber = new BodySubscriber();
        request.bodyPublisher().subscribe(subscriber);
        return subscriber.body();
    }

    private static class SequentialTransport implements SttHttpTransport {
        protected final ArrayDeque<SttHttpResponse> responses = new ArrayDeque<>();
        protected final List<SttHttpRequest> requests = new ArrayList<>();
        protected final List<SpeechToTextProviderContext.CancellationToken> tokens = new ArrayList<>();
        private RequestCallback afterSend = request -> {
        };
        private boolean failDelete;

        private SequentialTransport(SttHttpResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public SttHttpResponse send(SttHttpRequest request, SpeechToTextProviderContext.CancellationToken cancellationToken) {
            requests.add(request);
            tokens.add(cancellationToken);
            afterSend.call(request);
            if (failDelete && "DELETE".equals(request.method())) {
                throw new IllegalStateException("delete failed");
            }
            return responses.isEmpty() ? success("{}") : responses.removeFirst();
        }
    }

    private static final class QueuedPollTransport extends SequentialTransport {
        private QueuedPollTransport(SttHttpResponse upload, SttHttpResponse submit) {
            super(upload, submit);
        }

        @Override
        public SttHttpResponse send(SttHttpRequest request, SpeechToTextProviderContext.CancellationToken cancellationToken) {
            requests.add(request);
            tokens.add(cancellationToken);
            if ("GET".equals(request.method())) {
                return success("{\"status\":\"queued\"}");
            }
            if ("DELETE".equals(request.method())) {
                return success("{}");
            }
            return responses.removeFirst();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-08T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    private static final class MutableCancellationToken implements SpeechToTextProviderContext.CancellationToken {
        private boolean cancelled;

        @Override
        public boolean cancelled() {
            return cancelled;
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

    @FunctionalInterface
    private interface RequestCallback {
        void call(SttHttpRequest request);
    }
}
