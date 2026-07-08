package com.github.drafael.chat4j.stt.provider.assemblyai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public class AssemblyAiSpeechToTextProvider implements SpeechToTextProvider {

    public static final String ID = "assemblyai";
    public static final String ENV_VAR = "ASSEMBLYAI_API_KEY";
    public static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;

    private static final long SMALL_RESPONSE_LIMIT_BYTES = 2L * 1024L * 1024L;
    private static final long POLL_RESPONSE_LIMIT_BYTES = 25L * 1024L * 1024L;
    private static final int ERROR_DETAIL_LIMIT = 300;
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(3);
    private static final Duration DEFAULT_SLEEP_INCREMENT = Duration.ofMillis(250);
    private static final Duration MIN_POLLING_DEADLINE = Duration.ofMinutes(2);
    private static final Duration MAX_POLLING_DEADLINE = Duration.ofMinutes(15);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SttHttpTransport transport;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration pollInterval;
    private final Duration sleepIncrement;

    public AssemblyAiSpeechToTextProvider(SttHttpTransport transport) {
        this(transport, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()), DEFAULT_POLL_INTERVAL, DEFAULT_SLEEP_INCREMENT);
    }

    AssemblyAiSpeechToTextProvider(
            SttHttpTransport transport,
            Clock clock,
            Sleeper sleeper,
            Duration pollInterval,
            Duration sleepIncrement
    ) {
        this.transport = transport;
        this.clock = clock;
        this.sleeper = sleeper;
        this.pollInterval = positiveOrDefault(pollInterval, DEFAULT_POLL_INTERVAL);
        this.sleepIncrement = positiveOrDefault(sleepIncrement, DEFAULT_SLEEP_INCREMENT);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "AssemblyAI";
    }

    @Override
    public String requiredEnvVar() {
        return ENV_VAR;
    }

    @Override
    public SpeechToTextCatalogItem defaultModel() {
        return AssemblyAiSpeechToTextModels.DEFAULT_MODEL;
    }

    @Override
    public List<SpeechToTextCatalogItem> bundledModels() {
        return AssemblyAiSpeechToTextModels.BUNDLED_MODELS;
    }

    @Override
    public SpeechToTextCatalogItem normalizeModelSelection(SpeechToTextCatalogItem model) {
        String id = model == null ? "" : model.id();
        return bundledModels().stream()
                .filter(item -> Strings.CS.equals(item.id(), id))
                .findFirst()
                .orElse(defaultModel());
    }

    @Override
    public List<SpeechToTextCatalogItem> fetchModels(SpeechToTextProviderContext context) {
        return bundledModels();
    }

    @Override
    public SpeechToTextResult transcribe(SpeechToTextRequest request, SpeechToTextProviderContext context) throws Exception {
        if (request.sizeBytes() > MAX_UPLOAD_BYTES) {
            throw new SpeechToTextException("Recording is too large to upload.");
        }
        String apiKey = context.credentialSource().resolveRequiredApiKey(ENV_VAR);
        String transcriptId = "";
        try {
            throwIfCancelled(context);
            String uploadUrl = uploadAudio(request, context, apiKey);
            throwIfCancelled(context);
            transcriptId = submitTranscript(request, context, apiKey, uploadUrl);
            throwIfCancelled(context);
            return pollTranscript(request, context, apiKey, transcriptId);
        } finally {
            cleanupTranscript(context, apiKey, transcriptId);
        }
    }

    public static boolean isBundledModelId(String modelId) {
        return AssemblyAiSpeechToTextModels.BUNDLED_MODELS.stream()
                .anyMatch(item -> Strings.CS.equals(item.id(), modelId));
    }

    static Duration pollingDeadline(long durationMillis) {
        Duration audioDuration = Duration.ofMillis(Math.max(0, durationMillis));
        Duration calculated = Duration.ofSeconds(audioDuration.toSeconds() * 3 + 60);
        if (calculated.compareTo(MIN_POLLING_DEADLINE) < 0) {
            return MIN_POLLING_DEADLINE;
        }
        if (calculated.compareTo(MAX_POLLING_DEADLINE) > 0) {
            return MAX_POLLING_DEADLINE;
        }
        return calculated;
    }

    private String uploadAudio(SpeechToTextRequest request, SpeechToTextProviderContext context, String apiKey) throws Exception {
        AssemblyAiSttEndpointResolver.Endpoint endpoint = AssemblyAiSttEndpointResolver.resolve(context.baseUri());
        SttHttpRequest httpRequest = new SttHttpRequest(
                "POST",
                endpoint.uploadUri(),
                audioHeaders(apiKey),
                HttpRequest.BodyPublishers.ofFile(request.audioFile()),
                context.timeout(),
                SMALL_RESPONSE_LIMIT_BYTES
        );
        SttHttpResponse response = transport.send(httpRequest, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException(errorMessage("upload", response, context));
        }
        return parseUploadUrl(response.body());
    }

    private String submitTranscript(SpeechToTextRequest request, SpeechToTextProviderContext context, String apiKey, String uploadUrl) throws Exception {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("audio_url", uploadUrl);
        String modelId = normalizeModelId(request.modelId());
        if (!Strings.CS.equals(modelId, AssemblyAiSpeechToTextModels.DEFAULT_MODEL_ID)) {
            json.putArray("speech_models").add(modelId);
        }
        SttHttpRequest httpRequest = new SttHttpRequest(
                "POST",
                AssemblyAiSttEndpointResolver.resolve(context.baseUri()).transcriptionUri(),
                jsonHeaders(apiKey),
                HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(json), StandardCharsets.UTF_8),
                context.timeout(),
                SMALL_RESPONSE_LIMIT_BYTES
        );
        SttHttpResponse response = transport.send(httpRequest, context.cancellationToken());
        if (!response.successful()) {
            throw new SpeechToTextException(errorMessage("transcription", response, context));
        }
        return parseTranscriptId(response.body());
    }

    private SpeechToTextResult pollTranscript(SpeechToTextRequest request, SpeechToTextProviderContext context, String apiKey, String transcriptId) throws Exception {
        Instant deadline = clock.instant().plus(pollingDeadline(request.durationMillis()));
        while (true) {
            throwIfCancelled(context);
            SttHttpResponse response = transport.send(pollRequest(context, apiKey, transcriptId), context.cancellationToken());
            throwIfCancelled(context);
            if (!response.successful()) {
                throw new SpeechToTextException(errorMessage("poll", response, context));
            }
            JsonNode json = parseJson(response.body(), "AssemblyAI transcription response was invalid.");
            String status = StringUtils.trimToEmpty(json.path("status").asText(""));
            switch (status) {
                case "completed" -> {
                    String text = StringUtils.trimToEmpty(json.path("text").asText(""));
                    if (text.isBlank()) {
                        throw new SpeechToTextException("No speech was recorded.");
                    }
                    return new SpeechToTextResult(text);
                }
                case "error" -> throw new SpeechToTextException("AssemblyAI transcription failed: %s".formatted(safeDetail(json.path("error").asText(""), context)));
                case "queued", "processing" -> waitForNextPoll(context, deadline);
                default -> throw new SpeechToTextException("AssemblyAI transcription response was invalid.");
            }
        }
    }

    private SttHttpRequest pollRequest(SpeechToTextProviderContext context, String apiKey, String transcriptId) throws SpeechToTextException {
        return new SttHttpRequest(
                "GET",
                AssemblyAiSttEndpointResolver.transcriptUri(context.baseUri(), transcriptId),
                authJsonHeaders(apiKey),
                HttpRequest.BodyPublishers.noBody(),
                context.timeout(),
                POLL_RESPONSE_LIMIT_BYTES
        );
    }

    private void waitForNextPoll(SpeechToTextProviderContext context, Instant deadline) throws SpeechToTextException {
        Instant pollAt = clock.instant().plus(pollInterval);
        while (clock.instant().isBefore(pollAt)) {
            throwIfCancelled(context);
            Duration untilPoll = Duration.between(clock.instant(), pollAt);
            Duration untilDeadline = Duration.between(clock.instant(), deadline);
            if (!untilDeadline.isPositive()) {
                throw new SpeechToTextException("AssemblyAI transcription timed out.");
            }
            Duration step = min(untilPoll, min(untilDeadline, sleepIncrement));
            try {
                sleeper.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SpeechToTextException("Transcription canceled.", e);
            }
        }
        if (!clock.instant().isBefore(deadline)) {
            throw new SpeechToTextException("AssemblyAI transcription timed out.");
        }
    }

    private void cleanupTranscript(SpeechToTextProviderContext context, String apiKey, String transcriptId) {
        if (StringUtils.isBlank(transcriptId)) {
            return;
        }
        try {
            SttHttpRequest request = new SttHttpRequest(
                    "DELETE",
                    AssemblyAiSttEndpointResolver.transcriptUri(context.baseUri(), transcriptId),
                    authJsonHeaders(apiKey),
                    HttpRequest.BodyPublishers.noBody(),
                    CLEANUP_TIMEOUT,
                    SMALL_RESPONSE_LIMIT_BYTES
            );
            transport.send(request, SpeechToTextProviderContext.CancellationToken.never());
        } catch (Exception ignored) {
        }
    }

    private String normalizeModelId(String modelId) {
        return bundledModels().stream()
                .map(SpeechToTextCatalogItem::id)
                .filter(id -> Strings.CS.equals(id, modelId))
                .findFirst()
                .orElse(AssemblyAiSpeechToTextModels.DEFAULT_MODEL_ID);
    }

    private String parseUploadUrl(byte[] body) throws SpeechToTextException {
        String uploadUrl = StringUtils.trimToEmpty(parseJson(body, "AssemblyAI upload response was invalid.").path("upload_url").asText(""));
        if (invalidUrl(uploadUrl)) {
            throw new SpeechToTextException("AssemblyAI upload response was invalid.");
        }
        return uploadUrl;
    }

    private String parseTranscriptId(byte[] body) throws SpeechToTextException {
        String id = StringUtils.trimToEmpty(parseJson(body, "AssemblyAI transcription response was invalid.").path("id").asText(""));
        if (StringUtils.isBlank(id) || id.chars().anyMatch(Character::isISOControl)) {
            throw new SpeechToTextException("AssemblyAI transcription response was invalid.");
        }
        return id;
    }

    private JsonNode parseJson(byte[] body, String invalidMessage) throws SpeechToTextException {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new SpeechToTextException(invalidMessage, e);
        }
    }

    private boolean invalidUrl(String value) {
        if (StringUtils.isBlank(value) || value.chars().anyMatch(Character::isISOControl)) {
            return true;
        }
        try {
            URI uri = URI.create(value);
            return !uri.isAbsolute() || StringUtils.isBlank(uri.getHost())
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private Map<String, String> audioHeaders(String apiKey) {
        return Map.of(
                "Authorization", apiKey,
                "Content-Type", "application/octet-stream",
                "Accept", "application/json"
        );
    }

    private Map<String, String> jsonHeaders(String apiKey) {
        return Map.of(
                "Authorization", apiKey,
                "Content-Type", "application/json",
                "Accept", "application/json"
        );
    }

    private Map<String, String> authJsonHeaders(String apiKey) {
        return Map.of(
                "Authorization", apiKey,
                "Accept", "application/json"
        );
    }

    private String errorMessage(String stage, SttHttpResponse response, SpeechToTextProviderContext context) {
        return switch (response.statusCode()) {
            case 401, 403 -> "AssemblyAI credentials were rejected.";
            case 404 -> "AssemblyAI speech-to-text endpoint or transcript job was not found.";
            case 413 -> "Recording is too large to upload.";
            case 429 -> "AssemblyAI rate limit reached. Try again later.";
            default -> response.statusCode() >= 500
                    ? "AssemblyAI speech-to-text is temporarily unavailable."
                    : "AssemblyAI %s failed: %s".formatted(stage, errorDetail(response, context));
        };
    }

    private String errorDetail(SttHttpResponse response, SpeechToTextProviderContext context) {
        String body = StringUtils.abbreviate(response.bodyText(), 64 * 1024);
        try {
            JsonNode json = OBJECT_MAPPER.readTree(body);
            String message = firstNonBlank(
                    json.path("error").path("message").asText(""),
                    json.path("error").asText(""),
                    json.path("message").asText("")
            );
            return safeDetail(StringUtils.defaultIfBlank(message, "HTTP %d".formatted(response.statusCode())), context);
        } catch (Exception e) {
            return safeDetail(StringUtils.defaultIfBlank(body, "HTTP %d".formatted(response.statusCode())), context);
        }
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

    private void throwIfCancelled(SpeechToTextProviderContext context) throws SpeechToTextException {
        if (context.cancelled()) {
            throw new SpeechToTextException("Transcription canceled.");
        }
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || !value.isPositive() ? fallback : value;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
