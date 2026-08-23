package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.chat.render.BoundedUtf8;
import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpCall;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.json.JsonCodec;
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
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.NativeImage;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedPart;
import com.github.drafael.chat4j.provider.support.GeneratedImageAttachmentWriter;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toCollection;

@Slf4j
public class GoogleAiGenerateContentClient implements ChatCompletionClient {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final String GOOGLE_AI_PROVIDER_NAME = "Google AI";
    private static final String DEFAULT_GENERATE_CONTENT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final long MAX_RESPONSE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_GENERATED_IMAGES = 4;
    private static final String UNSUPPORTED_INLINE_DATA_WARNING = "[Google AI returned unsupported inline data.]";
    private static final long MAX_GENERATED_IMAGE_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 16_384;
    private static final long MAX_IMAGE_PIXELS = 40_000_000L;
    private static final Set<String> SUCCESSFUL_FINISH_REASONS = Set.of(
            "FINISH_REASON_UNSPECIFIED",
            "MAX_TOKENS",
            "STOP"
    );

    private final ChatCompletionClient fallbackClient;
    private final HttpTransport transport;
    private final GeneratedImageAttachmentWriter generatedImageAttachmentWriter;
    private final ProviderAttachmentSupport attachmentSupport;

    public GoogleAiGenerateContentClient(
            @NonNull ChatCompletionClient fallbackClient,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull GeneratedImageAttachmentWriter generatedImageAttachmentWriter
    ) {
        this(fallbackClient, new JavaNetHttpTransport(), attachmentSupport, generatedImageAttachmentWriter);
    }

    GoogleAiGenerateContentClient(
            @NonNull ChatCompletionClient fallbackClient,
            @NonNull HttpTransport transport,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull GeneratedImageAttachmentWriter generatedImageAttachmentWriter
    ) {
        this.fallbackClient = fallbackClient;
        this.transport = transport;
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
        boolean webSearchRequested = webSearchOptions != null && webSearchOptions.enabled();
        boolean nativeWebSearch = shouldUseGoogleNativeWebSearch(runtime, webSearchOptions);
        ReasoningLevel normalizedReasoningLevel = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        if (webSearchRequested && !nativeWebSearch) {
            throw new IllegalArgumentException("Native Web Search is unavailable for this Google AI model or endpoint.");
        }
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
                normalizedReasoningLevel,
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
            ReasoningLevel reasoningLevel,
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
        var request = new HttpExchangeRequest(
                "POST",
                generateContentUri(runtime),
                java.util.Map.of(
                        "Content-Type", "application/json",
                        "x-goog-api-key", runtime.apiKey()
                ),
                HttpBody.utf8(requestBody(projectionPlan, imageOutputModel, nativeWebSearch, reasoningLevel)),
                REQUEST_TIMEOUT,
                MAX_RESPONSE_BYTES
        );
        if (shouldStop(isCancelled)) {
            return;
        }
        boolean clearHandled = false;
        try (HttpCall call = transport.open(request)) {
            if (registerActiveStream != null) {
                try {
                    registerActiveStream.accept(call);
                } catch (RuntimeException | Error e) {
                    call.close();
                    clearHandled = true;
                    if (clearActiveStream != null) {
                        try {
                            clearActiveStream.run();
                        } catch (RuntimeException | Error clearFailure) {
                            e.addSuppressed(clearFailure);
                        }
                    }
                    throw e;
                }
            }

            HttpExchangeResponse response = call.await(isCancelled);
            if (shouldStop(isCancelled)) {
                return;
            }
            String responseBody = decodeResponseBody(response.body());
            if (!response.successful()) {
                throw new IllegalStateException("Google AI request failed (%d): %s".formatted(
                        response.statusCode(),
                        errorMessage(responseBody)
                ));
            }
            GoogleAiApi.GenerateResponse body = JSON.read(responseBody, GoogleAiApi.GenerateResponse.class);
            List<GoogleAiEmission> emissions = materializeGeneratedImages(
                    responseEmissions(body, reasoningLevel, isCancelled),
                    isCancelled
            );
            if (emissions.isEmpty() && shouldStop(isCancelled)) {
                return;
            }
            emitValidatedParts(emissions, onToken, onThinkingToken, onPart, isCancelled);
            if (nativeWebSearch && onCitation != null) {
                for (CitationRef citation : citations(body)) {
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    onCitation.accept(citation);
                }
            }
        } finally {
            if (!clearHandled && clearActiveStream != null) {
                clearActiveStream.run();
            }
        }
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
                && Strings.CS.equals(runtime.baseUrl(), runtime.normalizedDefaultBaseUrl())
                && !ProviderCapabilityResolver.isGoogleLatestAlias(runtime.selectedModel())
                && webSearchOptions != null
                && webSearchOptions.enabled();
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
            boolean webSearchEnabled,
            ReasoningLevel reasoningLevel
    ) {
        List<GoogleAiApi.Part> systemParts = projectionPlan.messages().stream()
                .filter(message -> message.role() == Role.SYSTEM)
                .flatMap(message -> message.parts().stream())
                .map(this::toGooglePart)
                .filter(java.util.Objects::nonNull)
                .toList();
        List<GoogleAiApi.Content> contents = projectionPlan.messages().stream()
                .filter(message -> message.role() != Role.SYSTEM)
                .map(message -> new GoogleAiApi.Content(
                        message.role() == Role.ASSISTANT ? "model" : "user",
                        message.parts().stream().map(this::toGooglePart).filter(java.util.Objects::nonNull).toList()
                ))
                .toList();
        GoogleAiApi.GenerationConfig generationConfig = includeImageResponse || reasoningLevel.enabled()
                ? new GoogleAiApi.GenerationConfig(
                        includeImageResponse ? List.of("TEXT", "IMAGE") : null,
                        reasoningLevel.enabled() ? new GoogleAiApi.ThinkingConfig(true) : null
                )
                : null;
        var request = new GoogleAiApi.GenerateRequest(
                systemParts.isEmpty() ? null : new GoogleAiApi.SystemInstruction(systemParts),
                contents,
                generationConfig,
                webSearchEnabled ? List.of(new GoogleAiApi.Tool(new GoogleAiApi.GoogleSearch())) : null
        );
        return JSON.writeString(request);
    }

    private GoogleAiApi.Part toGooglePart(ProjectedPart part) {
        if (part instanceof NativeImage image) {
            return GoogleAiApi.Part.image(image.mediaType(), image.base64Data());
        }
        String text = AttachmentProjectionPlan.textFallback(part);
        return StringUtils.isBlank(text) ? null : GoogleAiApi.Part.text(text);
    }

    private List<GoogleAiEmission> responseEmissions(
            GoogleAiApi.GenerateResponse body,
            ReasoningLevel reasoningLevel,
            BooleanSupplier isCancelled
    ) throws Exception {
        validateCandidateFinishReason(body);
        GoogleAiApi.Candidate candidate = firstCandidate(body);
        List<GoogleAiApi.Part> parts = candidate == null || candidate.content() == null
                ? emptyList()
                : safeList(candidate.content().parts());
        if (parts.isEmpty()) {
            throw emptyOutput(body, "no candidate content parts");
        }

        List<GoogleAiEmission> emissions = new ArrayList<>();
        int emittedParts = 0;
        int thinkingParts = 0;
        int imageParts = 0;
        long decodedImageBytes = 0;
        for (GoogleAiApi.Part part : parts) {
            if (shouldStop(isCancelled)) {
                return emptyList();
            }
            String text = StringUtils.defaultString(part.text());
            if (Boolean.TRUE.equals(part.thought())) {
                if (reasoningLevel.enabled() && StringUtils.isNotEmpty(text)) {
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

            GoogleAiApi.InlineData inlineData = part.inlineData();
            if (inlineData == null) {
                continue;
            }
            String mimeType = StringUtils.defaultString(inlineData.mimeType());
            String data = StringUtils.defaultString(inlineData.data());
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
                    body,
                    thinkingParts > 0 ? "only thinking parts, no answer text" : "no usable text or image parts"
            );
        }

        return List.copyOf(emissions);
    }

    private void validateCandidateFinishReason(GoogleAiApi.GenerateResponse body) {
        GoogleAiApi.Candidate candidate = firstCandidate(body);
        String finishReason = StringUtils.trimToEmpty(candidate == null ? null : candidate.finishReason())
                .toUpperCase(Locale.ROOT);
        if (finishReason.isEmpty() || SUCCESSFUL_FINISH_REASONS.contains(finishReason)) {
            return;
        }
        throw new IllegalStateException(
                "Google AI did not complete generateContent successfully%s.".formatted(outputDiagnostics(body))
        );
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

    private EmptyGoogleAiOutputException emptyOutput(GoogleAiApi.GenerateResponse body, String reason) {
        return new EmptyGoogleAiOutputException(
                "Google AI returned no generateContent output (%s%s).".formatted(reason, outputDiagnostics(body))
        );
    }

    private String outputDiagnostics(GoogleAiApi.GenerateResponse body) {
        List<String> diagnostics = new ArrayList<>();
        addDiagnostic(
                diagnostics,
                "promptBlockReason",
                body == null || body.promptFeedback() == null ? "" : body.promptFeedback().blockReason()
        );
        GoogleAiApi.Candidate candidate = firstCandidate(body);
        addDiagnostic(diagnostics, "finishReason", candidate == null ? "" : candidate.finishReason());
        addDiagnostic(diagnostics, "finishMessage", candidate == null ? "" : candidate.finishMessage());
        addDiagnostic(
                diagnostics,
                "modelStatus",
                body == null || body.modelStatus() == null ? "" : body.modelStatus().message()
        );

        GoogleAiApi.UsageMetadata usage = body == null ? null : body.usageMetadata();
        if (usage != null) {
            diagnostics.add("tokens prompt=%d candidates=%d thoughts=%d total=%d".formatted(
                    zeroIfNull(usage.promptTokenCount()),
                    zeroIfNull(usage.candidatesTokenCount()),
                    zeroIfNull(usage.thoughtsTokenCount()),
                    zeroIfNull(usage.totalTokenCount())
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

    private List<CitationRef> citations(GoogleAiApi.GenerateResponse body) {
        GoogleAiApi.Candidate candidate = firstCandidate(body);
        GoogleAiApi.GroundingMetadata grounding = candidate == null ? null : candidate.groundingMetadata();
        List<GoogleAiApi.GroundingChunk> chunks = grounding == null
                ? emptyList()
                : safeList(grounding.groundingChunks());
        if (chunks.isEmpty()) {
            return emptyList();
        }

        CitationAccumulator citationAccumulator = new CitationAccumulator();
        List<CitationRef> citations = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            GoogleAiApi.WebSource web = chunks.get(index) == null ? null : chunks.get(index).web();
            if (web == null) {
                continue;
            }
            String citedText = citedTextForGroundingChunk(safeList(grounding.groundingSupports()), index);
            UrlCitationMapper.fromUrl(web.title(), web.uri(), citedText)
                    .flatMap(citationAccumulator::addNew)
                    .ifPresent(citations::add);
        }
        return List.copyOf(citations);
    }

    private String citedTextForGroundingChunk(List<GoogleAiApi.GroundingSupport> supports, int chunkIndex) {
        return supports.stream()
                .filter(java.util.Objects::nonNull)
                .filter(support -> safeList(support.groundingChunkIndices()).contains(chunkIndex))
                .map(GoogleAiApi.GroundingSupport::segment)
                .filter(java.util.Objects::nonNull)
                .map(GoogleAiApi.Segment::text)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    private String errorMessage(String body) {
        if (StringUtils.isBlank(body)) {
            return "empty error response";
        }
        try {
            GoogleAiApi.ErrorEnvelope envelope = JSON.read(body, GoogleAiApi.ErrorEnvelope.class);
            String message = envelope.error() == null ? "" : envelope.error().message();
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

    private GoogleAiApi.Candidate firstCandidate(GoogleAiApi.GenerateResponse body) {
        return body == null || safeList(body.candidates()).isEmpty() ? null : body.candidates().getFirst();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? emptyList() : values;
    }

    private int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
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
        EmptyGoogleAiOutputException(String message) {
            super(message);
        }
    }

}
