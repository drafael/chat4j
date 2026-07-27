package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.chat.render.BoundedUtf8;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.NativeImage;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedPart;
import com.github.drafael.chat4j.provider.support.GeneratedImageAttachmentWriter;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toCollection;

@Slf4j
public class GoogleAiGenerateContentClient implements ChatCompletionClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GOOGLE_AI_PROVIDER_NAME = "Google AI";
    private static final String DEFAULT_GENERATE_CONTENT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final long MAX_RESPONSE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_GENERATED_IMAGES = 4;
    private static final String UNSUPPORTED_INLINE_DATA_WARNING = "[Google AI returned unsupported inline data.]";
    private static final long MAX_GENERATED_IMAGE_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 16_384;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;

    private final ChatCompletionClient fallbackClient;
    private final HttpClient httpClient;
    private final GeneratedImageAttachmentWriter generatedImageAttachmentWriter;
    private final ProviderAttachmentSupport attachmentSupport;

    public GoogleAiGenerateContentClient(
            @NonNull ChatCompletionClient fallbackClient,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull GeneratedImageAttachmentWriter generatedImageAttachmentWriter
    ) {
        this(fallbackClient, HttpClient.newHttpClient(), attachmentSupport, generatedImageAttachmentWriter);
    }

    GoogleAiGenerateContentClient(
            @NonNull ChatCompletionClient fallbackClient,
            @NonNull HttpClient httpClient,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull GeneratedImageAttachmentWriter generatedImageAttachmentWriter
    ) {
        this.fallbackClient = fallbackClient;
        this.httpClient = httpClient;
        this.attachmentSupport = attachmentSupport;
        this.generatedImageAttachmentWriter = generatedImageAttachmentWriter;
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
                null,
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

        AttachmentProjectionPlan projectionPlan = AttachmentProjectionPlan.create(
                history,
                attachmentSupport,
                AttachmentProjectionPlan.google(),
                () -> shouldStop(isCancelled)
        );
        if (shouldStop(isCancelled)) {
            return;
        }
        streamNativeCompletion(
                runtime,
                projectionPlan,
                imageOutputModel,
                nativeWebSearch,
                onToken,
                onThinkingToken,
                onPart,
                onCitation,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    private void streamNativeCompletion(
            ProviderRuntime runtime,
            AttachmentProjectionPlan projectionPlan,
            boolean imageOutputModel,
            boolean nativeWebSearch,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<ContentPart> onPart,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        if (shouldStop(isCancelled)) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(generateContentUri(runtime))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", runtime.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody(projectionPlan, imageOutputModel, nativeWebSearch),
                        StandardCharsets.UTF_8
                ))
                .build();
        if (shouldStop(isCancelled)) {
            return;
        }
        CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(request, boundedBodyHandler());
        if (registerActiveStream != null) {
            try {
                registerActiveStream.accept(() -> future.cancel(true));
            } catch (RuntimeException e) {
                future.cancel(true);
                throw e;
            }
        }

        try {
            HttpResponse<byte[]> response = waitForResponse(future, isCancelled);
            if (shouldStop(isCancelled)) {
                return;
            }
            String responseBody = decodeResponseBody(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Google AI request failed (%d): %s".formatted(
                        response.statusCode(),
                        errorMessage(responseBody)
                ));
            }
            try {
                JsonNode root = JSON.readTree(responseBody);
                List<GoogleAiEmission> emissions = materializeGeneratedImages(
                        responseEmissions(root, isCancelled),
                        isCancelled
                );
                if (emissions.isEmpty() && shouldStop(isCancelled)) {
                    return;
                }
                emitValidatedParts(emissions, onToken, onThinkingToken, onPart, isCancelled);
                if (nativeWebSearch && onCitation != null) {
                    for (CitationRef citation : citations(root)) {
                        if (shouldStop(isCancelled)) {
                            return;
                        }
                        onCitation.accept(citation);
                    }
                }
            } catch (EmptyGoogleAiOutputException e) {
                if (nativeWebSearch && !imageOutputModel && e.retryableWithoutNativeWebSearch()) {
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    log.warn(
                            "Google AI native web search returned no answer for model {}; retrying without native web search: {}",
                            runtime.selectedModel(),
                            ProviderExceptionMapper.sanitizeMessage(e, runtime.apiKey())
                    );
                    streamNativeCompletion(
                            runtime,
                            projectionPlan,
                            false,
                            false,
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
            if (!future.isDone()) {
                future.cancel(true);
            }
            if (clearActiveStream != null) {
                clearActiveStream.run();
            }
        }
    }

    private HttpResponse<byte[]> waitForResponse(
            CompletableFuture<HttpResponse<byte[]>> future,
            BooleanSupplier isCancelled
    ) throws Exception {
        boolean logicalCancellation = false;
        try {
            while (true) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    logicalCancellation = true;
                    future.cancel(true);
                    throw new InterruptedException("Google AI request cancelled");
                }
                if (Thread.currentThread().isInterrupted()) {
                    future.cancel(true);
                    throw new InterruptedException("Google AI request interrupted");
                }
                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Poll logical cancellation while the HTTP request is in flight.
                }
            }
        } catch (InterruptedException e) {
            future.cancel(true);
            if (!logicalCancellation) {
                Thread.currentThread().interrupt();
            }
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private HttpResponse.BodyHandler<byte[]> boundedBodyHandler() {
        return responseInfo -> {
            long declaredLength = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new BoundedBodySubscriber(declaredLength, MAX_RESPONSE_BYTES);
        };
    }

    private String decodeResponseBody(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
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
        String modelId = Strings.CS.removeStart(StringUtils.defaultString(runtime.selectedModel()), "models/");
        String encodedModelId = URLEncoder.encode(modelId, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create("%s/models/%s:generateContent".formatted(baseUrl, encodedModelId));
    }

    private String nativeBaseUrl(String configuredBaseUrl) {
        String baseUrl = Strings.CS.removeEnd(
                StringUtils.defaultIfBlank(configuredBaseUrl, DEFAULT_GENERATE_CONTENT_BASE_URL),
                "/"
        );
        return Strings.CS.removeEnd(baseUrl, "/openai");
    }

    private String requestBody(
            AttachmentProjectionPlan projectionPlan,
            boolean includeImageResponse,
            boolean webSearchEnabled
    ) throws Exception {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode contents = JSON.createArrayNode();
        ArrayNode systemParts = JSON.createArrayNode();

        projectionPlan.messages().forEach(message -> {
            if (message.role() == Role.SYSTEM) {
                addMessageParts(systemParts, message);
                return;
            }
            ObjectNode content = JSON.createObjectNode();
            content.put("role", message.role() == Role.ASSISTANT ? "model" : "user");
            ArrayNode parts = JSON.createArrayNode();
            addMessageParts(parts, message);
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

    private void addMessageParts(ArrayNode parts, ProjectedMessage message) {
        message.parts().stream()
                .map(this::toGooglePart)
                .filter(part -> part != null && !part.isEmpty())
                .forEach(parts::add);
    }

    private ObjectNode toGooglePart(ProjectedPart part) {
        if (part instanceof NativeImage image) {
            ObjectNode node = JSON.createObjectNode();
            ObjectNode inlineData = JSON.createObjectNode();
            inlineData.put("mime_type", image.mediaType());
            inlineData.put("data", image.base64Data());
            node.set("inline_data", inlineData);
            return node;
        }
        String text = AttachmentProjectionPlan.textFallback(part);
        return StringUtils.isBlank(text) ? null : textPart(text);
    }

    private ObjectNode textPart(String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("text", StringUtils.defaultString(text));
        return node;
    }

    private List<GoogleAiEmission> responseEmissions(JsonNode root, BooleanSupplier isCancelled) throws Exception {
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw emptyOutput(root, "no candidate content parts");
        }

        List<GoogleAiEmission> emissions = new ArrayList<>();
        int emittedParts = 0;
        int thinkingParts = 0;
        int imageParts = 0;
        long decodedImageBytes = 0;
        for (JsonNode part : parts) {
            if (shouldStop(isCancelled)) {
                return emptyList();
            }
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
            if (inlineData.isMissingNode()) {
                continue;
            }
            String mimeType = StringUtils.defaultIfBlank(
                    inlineData.path("mimeType").asText(""),
                    inlineData.path("mime_type").asText("")
            );
            String data = inlineData.path("data").asText("");
            if (!mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                log.warn("Google AI returned unsupported inline response data");
                emissions.add(GoogleAiEmission.token(UNSUPPORTED_INLINE_DATA_WARNING));
                emittedParts++;
                continue;
            }
            imageParts++;
            if (imageParts > MAX_GENERATED_IMAGES) {
                throw new IllegalStateException("Google AI returned too many generated images.");
            }
            GeneratedImageData image = decodeGeneratedImage(data, mimeType, decodedImageBytes);
            decodedImageBytes += image.bytes().length;
            emissions.add(GoogleAiEmission.image(image));
            emittedParts++;
        }

        if (emittedParts == 0) {
            throw emptyOutput(
                    root,
                    thinkingParts > 0 ? "only thinking parts, no answer text" : "no usable text or image parts"
            );
        }

        return List.copyOf(emissions);
    }

    private GeneratedImageData decodeGeneratedImage(
            String encoded,
            String declaredMime,
            long previouslyDecodedBytes
    ) throws IOException {
        String canonicalMime = canonicalGeneratedImageMime(declaredMime)
                .orElseThrow(() -> new IOException("Google AI returned an unsupported generated image type."));
        int decodedLength = decodedBase64Length(encoded);
        if (decodedLength <= 0
                || decodedLength > MAX_GENERATED_IMAGE_BYTES
                || previouslyDecodedBytes + decodedLength > MAX_GENERATED_IMAGE_BYTES) {
            throw new IOException("Google AI generated image data exceeded the response limit.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IOException("Google AI returned malformed generated image data.", e);
        }
        if (bytes.length != decodedLength) {
            throw new IOException("Google AI generated image length did not match its encoding.");
        }
        ImageDimensions dimensions = inspectGeneratedImage(bytes, canonicalMime);
        return new GeneratedImageData(bytes, canonicalMime, dimensions.width(), dimensions.height());
    }

    private int decodedBase64Length(String encoded) throws IOException {
        if (StringUtils.isEmpty(encoded) || encoded.length() % 4 != 0) {
            throw new IOException("Google AI returned malformed generated image data.");
        }
        int padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
        for (int index = 0; index < encoded.length(); index++) {
            char character = encoded.charAt(index);
            boolean alphabet = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '+'
                    || character == '/';
            boolean terminalPadding = character == '=' && index >= encoded.length() - padding;
            if (!alphabet && !terminalPadding) {
                throw new IOException("Google AI returned malformed generated image data.");
            }
        }
        long decodedLength = (long) encoded.length() / 4L * 3L - padding;
        if (decodedLength > Integer.MAX_VALUE) {
            throw new IOException("Google AI generated image data exceeded the response limit.");
        }
        return (int) decodedLength;
    }

    private Optional<String> canonicalGeneratedImageMime(String mimeType) {
        return switch (StringUtils.defaultString(mimeType)) {
            case "image/jpeg" -> Optional.of("image/jpeg");
            case "image/png" -> Optional.of("image/png");
            case "image/gif" -> Optional.of("image/gif");
            case "image/webp" -> Optional.of("image/webp");
            default -> Optional.empty();
        };
    }

    private ImageDimensions inspectGeneratedImage(byte[] bytes, String declaredMime) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("Google AI generated image could not be inspected.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Google AI generated image format is unsupported.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width <= 0
                        || height <= 0
                        || width > MAX_IMAGE_DIMENSION
                        || height > MAX_IMAGE_DIMENSION
                        || pixels > MAX_IMAGE_PIXELS) {
                    throw new IOException("Google AI generated image dimensions exceeded the response limit.");
                }
                String detectedMime = canonicalGeneratedImageMime("image/%s".formatted(
                        reader.getFormatName().toLowerCase(Locale.ROOT).replace("jpg", "jpeg")
                )).orElseThrow(() -> new IOException("Google AI generated image format is unsupported."));
                if (!detectedMime.equals(declaredMime)) {
                    throw new IOException("Google AI generated image did not match its declared MIME type.");
                }
                return new ImageDimensions(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    private List<GoogleAiEmission> materializeGeneratedImages(
            List<GoogleAiEmission> emissions,
            BooleanSupplier isCancelled
    ) throws Exception {
        List<GoogleAiEmission> materialized = new ArrayList<>(emissions.size());
        List<AttachmentRef> ownedRefs = new ArrayList<>();
        try {
            for (GoogleAiEmission emission : emissions) {
                if (shouldStop(isCancelled)) {
                    discardGeneratedRefs(ownedRefs);
                    return emptyList();
                }
                if (emission.image() == null) {
                    materialized.add(emission);
                    continue;
                }
                GeneratedImageData image = emission.image();
                AttachmentRef ref = generatedImageAttachmentWriter.write(image.bytes(), image.mimeType());
                ownedRefs.add(ref);
                materialized.add(GoogleAiEmission.part(new GeneratedImagePart(
                        ref,
                        image.width(),
                        image.height(),
                        "Generated image"
                )));
            }
            return List.copyOf(materialized);
        } catch (Exception e) {
            discardGeneratedRefs(ownedRefs);
            throw e;
        }
    }

    private void emitValidatedParts(
            List<GoogleAiEmission> emissions,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<ContentPart> onPart,
            BooleanSupplier isCancelled
    ) {
        List<AttachmentRef> undelivered = emissions.stream()
                .map(GoogleAiEmission::part)
                .filter(GeneratedImagePart.class::isInstance)
                .map(GeneratedImagePart.class::cast)
                .map(GeneratedImagePart::attachmentRef)
                .collect(toCollection(ArrayList::new));
        try {
            for (GoogleAiEmission emission : emissions) {
                if (shouldStop(isCancelled)) {
                    return;
                }
                if (onPart != null && emission.part() instanceof GeneratedImagePart generatedImagePart) {
                    undelivered.remove(generatedImagePart.attachmentRef());
                }
                emission.emit(onToken, onThinkingToken, onPart);
            }
        } finally {
            discardGeneratedRefs(undelivered);
        }
    }

    private void discardGeneratedRefs(List<AttachmentRef> refs) {
        refs.forEach(generatedImageAttachmentWriter::discard);
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

        return diagnostics.isEmpty()
                ? ""
                : "; %s".formatted(BoundedUtf8.presentation(String.join(", ", diagnostics), 1_024, 1_024));
    }

    private void addDiagnostic(List<String> diagnostics, String label, String value) {
        String normalized = BoundedUtf8.presentation(value, 256, 1_024);
        if (StringUtils.isNotBlank(normalized)) {
            diagnostics.add("%s=%s".formatted(label, normalized));
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

    private String errorMessage(String body) {
        if (StringUtils.isBlank(body)) {
            return "empty error response";
        }
        try {
            JsonNode root = JSON.readTree(body);
            String message = root.path("error").path("message").asText("");
            if (StringUtils.isNotBlank(message)) {
                String normalized = BoundedUtf8.presentation(message, 256, 1_024);
                if (StringUtils.isNotBlank(normalized)) {
                    return normalized;
                }
            }
        } catch (Exception ignored) {
            return "unparseable error response";
        }
        return "unrecognized error response";
    }

    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long maximumBytes;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final boolean declaredTooLarge;
        private Flow.Subscription subscription;
        private long received;

        BoundedBodySubscriber(long declaredLength, long maximumBytes) {
            this.maximumBytes = maximumBytes;
            declaredTooLarge = declaredLength > maximumBytes;
            int initialCapacity = declaredLength > 0 && declaredLength <= maximumBytes
                    ? (int) declaredLength
                    : 8192;
            output = new ByteArrayOutputStream(initialCapacity);
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declaredTooLarge) {
                subscription.cancel();
                body.completeExceptionally(new IOException("Google AI response exceeded the response byte limit."));
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int remaining = buffer.remaining();
                if (received + remaining > maximumBytes) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Google AI response exceeded the response byte limit."));
                    return;
                }
                byte[] chunk = new byte[remaining];
                buffer.get(chunk);
                output.writeBytes(chunk);
                received += remaining;
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (!body.isDone()) {
                body.complete(output.toByteArray());
            }
        }
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted()
                || isCancelled != null && isCancelled.getAsBoolean();
    }

    private record ImageDimensions(int width, int height) {
    }

    private record GeneratedImageData(byte[] bytes, String mimeType, int width, int height) {
        @Override
        public String toString() {
            return "GeneratedImageData[bytes=<masked>, mimeType=%s, width=%d, height=%d]".formatted(
                    mimeType,
                    width,
                    height
            );
        }
    }

    private record GoogleAiEmission(String text, ContentPart part, GeneratedImageData image, boolean thinking) {
        static GoogleAiEmission token(String text) {
            return new GoogleAiEmission(text, null, null, false);
        }

        static GoogleAiEmission thinking(String text) {
            return new GoogleAiEmission(text, null, null, true);
        }

        static GoogleAiEmission part(ContentPart part) {
            return new GoogleAiEmission(null, part, null, false);
        }

        static GoogleAiEmission image(GeneratedImageData image) {
            return new GoogleAiEmission(null, null, image, false);
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
