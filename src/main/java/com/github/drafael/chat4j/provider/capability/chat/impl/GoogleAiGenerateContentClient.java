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
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.GeneratedImageAttachmentWriter;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
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
        if (!isGoogleImageOutputModel(runtime)) {
            fallbackClient.streamCompletion(
                    runtime,
                    history,
                    reasoningLevel,
                    webSearchOptions,
                    onToken,
                    onThinkingToken,
                    onPart,
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
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(history), StandardCharsets.UTF_8))
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
                throw new IllegalStateException("Google AI image generation failed (%d): %s".formatted(response.statusCode(), errorMessage(response.body())));
            }
            emitResponseParts(response.body(), onToken, onPart);
        } finally {
            if (clearActiveStream != null) {
                clearActiveStream.run();
            }
        }
    }

    public static boolean isGoogleImageOutputModel(ProviderRuntime runtime) {
        return runtime != null
                && GOOGLE_AI_PROVIDER_NAME.equals(runtime.descriptor().name())
                && isImageOutputModelId(runtime.selectedModel());
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

    private String requestBody(List<Message> history) throws Exception {
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

        ObjectNode generationConfig = JSON.createObjectNode();
        ArrayNode responseModalities = JSON.createArrayNode();
        responseModalities.add("TEXT");
        responseModalities.add("IMAGE");
        generationConfig.set("responseModalities", responseModalities);
        root.set("generationConfig", generationConfig);
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

    private void emitResponseParts(String body, Consumer<String> onToken, Consumer<ContentPart> onPart) throws Exception {
        JsonNode root = JSON.readTree(body);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException("Google AI returned no image generation output.");
        }

        int emittedParts = 0;
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (StringUtils.isNotEmpty(text)) {
                onToken.accept(text);
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
                    onPart.accept(generatedImagePart(Base64.getDecoder().decode(data), mimeType));
                    emittedParts++;
                } else {
                    log.warn("Ignoring unsupported Google AI inline response data: {}", StringUtils.defaultIfBlank(mimeType, "unknown"));
                }
            }
        }

        if (emittedParts == 0) {
            throw new IllegalStateException("Google AI returned no usable text or image output.");
        }
    }

    private JsonNode inlineData(JsonNode part) {
        JsonNode inlineData = part.path("inlineData");
        return inlineData.isMissingNode() ? part.path("inline_data") : inlineData;
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
}
