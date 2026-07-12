package com.github.drafael.chat4j.stt.provider.deepgram;

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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import static java.util.stream.Collectors.joining;

public class DeepgramSpeechToTextProvider implements SpeechToTextProvider {

    public static final String ID = "deepgram";
    public static final String ENV_VAR = "DEEPGRAM_API_KEY";
    public static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
    private static final long TRANSCRIPTION_RESPONSE_LIMIT_BYTES = 5L * 1024L * 1024L;
    private static final long MODEL_RESPONSE_LIMIT_BYTES = 2L * 1024L * 1024L;
    private static final int ERROR_DETAIL_LIMIT = 300;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SttHttpTransport transport;

    public DeepgramSpeechToTextProvider(SttHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Deepgram";
    }

    @Override
    public String requiredEnvVar() {
        return ENV_VAR;
    }

    @Override
    public SpeechToTextCatalogItem defaultModel() {
        return DeepgramSpeechToTextModels.DEFAULT_MODEL;
    }

    @Override
    public List<SpeechToTextCatalogItem> bundledModels() {
        return DeepgramSpeechToTextModels.BUNDLED_MODELS;
    }

    @Override
    public SpeechToTextCatalogItem normalizeModelSelection(SpeechToTextCatalogItem model) {
        if (model == null) {
            return DeepgramSpeechToTextModels.DEFAULT_MODEL;
        }
        String id = normalizeModelId(model.id());
        return DeepgramSpeechToTextModels.DEFAULT_MODEL_ID.equals(id) && !DeepgramSpeechToTextModels.DEFAULT_MODEL_ID.equals(model.id())
                ? DeepgramSpeechToTextModels.DEFAULT_MODEL
                : new SpeechToTextCatalogItem(id, modelLabel(id), model.description());
    }

    @Override
    public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) throws Exception {
        if (!available(context.credentialSource())) {
            return bundledModels();
        }
        SttHttpRequest request = new SttHttpRequest(
                "GET",
                DeepgramSttEndpointResolver.resolve(context.baseUri()).modelsUri(),
                authJsonHeaders(context),
                HttpRequest.BodyPublishers.noBody(),
                context.timeout(),
                MODEL_RESPONSE_LIMIT_BYTES
        );
        SttHttpResponse response = transport.send(request, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException("Deepgram model catalog refresh failed: %s".formatted(errorDetail(response, context)));
        }
        List<SpeechToTextCatalogItem> models = parseModels(response.body());
        if (models.isEmpty()) {
            throw new SpeechToTextException("Deepgram model catalog did not include any batch Speech to Text models.");
        }
        return models;
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception {
        if (request.sizeBytes() > MAX_UPLOAD_BYTES) {
            throw new SpeechToTextException("Recording is too large to upload.");
        }
        String modelId = StringUtils.defaultIfBlank(request.modelId(), DeepgramSpeechToTextModels.DEFAULT_MODEL_ID);
        SttHttpRequest httpRequest = new SttHttpRequest(
                "POST",
                transcriptionUri(context, modelId),
                authAudioHeaders(context),
                HttpRequest.BodyPublishers.ofFile(request.audioFile()),
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
            throw new SpeechToTextException("Deepgram model catalog response was invalid.", e);
        }
        JsonNode sttModels = root.path("stt");
        if (!sttModels.isArray()) {
            throw new SpeechToTextException("Deepgram model catalog response was invalid.");
        }
        Map<String, SpeechToTextCatalogItem> models = new LinkedHashMap<>();
        sttModels.forEach(model -> addModel(models, model));
        return models.values().stream().toList();
    }

    private void addModel(Map<String, SpeechToTextCatalogItem> models, JsonNode model) {
        if (!model.path("batch").asBoolean(false)) {
            return;
        }
        String id = StringUtils.trimToEmpty(firstText(model, "canonical_name")).toLowerCase(Locale.ROOT);
        if (!isSupportedModelId(id)) {
            return;
        }
        models.putIfAbsent(id, new SpeechToTextCatalogItem(id, modelLabel(id), modelDescription(model)));
    }

    private String modelDescription(JsonNode model) {
        return Arrays.stream(new String[] {
                        descriptionPart("Architecture", firstText(model, "architecture")),
                        descriptionPart("Version", firstText(model, "version"))
                })
                .filter(StringUtils::isNotBlank)
                .collect(joining("; "));
    }

    private String descriptionPart(String label, String value) {
        return StringUtils.isBlank(value) ? "" : "%s: %s".formatted(label, value);
    }

    private SpeechToTextResult parseTranscript(SttHttpResponse response) throws Exception {
        JsonNode json;
        try {
            json = OBJECT_MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new SpeechToTextException("Transcription response was invalid.", e);
        }
        String text = StringUtils.trimToEmpty(json.path("results").path("channels").path(0).path("alternatives").path(0).path("transcript").asText(""));
        if (text.isBlank()) {
            throw new SpeechToTextException("No speech was recorded.");
        }
        return new SpeechToTextResult(text);
    }

    private URI transcriptionUri(SpeechToTextProviderContext context, String modelId) throws SpeechToTextException {
        URI uri = DeepgramSttEndpointResolver.resolve(context.baseUri()).transcriptionUri();
        return URI.create("%s?model=%s".formatted(uri, encodeQueryValue(modelId)));
    }

    private String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Map<String, String> authAudioHeaders(SpeechToTextProviderContext context) {
        return Map.of(
                "Authorization", "Token %s".formatted(context.credentialSource().resolveRequiredApiKey(ENV_VAR)),
                "Content-Type", "audio/wav",
                "Accept", "application/json"
        );
    }

    private Map<String, String> authJsonHeaders(SpeechToTextProviderContext context) {
        return Map.of(
                "Authorization", "Token %s".formatted(context.credentialSource().resolveRequiredApiKey(ENV_VAR)),
                "Accept", "application/json"
        );
    }

    private String errorMessage(SttHttpResponse response, SpeechToTextProviderContext context) {
        return switch (response.statusCode()) {
            case 401, 403 -> "Deepgram credentials were rejected.";
            case 404 -> "Deepgram speech-to-text endpoint or model was not found.";
            case 413 -> "Recording is too large to upload.";
            case 429 -> "Deepgram rate limit reached. Try again later.";
            default -> response.statusCode() >= 500
                    ? "Deepgram speech-to-text is temporarily unavailable."
                    : "Deepgram transcription failed: %s".formatted(errorDetail(response, context));
        };
    }

    private String errorDetail(SttHttpResponse response, SpeechToTextProviderContext context) {
        String body = StringUtils.abbreviate(response.bodyText(), 64 * 1024);
        try {
            JsonNode json = OBJECT_MAPPER.readTree(body);
            String message = firstNonBlank(
                    json.path("err_msg").asText(""),
                    json.path("err_code").asText(""),
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
            return firstNonBlank(detail.path("message").asText(""), detail.path("err_msg").asText(""));
        }
        if (detail.isArray()) {
            return detail.findValuesAsText("message").stream()
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

    private String normalizeModelId(String id) {
        String normalized = StringUtils.trimToEmpty(id).toLowerCase(Locale.ROOT);
        return isSupportedModelId(normalized) ? normalized : DeepgramSpeechToTextModels.DEFAULT_MODEL_ID;
    }

    private boolean isSupportedModelId(String id) {
        if (StringUtils.isBlank(id) || id.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        return id.equals("nova")
                || id.startsWith("nova-")
                || id.equals("enhanced")
                || id.startsWith("enhanced-")
                || id.equals("base")
                || id.startsWith("base-");
    }

    private String modelLabel(String id) {
        return "Deepgram %s".formatted(Arrays.stream(id.split("-"))
                .filter(StringUtils::isNotBlank)
                .map(StringUtils::capitalize)
                .collect(joining(" ")));
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
