package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CitationsConfigParam;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.WebSearchTool20250305;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ExtractedText;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.Label;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.NativeImage;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.NativePdf;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.NativeTextDocument;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.PlainText;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedPart;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;


public class AnthropicChatCompletionClient implements ChatCompletionClient {

    private static final int MAX_ANSWER_TOKENS = 4096;

    private final ProviderAttachmentSupport attachmentSupport;

    public AnthropicChatCompletionClient(@NonNull ProviderAttachmentSupport attachmentSupport) {
        this.attachmentSupport = attachmentSupport;
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
        Consumer<CitationRef> onCitation,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) throws Exception {
        if (webSearchOptions != null && webSearchOptions.enabled()
                && !ProviderCapabilityResolver.nativeWebSearchOutcome(
                runtime.descriptor().name(),
                runtime.selectedModel(),
                runtime.baseUrl(),
                runtime.normalizedDefaultBaseUrl()
        ).supported()) {
            throw new IllegalArgumentException("Native Web Search is unavailable for this Anthropic model or endpoint.");
        }
        AttachmentProjectionPlan projectionPlan = AttachmentProjectionPlan.create(
                history,
                attachmentSupport,
                AttachmentProjectionPlan.anthropic(
                        supportsNativeImages(runtime),
                        supportsNativeDocuments(runtime)
                ),
                isCancelled
        );
        if (shouldStop(isCancelled)) {
            return;
        }
        List<MessageParam> messages = projectionPlan.messages().stream()
                .filter(message -> message.role() != Role.SYSTEM)
                .map(this::toParam)
                .toList();

        boolean reasoningEnabled = reasoningLevel.enabled() && supportsReasoning(runtime);
        var paramsBuilder = MessageCreateParams.builder()
                .model(Model.of(runtime.selectedModel()))
                .maxTokens(completionTokenLimit(reasoningLevel, reasoningEnabled))
                .messages(messages);

        List<TextBlockParam> systemBlocks = systemBlocks(projectionPlan);
        if (!systemBlocks.isEmpty()) {
            paramsBuilder.systemOfTextBlockParams(systemBlocks);
        }

        if (reasoningEnabled) {
            paramsBuilder.enabledThinking(reasoningBudget(reasoningLevel));
        }

        if (webSearchOptions != null && webSearchOptions.enabled()) {
            paramsBuilder.addTool(WebSearchTool20250305.builder().build());
        }

        MessageCreateParams params = paramsBuilder.build();

        CitationAccumulator citationAccumulator = new CitationAccumulator();
        if (shouldStop(isCancelled)) {
            return;
        }
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(runtime.apiKey())
                .baseUrl(runtime.baseUrl())
                .build();
        try {
            try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
                registerActiveStream.accept(stream);
                Iterator<RawMessageStreamEvent> iterator = stream.stream().iterator();
                boolean emittedAssistantText = false;
                while (!shouldStop(isCancelled) && iterator.hasNext()) {
                    RawMessageStreamEvent event = iterator.next();
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    if (event.isMessageStop()) {
                        if (!emittedAssistantText) {
                            throw new IllegalStateException("Anthropic completed without assistant output.");
                        }
                        return;
                    }
                    if (!event.isContentBlockDelta()) {
                        continue;
                    }

                    RawContentBlockDelta delta = event.asContentBlockDelta().delta();
                    if (delta.isText()) {
                        String text = delta.asText().text();
                        if (StringUtils.isNotEmpty(text)) {
                            if (StringUtils.isNotBlank(text)) {
                                emittedAssistantText = true;
                            }
                            onToken.accept(text);
                        }
                    }

                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    if (delta.isCitations()) {
                        emitCitation(delta, citationAccumulator, onToken, onCitation, isCancelled);
                    }

                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    if (reasoningLevel.enabled() && delta.isThinking()) {
                        String thinking = delta.asThinking().thinking();
                        if (StringUtils.isNotEmpty(thinking)) {
                            onThinkingToken.accept(thinking);
                        }
                    }
                }
                if (!shouldStop(isCancelled)) {
                    throw new IllegalStateException("Anthropic stream ended before message_stop.");
                }
            } finally {
                clearActiveStream.run();
            }
        } finally {
            client.close();
        }
    }

    private void emitCitation(
            RawContentBlockDelta delta,
            CitationAccumulator citationAccumulator,
            Consumer<String> onToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled
    ) {
        AnthropicCitationMapper.fromDelta(delta.asCitations())
                .map(citationAccumulator::add)
                .ifPresent(citation -> {
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    onCitation.accept(citation);
                    if (!shouldStop(isCancelled)) {
                        onToken.accept(" [%d]".formatted(citation.number()));
                    }
                });
    }

    List<TextBlockParam> systemBlocks(AttachmentProjectionPlan projectionPlan) {
        return projectionPlan.messages().stream()
                .filter(message -> message.role() == Role.SYSTEM)
                .flatMap(message -> message.parts().stream())
                .map(this::projectedText)
                .filter(StringUtils::isNotBlank)
                .map(text -> TextBlockParam.builder().text(text).build())
                .toList();
    }

    private MessageParam toParam(ProjectedMessage message) {
        MessageParam.Builder builder = MessageParam.builder()
                .role(message.role() == Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT);
        List<ContentBlockParam> blocks = message.parts().stream()
                .map(this::mapPart)
                .flatMap(Optional::stream)
                .toList();
        return builder.contentOfBlockParams(blocks).build();
    }

    private Optional<ContentBlockParam> mapPart(ProjectedPart part) {
        if (part instanceof NativeImage image) {
            Base64ImageSource source = Base64ImageSource.builder()
                    .mediaType(resolveMediaType(image.mediaType()))
                    .data(image.base64Data())
                    .build();
            return Optional.of(ContentBlockParam.ofImage(ImageBlockParam.builder().source(source).build()));
        }
        if (part instanceof NativePdf pdf) {
            return Optional.of(ContentBlockParam.ofDocument(documentBuilder(pdf.title())
                    .base64Source(pdf.base64Data())
                    .build()));
        }
        if (part instanceof NativeTextDocument document) {
            return Optional.of(ContentBlockParam.ofDocument(documentBuilder(document.title())
                    .textSource(document.text())
                    .build()));
        }
        String text = projectedText(part);
        return StringUtils.isBlank(text) ? Optional.empty() : Optional.of(toTextBlock(text));
    }

    private String projectedText(ProjectedPart part) {
        return switch (part) {
            case PlainText text -> text.text();
            case Label label -> label.text();
            case ExtractedText extracted -> extracted.projection();
            case NativeImage ignored -> "";
            case NativePdf ignored -> "";
            case NativeTextDocument ignored -> "";
        };
    }

    private DocumentBlockParam.Builder documentBuilder(String safeName) {
        return DocumentBlockParam.builder()
                .citations(CitationsConfigParam.builder().enabled(true).build())
                .title(safeName);
    }

    private Base64ImageSource.MediaType resolveMediaType(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> throw new IllegalArgumentException("Unsupported planned image MIME.");
        };
    }

    private ContentBlockParam toTextBlock(String text) {
        return ContentBlockParam.ofText(
                TextBlockParam.builder()
                        .text(text)
                        .build()
        );
    }

    private boolean supportsNativeImages(ProviderRuntime runtime) {
        return ProviderCapabilityResolver.supportsImageInput(
                runtime.descriptor().capabilities(),
                runtime.descriptor().name(),
                runtime.selectedModel(),
                runtime.baseUrl(),
                runtime.apiKey()
        );
    }

    private boolean supportsNativeDocuments(ProviderRuntime runtime) {
        return ProviderCapabilityResolver.supportsFileInput(runtime.descriptor().capabilities());
    }

    int completionTokenLimit(ReasoningLevel reasoningLevel, boolean reasoningEnabled) {
        return MAX_ANSWER_TOKENS + (reasoningEnabled ? reasoningBudget(reasoningLevel) : 0);
    }

    private int reasoningBudget(ReasoningLevel reasoningLevel) {
        return switch (reasoningLevel) {
            case OFF -> 0;
            case LOW -> 1024;
            case MEDIUM -> 2048;
            case HIGH -> 4096;
            case EXTRA_HIGH, MAX, ULTRA -> 8192;
        };
    }

    private boolean supportsReasoning(ProviderRuntime runtime) {
        return ProviderCapabilityResolver.supportsReasoning(
                runtime.descriptor().capabilities(),
                runtime.descriptor().name(),
                runtime.selectedModel()
        );
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
