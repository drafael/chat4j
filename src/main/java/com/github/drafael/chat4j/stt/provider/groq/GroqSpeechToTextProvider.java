package com.github.drafael.chat4j.stt.provider.groq;

import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.HttpBody;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


public class GroqSpeechToTextProvider implements SpeechToTextProvider {

    public static final String ID = "groq";
    public static final String ENV_VAR = "GROQ_API_KEY";
    public static final long MAX_UPLOAD_BYTES = 25L * 1024L * 1024L;
    private static final long TRANSCRIPTION_RESPONSE_LIMIT_BYTES = 1024L * 1024L;
    private static final long MODEL_RESPONSE_LIMIT_BYTES = 2L * 1024L * 1024L;
    private static final int ERROR_DETAIL_LIMIT = 64 * 1024;
    private static final JsonCodec JSON_CODEC = JsonCodec.standard();

    private final HttpTransport transport;

    public GroqSpeechToTextProvider(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Groq";
    }

    @Override
    public String requiredEnvVar() {
        return ENV_VAR;
    }

    @Override
    public SpeechToTextCatalogItem defaultModel() {
        return GroqSpeechToTextModels.DEFAULT_MODEL;
    }

    @Override
    public List<SpeechToTextCatalogItem> bundledModels() {
        return GroqSpeechToTextModels.BUNDLED_MODELS;
    }

    @Override
    public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception {
        if (!available(context.credentialSource())) {
            throw new SpeechToTextException("Groq model catalog refresh requires credentials.");
        }
        HttpExchangeRequest request = new HttpExchangeRequest(
                "GET",
                GroqSttEndpointResolver.resolve(context.baseUri().toString()).modelsUri(),
                authJsonHeaders(context),
                HttpBody.empty(),
                context.timeout(),
                MODEL_RESPONSE_LIMIT_BYTES
        );
        HttpExchangeResponse response = transport.send(request, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException(
                    "Groq model catalog refresh failed with HTTP %d.".formatted(response.statusCode())
            );
        }
        List<SpeechToTextCatalogItem> models = new ArrayList<>();
        GroqSttApi.ModelsResponse body;
        try {
            body = JSON_CODEC.read(response.body(), GroqSttApi.ModelsResponse.class);
        } catch (Exception e) {
            throw new SpeechToTextException("Groq model catalog response was invalid.", e);
        }
        if (body.data() == null) {
            throw new SpeechToTextException("Groq model catalog response was invalid.");
        }
        if (!body.data().isEmpty() && body.data().stream()
                .noneMatch(model -> model != null && StringUtils.isNotBlank(model.id()))) {
            throw new SpeechToTextException("Groq model catalog response did not contain valid model IDs.");
        }
        body.data().stream()
                .filter(model -> model != null && isTranscriptionModel(model.id()))
                .map(model -> SpeechToTextCatalogItem.of(model.id(), model.id()))
                .forEach(models::add);
        return models.isEmpty() ? bundledModels() : models;
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception {
        if (request.sizeBytes() > MAX_UPLOAD_BYTES) {
            throw new SpeechToTextException("Recording is too large to upload.");
        }
        String boundary = "----chat4j-stt-%s".formatted(UUID.randomUUID());
        HttpExchangeRequest httpRequest = new HttpExchangeRequest(
                "POST",
                context.transcriptionUri(),
                multipartHeaders(context, boundary),
                multipartBody(boundary, request.audioFile(), request.modelId()),
                context.timeout(),
                TRANSCRIPTION_RESPONSE_LIMIT_BYTES
        );
        HttpExchangeResponse response = transport.send(httpRequest, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException(errorMessage(response));
        }
        return parseTranscript(response);
    }

    private SpeechToTextResult parseTranscript(HttpExchangeResponse response) throws Exception {
        GroqSttApi.TranscriptionResponse body;
        try {
            body = JSON_CODEC.read(response.body(), GroqSttApi.TranscriptionResponse.class);
        } catch (Exception e) {
            throw new SpeechToTextException("Transcription response was invalid.", e);
        }
        String text = StringUtils.trimToEmpty(body.text());
        if (text.isBlank()) {
            throw new SpeechToTextException("No speech was recorded.");
        }
        return new SpeechToTextResult(text);
    }

    private HttpBody multipartBody(String boundary, Path audioFile, String modelId) {
        return HttpBody.composite(List.of(
                stringPart(boundary, "model", modelId),
                stringPart(boundary, "response_format", "json"),
                filePart(boundary, audioFile),
                HttpBody.utf8("--%s--\r\n".formatted(boundary))
        ));
    }

    private HttpBody stringPart(String boundary, String name, String value) {
        return HttpBody.utf8(
                "--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                        .formatted(boundary, name, StringUtils.defaultString(value))
        );
    }

    private HttpBody filePart(String boundary, Path audioFile) {
        return HttpBody.composite(List.of(
                HttpBody.utf8(
                        "--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\nContent-Type: audio/wav\r\n\r\n"
                                .formatted(boundary, safeFileName(audioFile))
                ),
                HttpBody.file(audioFile),
                HttpBody.utf8("\r\n")
        ));
    }

    private String safeFileName(Path audioFile) {
        String name = audioFile == null || audioFile.getFileName() == null ? "recording.wav" : audioFile.getFileName().toString();
        String sanitized = StringUtils.defaultIfBlank(name.replaceAll("[\\r\\n\"]", "_"), "recording.wav");
        return sanitized.endsWith(".wav") ? sanitized : "%s.wav".formatted(sanitized);
    }

    private Map<String, String> multipartHeaders(SpeechToTextProviderContext context, String boundary) {
        return Map.of(
                "Authorization", "Bearer %s".formatted(context.credentialSource().resolveRequiredApiKey(ENV_VAR)),
                "Content-Type", "multipart/form-data; boundary=%s".formatted(boundary),
                "Accept", "application/json"
        );
    }

    private Map<String, String> authJsonHeaders(SpeechToTextProviderContext context) {
        return Map.of(
                "Authorization", "Bearer %s".formatted(context.credentialSource().resolveRequiredApiKey(ENV_VAR)),
                "Accept", "application/json"
        );
    }

    private String errorMessage(HttpExchangeResponse response) {
        return switch (response.statusCode()) {
            case 401, 403 -> "Groq credentials were rejected.";
            case 404 -> "Groq transcription endpoint or model was not found.";
            case 413 -> "Recording is too large to upload.";
            case 429 -> "Groq rate limit reached. Try again later.";
            default -> response.statusCode() >= 500
                    ? "Groq transcription is temporarily unavailable."
                    : "Groq transcription failed: %s".formatted(errorDetail(response));
        };
    }

    private String errorDetail(HttpExchangeResponse response) {
        String body = StringUtils.abbreviate(response.bodyText(), ERROR_DETAIL_LIMIT);
        try {
            GroqSttApi.ErrorResponse error = JSON_CODEC.read(body, GroqSttApi.ErrorResponse.class);
            String message = firstNonBlank(
                    error.error() == null ? "" : error.error().message(),
                    error.detail() == null ? "" : error.detail().message(),
                    error.message()
            );
            return StringUtils.defaultIfBlank(message, "HTTP %d".formatted(response.statusCode()));
        } catch (Exception e) {
            return StringUtils.defaultIfBlank(StringUtils.abbreviate(body, 300), "HTTP %d".formatted(response.statusCode()));
        }
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    private boolean isTranscriptionModel(String modelId) {
        if (StringUtils.isBlank(modelId)) {
            return false;
        }
        boolean known = bundledModels().stream().anyMatch(item -> Strings.CS.equals(item.id(), modelId));
        boolean whisper = Strings.CI.contains(modelId, "whisper") || Strings.CI.contains(modelId, "distil-whisper");
        boolean excluded = Strings.CI.contains(modelId, "orpheus") || Strings.CI.contains(modelId, "tts")
                || (Strings.CI.contains(modelId, "speech") && !whisper);
        return (known || whisper) && !excluded;
    }
}
