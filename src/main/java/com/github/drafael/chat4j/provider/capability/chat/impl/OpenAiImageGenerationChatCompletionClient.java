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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static java.util.Collections.emptyList;

@Slf4j
public class OpenAiImageGenerationChatCompletionClient implements ChatCompletionClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPENAI_PROVIDER_NAME = "OpenAI";
    private static final String OPENAI_CODEX_PROVIDER_NAME = "OpenAI Codex";
    private static final String OPENROUTER_PROVIDER_NAME = "OpenRouter";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_CONTEXT_MESSAGES = 12;
    private static final int MAX_GENERATED_IMAGES = 3;

    private static final List<String> EXCLUDED_INTENT_PHRASES = List.of(
            "mermaid",
            "diagram",
            "flowchart",
            "plantuml",
            "graphviz",
            " dot ",
            "latex",
            " la tex",
            "tex equation",
            "math formula",
            "equation",
            "formula",
            "smiles",
            "chemical formula",
            "molecule notation",
            "svg code",
            "html",
            "css",
            "ascii diagram"
    );
    private static final Pattern MERMAID_FENCE_PATTERN = Pattern.compile("```\\s*mermaid", Pattern.CASE_INSENSITIVE);
    private static final Pattern LATEX_PATTERN = Pattern.compile("(\\$[^\\n$]+\\$|\\\\\\[[\\s\\S]*?\\\\]|\\\\begin\\{)", Pattern.CASE_INSENSITIVE);
    private static final Pattern POSITIVE_IMAGE_INTENT_PATTERN = Pattern.compile(
            "\\b(draw|generate|create|make|paint|illustrate)\\b.*\\b(image|picture|photo|portrait|poster|icon|logo|wallpaper|scene|illustration|artwork|sketch)\\b"
                    + "|^\\s*(please\\s+)?\\b(draw|paint|sketch)\\b\\s+.+"
                    + "|\\b(render)\\b.*\\b(image|picture|photo|scene|portrait|artwork)\\b"
                    + "|\\b(edit|modify|retouch|replace|remove|add|change)\\b.*\\b(image|picture|photo|background|foreground|object|style|color|lighting)\\b"
                    + "|\\bturn\\b.*\\b(photo|image|picture)\\b.*\\binto\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IMAGE_CONTEXT_EDIT_PATTERN = Pattern.compile(
            "\\b(make it|make this|turn it|turn this|change it|change this|edit it|edit this|modify it|modify this)\\b.*"
                    + "\\b(watercolor|realistic|anime|cartoon|cinematic|brighter|darker|background|style|color|lighting|larger|smaller)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final ChatCompletionClient fallbackClient;
    private final HttpClient httpClient;
    private final GeneratedImageAttachmentWriter generatedImageAttachmentWriter;

    public OpenAiImageGenerationChatCompletionClient(ChatCompletionClient fallbackClient) {
        this(fallbackClient, HttpClient.newHttpClient(), new GeneratedImageAttachmentWriter());
    }

    OpenAiImageGenerationChatCompletionClient(
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
        List<Message> safeHistory = history == null ? emptyList() : history;
        if (isOpenRouterImageGenerationRequest(runtime, safeHistory)) {
            generateWithOpenRouterChatCompletions(runtime, safeHistory, onToken, onPart, isCancelled, registerActiveStream, clearActiveStream);
            return;
        }

        if (isOpenAiImageModel(runtime)) {
            generateWithImagesApi(runtime, safeHistory, onPart, isCancelled, registerActiveStream, clearActiveStream);
            return;
        }

        if (supportsOpenAiImageGeneration(runtime) && hasImageIntent(safeHistory)) {
            try {
                generateWithResponsesImageTool(
                        runtime,
                        safeHistory,
                        onToken,
                        onPart,
                        isCancelled,
                        registerActiveStream,
                        clearActiveStream
                );
                return;
            } catch (Exception e) {
                if (!isUnsupportedImageGeneration(e)) {
                    throw e;
                }
                log.info("Falling back to normal OpenAI chat after image_generation tool failure for model {}: {}",
                        runtime.selectedModel(), e.getMessage());
            }
        }

        fallbackClient.streamCompletion(
                runtime,
                safeHistory,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onPart,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    public static boolean isOpenAiImageModel(ProviderRuntime runtime) {
        return supportsOpenAiImageGeneration(runtime) && isImageModelId(runtime.selectedModel());
    }

    static boolean isImageModelId(String modelId) {
        String normalized = StringUtils.defaultString(modelId).toLowerCase(Locale.ROOT);
        return StringUtils.isNotBlank(normalized)
                && (normalized.startsWith("gpt-image-") || "chatgpt-image-latest".equals(normalized));
    }

    static boolean isOpenRouterImageOutputModelId(String modelId) {
        String normalized = StringUtils.defaultString(modelId).toLowerCase(Locale.ROOT);
        return StringUtils.isNotBlank(normalized)
                && ((normalized.contains("/gpt-") && normalized.contains("image"))
                || (normalized.contains("/gemini-") && normalized.contains("image"))
                || normalized.contains("/flux")
                || normalized.contains("/riverflow")
                || normalized.contains("/recraft")
                || normalized.contains("/mai-image")
                || normalized.contains("image-preview"));
    }

    static boolean hasImageIntent(List<Message> history) {
        Message latestUserMessage = latestUserMessage(history).orElse(null);
        if (latestUserMessage == null) {
            return false;
        }

        String text = latestUserText(latestUserMessage);
        if (StringUtils.isBlank(text)) {
            return false;
        }

        String normalized = normalizeIntentText(text);
        if (isRenderableSourceRequest(normalized, text)) {
            return false;
        }

        if (POSITIVE_IMAGE_INTENT_PATTERN.matcher(text).find()) {
            return true;
        }

        return hasRecentImageContext(history) && IMAGE_CONTEXT_EDIT_PATTERN.matcher(text).find();
    }

    private void generateWithImagesApi(
            ProviderRuntime runtime,
            List<Message> history,
            Consumer<ContentPart> onPart,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        String prompt = latestUserMessage(history)
                .map(OpenAiImageGenerationChatCompletionClient::latestUserText)
                .filter(StringUtils::isNotBlank)
                .orElse("Generate an image.");

        ObjectNode root = JSON.createObjectNode();
        root.put("model", runtime.selectedModel());
        root.put("prompt", prompt);

        HttpResponse<String> response = sendJson(runtime, "/images/generations", root, isCancelled, registerActiveStream, clearActiveStream);
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI image generation failed (%d): %s".formatted(response.statusCode(), errorMessage(response.body())));
        }
        emitImagesApiResponse(response.body(), onPart);
    }

    private void generateWithResponsesImageTool(
            ProviderRuntime runtime,
            List<Message> history,
            Consumer<String> onToken,
            Consumer<ContentPart> onPart,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", runtime.selectedModel());
        root.set("input", responsesInput(history));
        ArrayNode tools = JSON.createArrayNode();
        ObjectNode imageGenerationTool = JSON.createObjectNode();
        imageGenerationTool.put("type", "image_generation");
        imageGenerationTool.put("action", "auto");
        tools.add(imageGenerationTool);
        root.set("tools", tools);

        HttpResponse<String> response = sendJson(runtime, "/responses", root, isCancelled, registerActiveStream, clearActiveStream);
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI image generation tool failed (%d): %s".formatted(response.statusCode(), errorMessage(response.body())));
        }
        emitResponsesImageToolResponse(response.body(), onToken, onPart);
    }

    private void generateWithOpenRouterChatCompletions(
            ProviderRuntime runtime,
            List<Message> history,
            Consumer<String> onToken,
            Consumer<ContentPart> onPart,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", runtime.selectedModel());
        root.set("messages", openRouterMessages(history));
        boolean imageOutputModel = isOpenRouterImageOutputModelId(runtime.selectedModel());
        if (imageOutputModel) {
            root.set("modalities", openRouterModalities(runtime.selectedModel()));
        } else {
            ArrayNode tools = JSON.createArrayNode();
            ObjectNode imageGenerationTool = JSON.createObjectNode();
            imageGenerationTool.put("type", "openrouter:image_generation");
            tools.add(imageGenerationTool);
            root.set("tools", tools);
            root.put("tool_choice", "auto");
        }

        HttpResponse<String> response = sendJson(runtime, "/chat/completions", root, isCancelled, registerActiveStream, clearActiveStream);
        if (isCancelled != null && isCancelled.getAsBoolean()) {
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenRouter image generation failed (%d): %s".formatted(response.statusCode(), errorMessage(response.body())));
        }

        emitOpenRouterChatCompletionImages(response.body(), onToken, onPart, imageOutputModel);
    }

    private ArrayNode openRouterModalities(String modelId) {
        ArrayNode modalities = JSON.createArrayNode();
        modalities.add("image");
        if (isOpenRouterTextAndImageOutputModelId(modelId)) {
            modalities.add("text");
        }
        return modalities;
    }

    private boolean isOpenRouterTextAndImageOutputModelId(String modelId) {
        String normalized = StringUtils.defaultString(modelId).toLowerCase(Locale.ROOT);
        return normalized.contains("/gpt-") || normalized.contains("/gemini-") || normalized.contains("image-preview");
    }

    private HttpResponse<String> sendJson(
            ProviderRuntime runtime,
            String endpoint,
            JsonNode body,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpointUri(runtime, endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer %s".formatted(runtime.apiKey()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (registerActiveStream != null) {
            registerActiveStream.accept(() -> future.cancel(true));
        }

        try {
            if (isCancelled != null && isCancelled.getAsBoolean()) {
                future.cancel(true);
            }
            return future.get();
        } finally {
            if (clearActiveStream != null) {
                clearActiveStream.run();
            }
        }
    }

    private URI endpointUri(ProviderRuntime runtime, String endpoint) {
        String baseUrl = StringUtils.removeEnd(StringUtils.defaultString(runtime.baseUrl()), "/");
        return URI.create("%s%s".formatted(baseUrl, endpoint));
    }

    private void emitImagesApiResponse(String body, Consumer<ContentPart> onPart) throws Exception {
        JsonNode data = JSON.readTree(body).path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("OpenAI returned no generated image.");
        }

        int emittedImages = 0;
        for (JsonNode image : data) {
            String b64Json = image.path("b64_json").asText("");
            if (StringUtils.isBlank(b64Json)) {
                continue;
            }
            onPart.accept(generatedImagePart(Base64.getDecoder().decode(b64Json), "image/png"));
            emittedImages++;
        }

        if (emittedImages == 0) {
            throw new IllegalStateException("OpenAI returned no generated image.");
        }
    }

    private void emitOpenRouterChatCompletionImages(
            String body,
            Consumer<String> onToken,
            Consumer<ContentPart> onPart,
            boolean imageRequired
    ) throws Exception {
        JsonNode choices = JSON.readTree(body).path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("OpenRouter returned no image generation output.");
        }

        List<String> textParts = new ArrayList<>();
        List<GeneratedImagePart> imageParts = new ArrayList<>();
        for (JsonNode choice : choices) {
            JsonNode message = choice.path("message");
            textParts.addAll(openRouterMessageText(message.path("content")));
            imageParts.addAll(openRouterMessageImages(message));
        }

        if (imageRequired && imageParts.isEmpty()) {
            throw new IllegalStateException("OpenRouter returned text but no generated image. Ensure the selected model supports image output or the image generation server tool is available.");
        }

        if (!imageRequired && imageParts.isEmpty() && !textParts.isEmpty()) {
            onToken.accept("OpenRouter image generation tool was not called; showing the model response instead.\n\n");
        }
        textParts.forEach(onToken);
        imageParts.forEach(onPart);

        if (textParts.isEmpty() && imageParts.isEmpty()) {
            throw new IllegalStateException("OpenRouter returned no usable text or image output. Ensure the model supports image output and modalities include image.");
        }
    }

    private List<String> openRouterMessageText(JsonNode content) {
        if (content.isTextual()) {
            String text = content.asText("");
            return StringUtils.isEmpty(text) ? emptyList() : List.of(text);
        }

        if (!content.isArray()) {
            return emptyList();
        }

        return StreamSupport.stream(content.spliterator(), false)
                .map(contentItem -> contentItem.path("text").asText(""))
                .filter(StringUtils::isNotEmpty)
                .toList();
    }

    private List<GeneratedImagePart> openRouterMessageImages(JsonNode message) throws Exception {
        if (!message.path("images").isArray()) {
            return emptyList();
        }

        List<GeneratedImagePart> imageParts = new ArrayList<>();
        for (JsonNode image : message.path("images")) {
            String dataUrl = image.path("image_url").path("url").asText("");
            if (StringUtils.isBlank(dataUrl)) {
                dataUrl = image.path("url").asText("");
            }
            Optional<DecodedImage> decodedImage = decodeDataUrlImage(dataUrl);
            if (decodedImage.isPresent()) {
                imageParts.add(generatedImagePart(decodedImage.get().bytes(), decodedImage.get().mimeType()));
            }
        }
        return imageParts;
    }

    private void emitResponsesImageToolResponse(
            String body,
            Consumer<String> onToken,
            Consumer<ContentPart> onPart
    ) throws Exception {
        JsonNode output = JSON.readTree(body).path("output");
        if (!output.isArray() || output.isEmpty()) {
            throw new IllegalStateException("OpenAI returned no image generation output.");
        }

        int emittedParts = 0;
        for (JsonNode outputItem : output) {
            String type = outputItem.path("type").asText("");
            if ("image_generation_call".equals(type)) {
                String result = outputItem.path("result").asText("");
                if (StringUtils.isNotBlank(result)) {
                    onPart.accept(generatedImagePart(Base64.getDecoder().decode(result), "image/png"));
                    emittedParts++;
                }
                continue;
            }

            if ("message".equals(type)) {
                emittedParts += emitOutputMessageText(outputItem, onToken);
            }
        }

        if (emittedParts == 0) {
            throw new IllegalStateException("OpenAI returned no usable text or image output.");
        }
    }

    private int emitOutputMessageText(JsonNode outputItem, Consumer<String> onToken) {
        int emittedText = 0;
        JsonNode content = outputItem.path("content");
        if (!content.isArray()) {
            return 0;
        }

        for (JsonNode contentItem : content) {
            String text = StringUtils.defaultIfBlank(
                    contentItem.path("text").asText(""),
                    contentItem.path("output_text").asText("")
            );
            if (StringUtils.isNotEmpty(text)) {
                onToken.accept(text);
                emittedText++;
            }
        }
        return emittedText;
    }

    private ArrayNode openRouterMessages(List<Message> history) {
        ArrayNode messages = JSON.createArrayNode();
        List<Message> relevantMessages = boundedHistory(history);
        Set<String> allowedGeneratedImageStoragePaths = recentGeneratedImageStoragePaths(relevantMessages);
        relevantMessages.stream()
                .map(message -> openRouterMessage(message, allowedGeneratedImageStoragePaths))
                .filter(message -> message != null && !message.path("content").isMissingNode())
                .forEach(messages::add);
        return messages;
    }

    private ObjectNode openRouterMessage(Message message, Set<String> allowedGeneratedImageStoragePaths) {
        if (message == null || message.role() == null) {
            return null;
        }

        ObjectNode node = JSON.createObjectNode();
        node.put("role", chatCompletionRole(message.role()));
        ArrayNode contentParts = JSON.createArrayNode();
        addOpenRouterContentParts(contentParts, message, allowedGeneratedImageStoragePaths);
        if (contentParts.isEmpty()) {
            node.put("content", StringUtils.defaultString(message.content()));
        } else if (contentParts.size() == 1 && "text".equals(contentParts.get(0).path("type").asText(""))) {
            node.put("content", contentParts.get(0).path("text").asText(""));
        } else {
            node.set("content", contentParts);
        }
        return node;
    }

    private void addOpenRouterContentParts(ArrayNode content, Message message, Set<String> allowedGeneratedImageStoragePaths) {
        if (ObjectUtils.isEmpty(message.parts())) {
            return;
        }

        message.parts().forEach(part -> addOpenRouterContentPart(content, part, allowedGeneratedImageStoragePaths));
    }

    private void addOpenRouterContentPart(ArrayNode content, ContentPart part, Set<String> allowedGeneratedImageStoragePaths) {
        if (part instanceof TextPart textPart && StringUtils.isNotBlank(textPart.text())) {
            content.add(chatTextPart(textPart.text()));
            return;
        }

        if (part instanceof ImagePart imagePart) {
            ProviderAttachmentSupport.loadEncodedImage(imagePart)
                    .map(encodedImage -> dataUrl(encodedImage.mediaType(), encodedImage.base64Data()))
                    .map(this::chatImagePart)
                    .ifPresentOrElse(content::add, () -> content.add(chatTextPart(ProviderAttachmentSupport.textProjection(imagePart))));
            return;
        }

        if (part instanceof GeneratedImagePart generatedImagePart) {
            if (!allowedGeneratedImageStoragePaths.contains(generatedImagePart.attachmentRef().storagePath())) {
                return;
            }
            encodedAttachment(generatedImagePart.attachmentRef())
                    .map(encodedImage -> dataUrl(encodedImage.mediaType(), encodedImage.base64Data()))
                    .map(this::chatImagePart)
                    .ifPresentOrElse(content::add, () -> log.debug("Skipping missing OpenRouter generated image attachment: {}",
                            generatedImagePart.attachmentRef().storagePath()));
            return;
        }

        String projection = ProviderAttachmentSupport.textProjection(part);
        if (StringUtils.isNotBlank(projection)) {
            content.add(chatTextPart(projection));
        }
    }

    private ObjectNode chatTextPart(String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "text");
        node.put("text", StringUtils.defaultString(text));
        return node;
    }

    private ObjectNode chatImagePart(String dataUrl) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "image_url");
        ObjectNode imageUrl = JSON.createObjectNode();
        imageUrl.put("url", dataUrl);
        node.set("image_url", imageUrl);
        return node;
    }

    private ArrayNode responsesInput(List<Message> history) {
        ArrayNode input = JSON.createArrayNode();
        List<Message> relevantMessages = boundedHistory(history);
        Set<String> allowedGeneratedImageStoragePaths = recentGeneratedImageStoragePaths(relevantMessages);
        relevantMessages.stream()
                .map(message -> responseInputMessage(message, allowedGeneratedImageStoragePaths))
                .filter(message -> message != null && message.path("content").isArray() && !message.path("content").isEmpty())
                .forEach(input::add);
        return input;
    }

    private List<Message> boundedHistory(List<Message> history) {
        if (ObjectUtils.isEmpty(history)) {
            return emptyList();
        }

        int fromIndex = Math.max(0, history.size() - MAX_CONTEXT_MESSAGES);
        return history.subList(fromIndex, history.size());
    }

    private ObjectNode responseInputMessage(Message message, Set<String> allowedGeneratedImageStoragePaths) {
        if (message == null || message.role() == null) {
            return null;
        }

        ObjectNode inputMessage = JSON.createObjectNode();
        inputMessage.put("role", responseInputRole(message.role()));
        ArrayNode content = JSON.createArrayNode();
        addResponseContentParts(content, message, allowedGeneratedImageStoragePaths);
        if (content.isEmpty()) {
            String text = StringUtils.trimToEmpty(message.content());
            if (StringUtils.isNotBlank(text)) {
                content.add(inputText(responseRoleLabel(message.role(), text)));
            }
        }
        inputMessage.set("content", content);
        return inputMessage;
    }

    private void addResponseContentParts(ArrayNode content, Message message, Set<String> allowedGeneratedImageStoragePaths) {
        if (ObjectUtils.isEmpty(message.parts())) {
            return;
        }

        message.parts().forEach(part -> addResponseContentPart(content, message.role(), part, allowedGeneratedImageStoragePaths));
    }

    private void addResponseContentPart(
            ArrayNode content,
            Role role,
            ContentPart part,
            Set<String> allowedGeneratedImageStoragePaths
    ) {
        if (part instanceof TextPart textPart && StringUtils.isNotBlank(textPart.text())) {
            content.add(inputText(responseRoleLabel(role, textPart.text())));
            return;
        }

        if (part instanceof ImagePart imagePart) {
            ProviderAttachmentSupport.loadEncodedImage(imagePart)
                    .map(encodedImage -> dataUrl(encodedImage.mediaType(), encodedImage.base64Data()))
                    .map(this::inputImage)
                    .ifPresentOrElse(content::add, () -> content.add(inputText(ProviderAttachmentSupport.textProjection(imagePart))));
            return;
        }

        if (part instanceof GeneratedImagePart generatedImagePart) {
            if (!allowedGeneratedImageStoragePaths.contains(generatedImagePart.attachmentRef().storagePath())) {
                return;
            }
            Optional<ProviderAttachmentSupport.EncodedImage> encodedImage = encodedAttachment(generatedImagePart.attachmentRef());
            if (encodedImage.isEmpty()) {
                log.debug("Skipping missing generated image attachment: {}", generatedImagePart.attachmentRef().storagePath());
                return;
            }
            content.add(inputImage(dataUrl(encodedImage.get().mediaType(), encodedImage.get().base64Data())));
            return;
        }

        String projection = ProviderAttachmentSupport.textProjection(part);
        if (StringUtils.isNotBlank(projection)) {
            content.add(inputText(responseRoleLabel(role, projection)));
        }
    }

    private ObjectNode inputText(String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "input_text");
        node.put("text", StringUtils.defaultString(text));
        return node;
    }

    private ObjectNode inputImage(String dataUrl) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", "input_image");
        node.put("image_url", dataUrl);
        node.put("detail", "auto");
        return node;
    }

    private Set<String> recentGeneratedImageStoragePaths(List<Message> messages) {
        LinkedHashSet<String> storagePaths = new LinkedHashSet<>();
        for (int messageIndex = messages.size() - 1; messageIndex >= 0 && storagePaths.size() < MAX_GENERATED_IMAGES; messageIndex--) {
            Message message = messages.get(messageIndex);
            if (message == null || ObjectUtils.isEmpty(message.parts())) {
                continue;
            }
            List<ContentPart> parts = message.parts();
            for (int partIndex = parts.size() - 1; partIndex >= 0 && storagePaths.size() < MAX_GENERATED_IMAGES; partIndex--) {
                if (parts.get(partIndex) instanceof GeneratedImagePart generatedImagePart) {
                    generatedImagePath(generatedImagePart.attachmentRef())
                            .map(Path::toString)
                            .ifPresent(storagePaths::add);
                }
            }
        }
        return storagePaths;
    }

    private Optional<ProviderAttachmentSupport.EncodedImage> encodedAttachment(AttachmentRef attachmentRef) {
        Optional<Path> attachmentPath = generatedImagePath(attachmentRef);
        if (attachmentPath.isEmpty()) {
            return Optional.empty();
        }

        Path filePath = attachmentPath.get();
        String storagePath = attachmentRef == null ? null : attachmentRef.storagePath();
        if (StringUtils.isBlank(storagePath)) {
            return Optional.empty();
        }

        try {
            String mediaType = attachmentRef.mimeType();
            if (StringUtils.isBlank(mediaType)) {
                mediaType = Files.probeContentType(filePath);
            }
            if (StringUtils.isBlank(mediaType)) {
                mediaType = "image/png";
            }
            String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(filePath));
            return Optional.of(new ProviderAttachmentSupport.EncodedImage(mediaType, encoded));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<Path> generatedImagePath(AttachmentRef attachmentRef) {
        String storagePath = attachmentRef == null ? null : attachmentRef.storagePath();
        if (StringUtils.isBlank(storagePath)) {
            return Optional.empty();
        }

        try {
            Path filePath = Path.of(storagePath);
            return Files.isRegularFile(filePath) ? Optional.of(filePath) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String dataUrl(String mediaType, String base64Data) {
        return "data:%s;base64,%s".formatted(StringUtils.defaultIfBlank(mediaType, "image/png"), base64Data);
    }

    private Optional<DecodedImage> decodeDataUrlImage(String dataUrl) {
        if (StringUtils.isBlank(dataUrl) || !dataUrl.startsWith("data:image/")) {
            return Optional.empty();
        }

        int commaIndex = dataUrl.indexOf(',');
        int semicolonIndex = dataUrl.indexOf(';');
        if (commaIndex < 0 || semicolonIndex < 0 || semicolonIndex > commaIndex) {
            return Optional.empty();
        }

        String mimeType = dataUrl.substring("data:".length(), semicolonIndex);
        String encoded = dataUrl.substring(commaIndex + 1);
        try {
            return Optional.of(new DecodedImage(Base64.getDecoder().decode(encoded), mimeType));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String responseInputRole(Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER, ASSISTANT -> "user";
        };
    }

    private String chatCompletionRole(Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case USER -> "user";
        };
    }

    private String responseRoleLabel(Role role, String text) {
        if (role == Role.ASSISTANT) {
            return "Assistant: %s".formatted(text);
        }
        if (role == Role.SYSTEM) {
            return text;
        }
        return text;
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

    private boolean isUnsupportedImageGeneration(Exception exception) {
        String message = StringUtils.defaultString(exception.getMessage()).toLowerCase(Locale.ROOT);
        Throwable cause = exception.getCause();
        while (cause != null) {
            message = "%s | %s".formatted(message, StringUtils.defaultString(cause.getMessage()).toLowerCase(Locale.ROOT));
            cause = cause.getCause();
        }
        boolean mentionsImageGeneration = message.contains("image_generation") || message.contains("image generation");
        return mentionsImageGeneration
                && (message.contains("unsupported")
                || message.contains("not supported")
                || message.contains("unknown tool")
                || message.contains("invalid tool")
                || message.contains("not accessible")
                || message.contains("insufficient permissions")
                || message.contains("missing scopes")
                || message.contains("api.responses.write"));
    }

    private static Optional<Message> latestUserMessage(List<Message> history) {
        if (ObjectUtils.isEmpty(history)) {
            return Optional.empty();
        }

        for (int index = history.size() - 1; index >= 0; index--) {
            Message message = history.get(index);
            if (message != null && message.role() == Role.USER) {
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    private static String latestUserText(Message message) {
        if (ObjectUtils.isEmpty(message.parts())) {
            return StringUtils.defaultString(message.content());
        }

        String textParts = message.parts().stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .filter(StringUtils::isNotBlank)
                .reduce("", (left, right) -> left.isBlank() ? right : "%s\n%s".formatted(left, right));
        return StringUtils.defaultIfBlank(textParts, message.content());
    }

    private static String normalizeIntentText(String text) {
        return " %s ".formatted(StringUtils.defaultString(text).toLowerCase(Locale.ROOT)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " "));
    }

    private static boolean isRenderableSourceRequest(String normalizedText, String originalText) {
        if (MERMAID_FENCE_PATTERN.matcher(originalText).find() || LATEX_PATTERN.matcher(originalText).find()) {
            return true;
        }

        return EXCLUDED_INTENT_PHRASES.stream().anyMatch(normalizedText::contains);
    }

    private static boolean hasRecentImageContext(List<Message> history) {
        if (ObjectUtils.isEmpty(history)) {
            return false;
        }

        return history.stream()
                .skip(Math.max(0, history.size() - MAX_CONTEXT_MESSAGES))
                .flatMap(message -> message.parts().stream())
                .anyMatch(part -> part instanceof ImagePart || part instanceof GeneratedImagePart);
    }

    private static boolean supportsOpenAiImageGeneration(ProviderRuntime runtime) {
        if (runtime == null || runtime.descriptor() == null) {
            return false;
        }

        String providerName = runtime.descriptor().name();
        return OPENAI_PROVIDER_NAME.equals(providerName) || OPENAI_CODEX_PROVIDER_NAME.equals(providerName);
    }

    private static boolean isOpenRouterImageGenerationRequest(ProviderRuntime runtime, List<Message> history) {
        return runtime != null
                && runtime.descriptor() != null
                && OPENROUTER_PROVIDER_NAME.equals(runtime.descriptor().name())
                && (isOpenRouterImageOutputModelId(runtime.selectedModel()) || hasImageIntent(history));
    }

    private record ImageDimensions(Integer width, Integer height) {
    }

    private record DecodedImage(byte[] bytes, String mimeType) {
    }

}
