package com.github.drafael.chat4j.stt.provider.groq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.stt.error.SpeechToTextException;
import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProvider;
import com.github.drafael.chat4j.stt.provider.SpeechToTextProviderContext;
import com.github.drafael.chat4j.stt.provider.SpeechToTextRequest;
import com.github.drafael.chat4j.stt.provider.SpeechToTextResult;
import com.github.drafael.chat4j.stt.provider.SttHttpRequest;
import com.github.drafael.chat4j.stt.provider.SttHttpResponse;
import com.github.drafael.chat4j.stt.provider.SttHttpTransport;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.util.stream.StreamSupport.stream;

public class GroqSpeechToTextProvider implements SpeechToTextProvider {

    public static final String ID = "groq";
    public static final String ENV_VAR = "GROQ_API_KEY";
    public static final long MAX_UPLOAD_BYTES = 25L * 1024L * 1024L;
    private static final long TRANSCRIPTION_RESPONSE_LIMIT_BYTES = 1024L * 1024L;
    private static final long MODEL_RESPONSE_LIMIT_BYTES = 2L * 1024L * 1024L;
    private static final int ERROR_DETAIL_LIMIT = 64 * 1024;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SttHttpTransport transport;

    public GroqSpeechToTextProvider(SttHttpTransport transport) {
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
        SttHttpRequest request = new SttHttpRequest(
                "GET",
                GroqSttEndpointResolver.resolve(context.baseUri().toString()).modelsUri(),
                authJsonHeaders(context),
                HttpRequest.BodyPublishers.noBody(),
                context.timeout(),
                MODEL_RESPONSE_LIMIT_BYTES
        );
        SttHttpResponse response = transport.send(request, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException(
                    "Groq model catalog refresh failed with HTTP %d.".formatted(response.statusCode())
            );
        }
        List<SpeechToTextCatalogItem> models = new ArrayList<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode data = root == null ? null : root.path("data");
            if (data == null || !data.isArray()) {
                throw new SpeechToTextException("Groq model catalog response was invalid.");
            }
            if (!data.isEmpty() && stream(data.spliterator(), false)
                    .noneMatch(model -> StringUtils.isNotBlank(model.path("id").asText("")))) {
                throw new SpeechToTextException("Groq model catalog response did not contain valid model IDs.");
            }
            data.forEach(model -> {
                String modelId = model.path("id").asText("");
                if (isTranscriptionModel(modelId)) {
                    models.add(SpeechToTextCatalogItem.of(modelId, modelId));
                }
            });
        } catch (SpeechToTextException e) {
            throw e;
        } catch (Exception e) {
            throw new SpeechToTextException("Groq model catalog response was invalid.", e);
        }
        return models.isEmpty() ? bundledModels() : models;
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception {
        if (request.sizeBytes() > MAX_UPLOAD_BYTES) {
            throw new SpeechToTextException("Recording is too large to upload.");
        }
        String boundary = "----chat4j-stt-%s".formatted(UUID.randomUUID());
        SttHttpRequest httpRequest = new SttHttpRequest(
                "POST",
                context.transcriptionUri(),
                multipartHeaders(context, boundary),
                multipartBody(boundary, request.audioFile(), request.modelId()),
                context.timeout(),
                TRANSCRIPTION_RESPONSE_LIMIT_BYTES
        );
        SttHttpResponse response = transport.send(httpRequest, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException(errorMessage(response));
        }
        return parseTranscript(response);
    }

    private SpeechToTextResult parseTranscript(SttHttpResponse response) throws Exception {
        JsonNode json;
        try {
            json = OBJECT_MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new SpeechToTextException("Transcription response was invalid.", e);
        }
        String text = StringUtils.trimToEmpty(json.path("text").asText(""));
        if (text.isBlank()) {
            throw new SpeechToTextException("No speech was recorded.");
        }
        return new SpeechToTextResult(text);
    }

    private HttpRequest.BodyPublisher multipartBody(String boundary, Path audioFile, String modelId) throws Exception {
        return HttpRequest.BodyPublishers.concat(
                stringPart(boundary, "model", modelId),
                stringPart(boundary, "response_format", "json"),
                filePart(boundary, audioFile),
                HttpRequest.BodyPublishers.ofString("--%s--\r\n".formatted(boundary), StandardCharsets.UTF_8)
        );
    }

    private HttpRequest.BodyPublisher stringPart(String boundary, String name, String value) {
        return HttpRequest.BodyPublishers.ofString(
                "--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                        .formatted(boundary, name, StringUtils.defaultString(value)),
                StandardCharsets.UTF_8
        );
    }

    private HttpRequest.BodyPublisher filePart(String boundary, Path audioFile) throws Exception {
        HttpRequest.BodyPublisher prefix = HttpRequest.BodyPublishers.ofString(
                "--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\nContent-Type: audio/wav\r\n\r\n"
                        .formatted(boundary, safeFileName(audioFile)),
                StandardCharsets.UTF_8
        );
        return HttpRequest.BodyPublishers.concat(
                prefix,
                HttpRequest.BodyPublishers.ofFile(audioFile),
                HttpRequest.BodyPublishers.ofString("\r\n", StandardCharsets.UTF_8)
        );
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

    private String errorMessage(SttHttpResponse response) {
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

    private String errorDetail(SttHttpResponse response) {
        String body = StringUtils.abbreviate(response.bodyText(), ERROR_DETAIL_LIMIT);
        try {
            JsonNode json = OBJECT_MAPPER.readTree(body);
            String message = firstNonBlank(
                    json.path("error").path("message").asText(""),
                    json.path("detail").path("message").asText(""),
                    json.path("message").asText("")
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
