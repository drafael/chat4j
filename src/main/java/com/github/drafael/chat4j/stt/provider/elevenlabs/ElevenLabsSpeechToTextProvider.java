package com.github.drafael.chat4j.stt.provider.elevenlabs;

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
    private static final JsonCodec JSON_CODEC = JsonCodec.standard();

    private final HttpTransport transport;

    public ElevenLabsSpeechToTextProvider(HttpTransport transport) {
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
            throw new SpeechToTextException("ElevenLabs model catalog refresh requires credentials.");
        }
        HttpExchangeRequest request = new HttpExchangeRequest(
                "GET",
                ElevenLabsSttEndpointResolver.resolve(context.baseUri()).modelsUri(),
                authJsonHeaders(context),
                HttpBody.empty(),
                context.timeout(),
                MODEL_RESPONSE_LIMIT_BYTES
        );
        HttpExchangeResponse response = transport.send(request, context.cancellationToken());
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
        HttpExchangeRequest httpRequest = new HttpExchangeRequest(
                "POST",
                ElevenLabsSttEndpointResolver.resolve(context.baseUri()).transcriptionUri(),
                multipartHeaders(context, boundary),
                multipartBody(boundary, request.audioFile(), StringUtils.defaultIfBlank(request.modelId(), ElevenLabsSpeechToTextModels.DEFAULT_MODEL_ID)),
                context.timeout(),
                TRANSCRIPTION_RESPONSE_LIMIT_BYTES
        );
        HttpExchangeResponse response = transport.send(httpRequest, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException(errorMessage(response, context));
        }
        return parseTranscript(response);
    }

    private List<SpeechToTextCatalogItem> parseModels(byte[] body) throws Exception {
        ElevenLabsSttApi.Model[] decoded;
        try {
            decoded = JSON_CODEC.read(body, ElevenLabsSttApi.Model[].class);
        } catch (Exception arrayFailure) {
            try {
                ElevenLabsSttApi.ModelsResponse envelope = JSON_CODEC.read(body, ElevenLabsSttApi.ModelsResponse.class);
                decoded = envelope.models() == null ? null : envelope.models().toArray(ElevenLabsSttApi.Model[]::new);
            } catch (Exception envelopeFailure) {
                throw new SpeechToTextException("ElevenLabs model catalog response was invalid.", envelopeFailure);
            }
        }
        if (decoded == null) {
            throw new SpeechToTextException("ElevenLabs model catalog response was invalid.");
        }
        Map<String, SpeechToTextCatalogItem> models = new LinkedHashMap<>();
        Arrays.stream(decoded).filter(java.util.Objects::nonNull).forEach(model -> addModel(models, model));
        return models.values().stream().toList();
    }

    private void addModel(Map<String, SpeechToTextCatalogItem> models, ElevenLabsSttApi.Model model) {
        String id = StringUtils.trimToEmpty(model.id());
        if (invalidModelId(id) || !supportedBatchModel(model, id)) {
            return;
        }
        String label = firstNonBlank(model.name(), model.id());
        String description = StringUtils.trimToEmpty(model.description());
        if (Strings.CS.equals(id, "scribe_v1")) {
            label = deprecatedLabel(label, id);
            description = StringUtils.defaultIfBlank(description, "Deprecated; use Scribe v2.");
        }
        models.putIfAbsent(id, new SpeechToTextCatalogItem(id, label, description));
    }

    private boolean supportedBatchModel(ElevenLabsSttApi.Model model, String id) {
        if (explicitNegativeStt(model) || Strings.CS.equals(id, "scribe_v2_realtime")) {
            return false;
        }
        if (Strings.CS.equalsAny(id, ElevenLabsSpeechToTextModels.DEFAULT_MODEL_ID, "scribe_v1")) {
            return true;
        }
        return explicitPositiveStt(model);
    }

    private boolean explicitPositiveStt(ElevenLabsSttApi.Model model) {
        return model.hasCapability(true);
    }

    private boolean explicitNegativeStt(ElevenLabsSttApi.Model model) {
        return model.hasCapability(false);
    }

    private boolean invalidModelId(String id) {
        return StringUtils.isBlank(id) || id.chars().anyMatch(Character::isISOControl);
    }

    private String deprecatedLabel(String label, String id) {
        String normalized = StringUtils.defaultIfBlank(label, id);
        return Strings.CI.contains(normalized, "deprecated") ? normalized : "%s (deprecated)".formatted(normalized);
    }

    private SpeechToTextResult parseTranscript(HttpExchangeResponse response) throws Exception {
        ElevenLabsSttApi.TranscriptionResponse body;
        try {
            body = JSON_CODEC.read(response.body(), ElevenLabsSttApi.TranscriptionResponse.class);
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
                stringPart(boundary, "model_id", modelId),
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
        return safeFileName(name);
    }

    static String safeFileName(String name) {
        String sanitized = StringUtils.defaultIfBlank(StringUtils.defaultString(name).replaceAll("[\\r\\n\"\\\\]", "_"), "recording.wav");
        return Strings.CI.endsWith(sanitized, ".wav") ? sanitized : "%s.wav".formatted(sanitized);
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

    private String errorMessage(HttpExchangeResponse response, SpeechToTextProviderContext context) {
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

    private String errorDetail(HttpExchangeResponse response, SpeechToTextProviderContext context) {
        String body = StringUtils.abbreviate(response.bodyText(), 64 * 1024);
        try {
            ElevenLabsSttApi.ErrorResponse error = JSON_CODEC.read(body, ElevenLabsSttApi.ErrorResponse.class);
            String message = firstNonBlank(
                    detailMessage(error.detail()),
                    error.message(),
                    detailMessage(error.error())
            );
            return safeDetail(StringUtils.defaultIfBlank(message, "HTTP %d".formatted(response.statusCode())), context);
        } catch (Exception e) {
            return safeDetail(StringUtils.defaultIfBlank(body, "HTTP %d".formatted(response.statusCode())), context);
        }
    }

    private String detailMessage(Object detail) {
        if (detail instanceof String text) {
            return text;
        }
        if (detail instanceof Map<?, ?> object) {
            return firstNonBlank(stringValue(object.get("message")), stringValue(object.get("msg")));
        }
        if (detail instanceof List<?> values) {
            return values.stream().map(this::detailMessage).filter(StringUtils::isNotBlank).collect(joining("; "));
        }
        return "";
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private String safeDetail(String message, SpeechToTextProviderContext context) {
        String sanitized = StringUtils.defaultString(message).replaceAll("[\\p{Cntrl}]", " ").trim();
        String apiKey = context.credentialSource().requiredApiKeyOrBlank(ENV_VAR);
        if (StringUtils.isNotBlank(apiKey)) {
            sanitized = sanitized.replace(apiKey, "****");
        }
        return StringUtils.defaultIfBlank(StringUtils.abbreviate(sanitized, ERROR_DETAIL_LIMIT), "HTTP error");
    }

    private String firstNonBlank(String... values) {
        return Arrays.stream(values)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }
}
