package com.github.drafael.chat4j.stt.provider.elevenlabs;

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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.util.stream.Collectors.joining;

public class ElevenLabsSpeechToTextProvider implements SpeechToTextProvider {

    public static final String ID = "elevenlabs";
    public static final String ENV_VAR = "ELEVENLABS_API_KEY";
    public static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
    private static final long TRANSCRIPTION_RESPONSE_LIMIT_BYTES = 5L * 1024L * 1024L;
    private static final long MODEL_RESPONSE_LIMIT_BYTES = 2L * 1024L * 1024L;
    private static final int ERROR_DETAIL_LIMIT = 300;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SttHttpTransport transport;

    public ElevenLabsSpeechToTextProvider(SttHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "ElevenLabs";
    }

    @Override
    public String requiredEnvVar() {
        return ENV_VAR;
    }

    @Override
    public SpeechToTextCatalogItem defaultModel() {
        return ElevenLabsSpeechToTextModels.DEFAULT_MODEL;
    }

    @Override
    public List<SpeechToTextCatalogItem> bundledModels() {
        return ElevenLabsSpeechToTextModels.BUNDLED_MODELS;
    }

    @Override
    public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception {
        if (!available(context.credentialSource())) {
            return bundledModels();
        }
        SttHttpRequest request = new SttHttpRequest(
                "GET",
                ElevenLabsSttEndpointResolver.resolve(context.baseUri()).modelsUri(),
                authJsonHeaders(context),
                HttpRequest.BodyPublishers.noBody(),
                context.timeout(),
                MODEL_RESPONSE_LIMIT_BYTES
        );
        SttHttpResponse response = transport.send(request, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException("ElevenLabs model catalog refresh failed: %s".formatted(errorDetail(response, context)));
        }
        List<SpeechToTextCatalogItem> models = parseModels(response.body());
        if (models.isEmpty()) {
            throw new SpeechToTextException("ElevenLabs model catalog did not include any supported Speech to Text models.");
        }
        return models;
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception {
        if (request.sizeBytes() > MAX_UPLOAD_BYTES) {
            throw new SpeechToTextException("Recording is too large to upload.");
        }
        String boundary = "----chat4j-stt-%s".formatted(UUID.randomUUID());
        SttHttpRequest httpRequest = new SttHttpRequest(
                "POST",
                ElevenLabsSttEndpointResolver.resolve(context.baseUri()).transcriptionUri(),
                multipartHeaders(context, boundary),
                multipartBody(boundary, request.audioFile(), StringUtils.defaultIfBlank(request.modelId(), ElevenLabsSpeechToTextModels.DEFAULT_MODEL_ID)),
                context.timeout(),
                TRANSCRIPTION_RESPONSE_LIMIT_BYTES
        );
        SttHttpResponse response = transport.send(httpRequest, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException(errorMessage(response, context));
        }
        return parseTranscript(response);
    }

    private List<SpeechToTextCatalogItem> parseModels(byte[] body) throws Exception {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new SpeechToTextException("ElevenLabs model catalog response was invalid.", e);
        }
        JsonNode modelsNode = root.isArray() ? root : root.path("models");
        if (!modelsNode.isArray()) {
            throw new SpeechToTextException("ElevenLabs model catalog response was invalid.");
        }
        Map<String, SpeechToTextCatalogItem> models = new LinkedHashMap<>();
        modelsNode.forEach(model -> addModel(models, model));
        return models.values().stream().toList();
    }

    private void addModel(Map<String, SpeechToTextCatalogItem> models, JsonNode model) {
        String id = firstText(model, "model_id", "id");
        if (invalidModelId(id) || !supportedBatchModel(model, id)) {
            return;
        }
        String label = firstText(model, "name", "label", "model_id", "id");
        String description = StringUtils.trimToEmpty(model.path("description").asText(""));
        if (Strings.CS.equals(id, "scribe_v1")) {
            label = deprecatedLabel(label, id);
            description = StringUtils.defaultIfBlank(description, "Deprecated; use Scribe v2.");
        }
        models.putIfAbsent(id, new SpeechToTextCatalogItem(id, label, description));
    }

    private boolean supportedBatchModel(JsonNode model, String id) {
        if (explicitNegativeStt(model) || Strings.CS.equals(id, "scribe_v2_realtime")) {
            return false;
        }
        if (Strings.CS.equalsAny(id, ElevenLabsSpeechToTextModels.DEFAULT_MODEL_ID, "scribe_v1")) {
            return true;
        }
        return explicitPositiveStt(model);
    }

    private boolean explicitPositiveStt(JsonNode model) {
        return booleanField(model, true, "can_do_speech_to_text", "can_do_transcription", "can_do_speech_to_text_batch", "can_do_batch_transcription");
    }

    private boolean explicitNegativeStt(JsonNode model) {
        return booleanField(model, false, "can_do_speech_to_text", "can_do_transcription", "can_do_speech_to_text_batch", "can_do_batch_transcription");
    }

    private boolean booleanField(JsonNode model, boolean value, String... fields) {
        return Arrays.stream(fields)
                .map(model::path)
                .anyMatch(node -> node.isBoolean() && node.asBoolean() == value);
    }

    private boolean invalidModelId(String id) {
        return StringUtils.isBlank(id) || id.chars().anyMatch(Character::isISOControl);
    }

    private String deprecatedLabel(String label, String id) {
        String normalized = StringUtils.defaultIfBlank(label, id);
        return Strings.CI.contains(normalized, "deprecated") ? normalized : "%s (deprecated)".formatted(normalized);
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
                stringPart(boundary, "model_id", modelId),
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
                "xi-api-key", context.credentialSource().resolveRequiredApiKey(ENV_VAR),
                "Content-Type", "multipart/form-data; boundary=%s".formatted(boundary),
                "Accept", "application/json"
        );
    }

    private Map<String, String> authJsonHeaders(SpeechToTextProviderContext context) {
        return Map.of(
                "xi-api-key", context.credentialSource().resolveRequiredApiKey(ENV_VAR),
                "Accept", "application/json"
        );
    }

    private String errorMessage(SttHttpResponse response, SpeechToTextProviderContext context) {
        return switch (response.statusCode()) {
            case 401, 403 -> "ElevenLabs credentials were rejected.";
            case 404 -> "ElevenLabs speech-to-text endpoint or model was not found.";
            case 413 -> "Recording is too large to upload.";
            case 429 -> "ElevenLabs rate limit reached. Try again later.";
            default -> response.statusCode() >= 500
                    ? "ElevenLabs speech-to-text is temporarily unavailable."
                    : "ElevenLabs transcription failed: %s".formatted(errorDetail(response, context));
        };
    }

    private String errorDetail(SttHttpResponse response, SpeechToTextProviderContext context) {
        String body = StringUtils.abbreviate(response.bodyText(), 64 * 1024);
        try {
            JsonNode json = OBJECT_MAPPER.readTree(body);
            String message = firstNonBlank(
                    detailMessage(json.path("detail")),
                    json.path("message").asText(""),
                    json.path("error").path("message").asText(""),
                    json.path("error").asText("")
            );
            return safeDetail(StringUtils.defaultIfBlank(message, "HTTP %d".formatted(response.statusCode())), context);
        } catch (Exception e) {
            return safeDetail(StringUtils.defaultIfBlank(body, "HTTP %d".formatted(response.statusCode())), context);
        }
    }

    private String detailMessage(JsonNode detail) {
        if (detail.isTextual()) {
            return detail.asText("");
        }
        if (detail.isObject()) {
            return detail.path("message").asText("");
        }
        if (detail.isArray()) {
            return detail.findValuesAsText("msg").stream()
                    .filter(StringUtils::isNotBlank)
                    .collect(joining("; "));
        }
        return "";
    }

    private String safeDetail(String message, SpeechToTextProviderContext context) {
        String sanitized = StringUtils.defaultString(message).replaceAll("[\\p{Cntrl}]", " ").trim();
        String apiKey = context.credentialSource().requiredApiKeyOrBlank(ENV_VAR);
        if (StringUtils.isNotBlank(apiKey)) {
            sanitized = sanitized.replace(apiKey, "****");
        }
        return StringUtils.defaultIfBlank(StringUtils.abbreviate(sanitized, ERROR_DETAIL_LIMIT), "HTTP error");
    }

    private String firstText(JsonNode node, String... fields) {
        return Arrays.stream(fields)
                .map(field -> node.path(field).asText(""))
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }
}
