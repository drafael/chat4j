package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.GeneratedImageAttachmentWriter;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;

@Slf4j
public class GoogleAiGenerateContentClient implements ChatCompletionClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOOGLE_AI_PROVIDER_NAME = "Google AI";
    private static final String DEFAULT_GENERATE_CONTENT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final ChatCompletionClient fallbackClient;
    private final HttpClient httpClient;
    private final GeneratedImageAttachmentWriter generatedImageAttachmentWriter;

    public GoogleAiGenerateContentClient(ChatCompletionClient fallbackClient) {
        this(fallbackClient, HttpClient.newHttpClient(), new GeneratedImageAttachmentWriter());
    }

    GoogleAiGenerateContentClient(
            ChatCompletionClient fallbackClient,
            HttpClient httpClient,
            GeneratedImageAttachmentWriter generatedImageAttachmentWriter
    ) {
        this.fallbackClient = fallbackClient == null ? new OpenAiChatCompletionClient() : fallbackClient;
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
        this.generatedImageAttachmentWriter = generatedImageAttachmentWriter == null
                ? new GeneratedImageAttachmentWriter()
                : generatedImageAttachmentWriter;
    }

    @Override
    public void streamCompletion(
            ProviderRuntime runtime,
            List<Message> history,
            ReasoningLevel reasoningLevel,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        streamCompletion(
                runtime,
                history,
                reasoningLevel,
                WebSearchRequestOptions.disabled(),
                onToken,
                onThinkingToken,
                part -> {
                },
                citation -> {
                },
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    @Override
    public void streamCompletion(
            ProviderRuntime runtime,
            List<Message> history,
            ReasoningLevel reasoningLevel,
            WebSearchRequestOptions webSearchOptions,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<ContentPart> onPart,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        streamCompletion(
                runtime,
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onPart,
                citation -> {
                },
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    @Override
    public void streamCompletion(
            ProviderRuntime runtime,
            List<Message> history,
            ReasoningLevel reasoningLevel,
            WebSearchRequestOptions webSearchOptions,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<ContentPart> onPart,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        boolean imageOutputModel = isGoogleImageOutputModel(runtime);
        boolean nativeWebSearch = shouldUseGoogleNativeWebSearch(runtime, webSearchOptions);
        if (!imageOutputModel && !nativeWebSearch) {
            fallbackClient.streamCompletion(
                    runtime,
                    history,
                    reasoningLevel,
                    webSearchOptions,
                    onToken,
                    onThinkingToken,
                    onPart,
                    onCitation,
                    isCancelled,
                    registerActiveStream,
                    clearActiveStream
            );
            return;
        }

        HttpRequest request = HttpRequest.newBuilder(generateContentUri(runtime))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", runtime.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(history, imageOutputModel, nativeWebSearch), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (registerActiveStream != null) {
            registerActiveStream.accept(() -> future.cancel(true));
        }

        try {
            HttpResponse<String> response = future.get();
            if (isCancelled != null && isCancelled.getAsBoolean()) {
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Google AI request failed (%d): %s".formatted(response.statusCode(), errorMessage(response.body())));
            }
            try {
                JsonNode root = JSON.readTree(response.body());
                List<GoogleAiEmission> emissions = responseEmissions(root);
                emissions.forEach(emission -> emission.emit(onToken, onThinkingToken, onPart));
                if (nativeWebSearch && onCitation != null) {
                    citations(root).forEach(onCitation);
                }
            } catch (EmptyGoogleAiOutputException e) {
                if (nativeWebSearch && !imageOutputModel && e.retryableWithoutNativeWebSearch()) {
                    log.warn(
                            "Google AI native web search returned no answer for model {}; retrying without native web search: {}",
                            runtime.selectedModel(),
                            e.getMessage()
                    );
                    if (clearActiveStream != null) {
                        clearActiveStream.run();
                    }
                    fallbackClient.streamCompletion(
                            runtime,
                            history,
                            reasoningLevel,
                            WebSearchRequestOptions.disabled(),
                            onToken,
                            onThinkingToken,
                            onPart,
                            onCitation,
                            isCancelled,
                            registerActiveStream,
                            clearActiveStream
                    );
                    return;
                }
                throw e;
            }
        } finally {
            if (clearActiveStream != null) {
                clearActiveStream.run();
            }
        }
    }

    public static boolean isGoogleImageOutputModel(ProviderRuntime runtime) {
        return runtime != null
                && runtime.descriptor() != null
                && GOOGLE_AI_PROVIDER_NAME.equals(runtime.descriptor().name())
                && isImageOutputModelId(runtime.selectedModel());
    }

    private boolean shouldUseGoogleNativeWebSearch(ProviderRuntime runtime, WebSearchRequestOptions webSearchOptions) {
        return runtime != null
                && runtime.descriptor() != null
                && GOOGLE_AI_PROVIDER_NAME.equals(runtime.descriptor().name())
                && webSearchOptions != null
                && webSearchOptions.enabled()
                && supportsGoogleNativeWebSearchModel(runtime);
    }

    private boolean supportsGoogleNativeWebSearchModel(ProviderRuntime runtime) {
        return ProviderCapabilityResolver.supportsRuntimeNativeWebSearch(
                runtime.descriptor().capabilities(),
                runtime.descriptor().name(),
                runtime.selectedModel(),
                runtime.baseUrl(),
                runtime.apiKey()
        );
    }

    static boolean isImageOutputModelId(String modelId) {
        String normalized = StringUtils.defaultString(modelId).toLowerCase(Locale.ROOT);
        return StringUtils.isNotBlank(normalized)
                && (normalized.contains("nano-banana")
                || normalized.contains("image-generation")
                || normalized.endsWith("-image")
                || normalized.contains("-image-")
                || normalized.endsWith("-image-preview"));
    }

    private URI generateContentUri(ProviderRuntime runtime) {
        String baseUrl = nativeBaseUrl(runtime.baseUrl());
        String modelId = StringUtils.removeStart(StringUtils.defaultString(runtime.selectedModel()), "models/");
        String encodedModelId = URLEncoder.encode(modelId, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create("%s/models/%s:generateContent".formatted(baseUrl, encodedModelId));
    }

    private String nativeBaseUrl(String configuredBaseUrl) {
        String baseUrl = StringUtils.removeEnd(StringUtils.defaultIfBlank(configuredBaseUrl, DEFAULT_GENERATE_CONTENT_BASE_URL), "/");
        return StringUtils.removeEnd(baseUrl, "/openai");
    }

    private String requestBody(List<Message> history, boolean includeImageResponse, boolean webSearchEnabled) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode contents = JSON.createArrayNode();
        ArrayNode systemParts = JSON.createArrayNode();

        List<Message> safeHistory = history == null ? emptyList() : history;
        safeHistory.stream()
                .filter(message -> message != null && message.role() != null)
                .forEach(message -> {
                    if (message.role() == Role.SYSTEM) {
                        addMessageParts(systemParts, message.parts());
                        return;
                    }
                    ObjectNode content = JSON.createObjectNode();
                    content.put("role", message.role() == Role.ASSISTANT ? "model" : "user");
                    ArrayNode parts = JSON.createArrayNode();
                    addMessageParts(parts, message.parts());
                    if (parts.isEmpty()) {
                        parts.add(textPart(message.content()));
                    }
                    content.set("parts", parts);
                    contents.add(content);
                });

        if (!systemParts.isEmpty()) {
            ObjectNode systemInstruction = JSON.createObjectNode();
            systemInstruction.set("parts", systemParts);
            root.set("systemInstruction", systemInstruction);
        }
        root.set("contents", contents);

        if (includeImageResponse) {
            ObjectNode generationConfig = JSON.createObjectNode();
            ArrayNode responseModalities = JSON.createArrayNode();
            responseModalities.add("TEXT");
            responseModalities.add("IMAGE");
            generationConfig.set("responseModalities", responseModalities);
            root.set("generationConfig", generationConfig);
        }
        if (webSearchEnabled) {
            ArrayNode tools = JSON.createArrayNode();
            ObjectNode googleSearchTool = JSON.createObjectNode();
            googleSearchTool.set("google_search", JSON.createObjectNode());
            tools.add(googleSearchTool);
            root.set("tools", tools);
        }
        return JSON.writeValueAsString(root);
    }

    private void addMessageParts(ArrayNode parts, List<ContentPart> contentParts) {
        if (ObjectUtils.isEmpty(contentParts)) {
            return;
        }

        contentParts.stream()
                .map(this::toGooglePart)
                .filter(part -> part != null && !part.isEmpty())
                .forEach(parts::add);
    }

    private ObjectNode toGooglePart(ContentPart part) {
        if (part instanceof TextPart textPart) {
            return StringUtils.isBlank(textPart.text()) ? null : textPart(textPart.text());
        }
        if (part instanceof ImagePart imagePart) {
            return imageInlinePart(imagePart);
        }
        return textPart(ProviderAttachmentSupport.textProjection(part));
    }

    private ObjectNode imageInlinePart(ImagePart imagePart) {
        return ProviderAttachmentSupport.loadEncodedImage(imagePart)
                .map(encodedImage -> {
                    ObjectNode part = JSON.createObjectNode();
                    ObjectNode inlineData = JSON.createObjectNode();
                    inlineData.put("mime_type", encodedImage.mediaType());
                    inlineData.put("data", encodedImage.base64Data());
                    part.set("inline_data", inlineData);
                    return part;
                })
                .orElseGet(() -> textPart(ProviderAttachmentSupport.textProjection(imagePart)));
    }

    private ObjectNode textPart(String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("text", StringUtils.defaultString(text));
        return node;
    }

    private List<GoogleAiEmission> responseEmissions(JsonNode root) throws Exception {
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw emptyOutput(root, "no candidate content parts");
        }

        List<GoogleAiEmission> emissions = new ArrayList<>();
        int emittedParts = 0;
        int thinkingParts = 0;
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (part.path("thought").asBoolean(false)) {
                if (StringUtils.isNotEmpty(text)) {
                    emissions.add(GoogleAiEmission.thinking(text));
                }
                thinkingParts++;
                continue;
            }
            if (StringUtils.isNotEmpty(text)) {
                emissions.add(GoogleAiEmission.token(text));
                emittedParts++;
                continue;
            }

            JsonNode inlineData = inlineData(part);
            if (!inlineData.isMissingNode()) {
                String mimeType = StringUtils.defaultIfBlank(
                        inlineData.path("mimeType").asText(""),
                        inlineData.path("mime_type").asText("")
                );
                String data = inlineData.path("data").asText("");
                if (StringUtils.startsWith(mimeType, "image/") && StringUtils.isNotBlank(data)) {
                    emissions.add(GoogleAiEmission.part(generatedImagePart(Base64.getDecoder().decode(data), mimeType)));
                    emittedParts++;
                } else {
                    log.warn("Ignoring unsupported Google AI inline response data: {}", StringUtils.defaultIfBlank(mimeType, "unknown"));
                }
            }
        }

        if (emittedParts == 0) {
            throw emptyOutput(root, thinkingParts > 0 ? "only thinking parts, no answer text" : "no usable text or image parts");
        }

        return List.copyOf(emissions);
    }

    private EmptyGoogleAiOutputException emptyOutput(JsonNode root, String reason) {
        return new EmptyGoogleAiOutputException(
                "Google AI returned no generateContent output (%s%s).".formatted(
                        reason,
                        outputDiagnostics(root)
                ),
                canRetryWithoutNativeWebSearch(root)
        );
    }

    private String outputDiagnostics(JsonNode root) {
        List<String> diagnostics = new ArrayList<>();
        addDiagnostic(diagnostics, "promptBlockReason", root.path("promptFeedback").path("blockReason").asText(""));
        JsonNode candidate = root.path("candidates").path(0);
        addDiagnostic(diagnostics, "finishReason", candidate.path("finishReason").asText(""));
        addDiagnostic(diagnostics, "finishMessage", candidate.path("finishMessage").asText(""));
        addDiagnostic(diagnostics, "modelStatus", root.path("modelStatus").path("message").asText(""));

        JsonNode usage = root.path("usageMetadata");
        if (!usage.isMissingNode() && !usage.isEmpty()) {
            diagnostics.add("tokens prompt=%d candidates=%d thoughts=%d total=%d".formatted(
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0),
                    usage.path("thoughtsTokenCount").asInt(0),
                    usage.path("totalTokenCount").asInt(0)
            ));
        }

        return diagnostics.isEmpty() ? "" : "; %s".formatted(String.join(", ", diagnostics));
    }

    private void addDiagnostic(List<String> diagnostics, String label, String value) {
        if (StringUtils.isNotBlank(value)) {
            diagnostics.add("%s=%s".formatted(label, value));
        }
    }

    private boolean canRetryWithoutNativeWebSearch(JsonNode root) {
        if (StringUtils.isNotBlank(root.path("promptFeedback").path("blockReason").asText(""))) {
            return false;
        }

        String finishReason = root.path("candidates").path(0).path("finishReason").asText("");
        return StringUtils.isBlank(finishReason) || "STOP".equals(finishReason) || "RECITATION".equals(finishReason);
    }

    private JsonNode inlineData(JsonNode part) {
        JsonNode inlineData = part.path("inlineData");
        return inlineData.isMissingNode() ? part.path("inline_data") : inlineData;
    }

    private List<CitationRef> citations(JsonNode root) {
        JsonNode candidate = root.path("candidates").path(0);
        JsonNode groundingMetadata = candidate.path("groundingMetadata");
        JsonNode groundingChunks = groundingMetadata.path("groundingChunks");
        if (!groundingChunks.isArray() || groundingChunks.isEmpty()) {
            return emptyList();
        }

        CitationAccumulator citationAccumulator = new CitationAccumulator();
        List<CitationRef> citations = new ArrayList<>();
        for (int i = 0; i < groundingChunks.size(); i++) {
            JsonNode web = groundingChunks.path(i).path("web");
            String uri = web.path("uri").asText("");
            String title = web.path("title").asText("");
            String citedText = citedTextForGroundingChunk(groundingMetadata.path("groundingSupports"), i);
            UrlCitationMapper.fromUrl(title, uri, citedText)
                    .flatMap(citationAccumulator::addNew)
                    .ifPresent(citations::add);
        }
        return List.copyOf(citations);
    }

    private String citedTextForGroundingChunk(JsonNode groundingSupports, int chunkIndex) {
        if (!groundingSupports.isArray()) {
            return "";
        }

        for (JsonNode support : groundingSupports) {
            JsonNode indices = support.path("groundingChunkIndices");
            if (!indices.isArray() || !containsIndex(indices, chunkIndex)) {
                continue;
            }
            String segmentText = support.path("segment").path("text").asText("");
            if (StringUtils.isNotBlank(segmentText)) {
                return segmentText;
            }
        }
        return "";
    }

    private boolean containsIndex(JsonNode indices, int expectedIndex) {
        for (JsonNode index : indices) {
            if (index.asInt(-1) == expectedIndex) {
                return true;
            }
        }
        return false;
    }

    private GeneratedImagePart generatedImagePart(byte[] bytes, String mimeType) throws Exception {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("Generated image was empty.");
        }
        AttachmentRef ref = generatedImageAttachmentWriter.write(bytes, mimeType);
        ImageDimensions dimensions = imageDimensions(bytes);
        return new GeneratedImagePart(ref, dimensions.width(), dimensions.height(), "Generated image");
    }

    private ImageDimensions imageDimensions(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image == null
                    ? new ImageDimensions(null, null)
                    : new ImageDimensions(image.getWidth(), image.getHeight());
        } catch (Exception e) {
            return new ImageDimensions(null, null);
        }
    }

    private String errorMessage(String body) {
        if (StringUtils.isBlank(body)) {
            return "empty error response";
        }
        try {
            JsonNode root = JSON.readTree(body);
            String message = root.path("error").path("message").asText("");
            if (StringUtils.isNotBlank(message)) {
                return message;
            }
        } catch (Exception ignored) {
        }
        return StringUtils.abbreviate(body, 600);
    }

    private record ImageDimensions(Integer width, Integer height) {
    }

    private record GoogleAiEmission(String text, ContentPart part, boolean thinking) {
        static GoogleAiEmission token(String text) {
            return new GoogleAiEmission(text, null, false);
        }

        static GoogleAiEmission thinking(String text) {
            return new GoogleAiEmission(text, null, true);
        }

        static GoogleAiEmission part(ContentPart part) {
            return new GoogleAiEmission(null, part, false);
        }

        void emit(Consumer<String> onToken, Consumer<String> onThinkingToken, Consumer<ContentPart> onPart) {
            if (thinking) {
                if (onThinkingToken != null) {
                    onThinkingToken.accept(text);
                }
                return;
            }
            if (part != null) {
                if (onPart != null) {
                    onPart.accept(part);
                }
                return;
            }
            if (onToken != null) {
                onToken.accept(text);
            }
        }
    }

    private static class EmptyGoogleAiOutputException extends IllegalStateException {
        private final boolean retryableWithoutNativeWebSearch;

        EmptyGoogleAiOutputException(String message, boolean retryableWithoutNativeWebSearch) {
            super(message);
            this.retryableWithoutNativeWebSearch = retryableWithoutNativeWebSearch;
        }

        boolean retryableWithoutNativeWebSearch() {
            return retryableWithoutNativeWebSearch;
        }
    }
}
