package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.WebSearchResultBlock;
import com.anthropic.models.messages.WebSearchTool20250305;
import com.anthropic.models.messages.WebSearchToolResultBlockContent;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.DeepSeekNativeWebSearchSupport;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.WebSearchSourceUrlNormalizer;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class DeepSeekAnthropicWebSearchClient implements ChatCompletionClient {

    private static final long MAX_OUTPUT_TOKENS = 384_000L;

    private final ProviderAttachmentSupport attachmentSupport;
    private final ClientFactory clientFactory;
    private final String baseUrlOverride;

    public DeepSeekAnthropicWebSearchClient(@NonNull ProviderAttachmentSupport attachmentSupport) {
        this(attachmentSupport, ClientFactory.PRODUCTION, null);
    }

    DeepSeekAnthropicWebSearchClient(
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull ClientFactory clientFactory,
            String baseUrlOverride
    ) {
        this.attachmentSupport = attachmentSupport;
        this.clientFactory = clientFactory;
        this.baseUrlOverride = StringUtils.trimToNull(baseUrlOverride);
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
    ) {
        throw new IllegalArgumentException("DeepSeek native Web Search must be enabled explicitly.");
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
            Consumer<WebSearchSource> onWebSearchSource,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        if (webSearchOptions == null || !webSearchOptions.enabled()) {
            throw new IllegalArgumentException("DeepSeek native Web Search must be enabled explicitly.");
        }
        if (shouldStop(isCancelled)) {
            return;
        }

        AttachmentProjectionPlan projectionPlan = AttachmentProjectionPlan.create(
                history,
                attachmentSupport,
                AttachmentProjectionPlan.textOnly(),
                isCancelled
        );
        List<MessageParam> messages = projectionPlan.messages().stream()
                .filter(message -> message.role() != Role.SYSTEM)
                .filter(message -> message.parts().stream()
                        .map(AttachmentProjectionPlan::textFallback)
                        .anyMatch(StringUtils::isNotBlank))
                .map(this::toParam)
                .toList();
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("DeepSeek native Web Search requires usable message text.");
        }

        ReasoningLevel level = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(Model.of(runtime.selectedModel()))
                .maxTokens(MAX_OUTPUT_TOKENS)
                .messages(messages)
                .addTool(WebSearchTool20250305.builder().build());
        List<TextBlockParam> systemBlocks = systemBlocks(projectionPlan);
        if (!systemBlocks.isEmpty()) {
            paramsBuilder.systemOfTextBlockParams(systemBlocks);
        }
        configureThinking(paramsBuilder, level);
        MessageCreateParams params = paramsBuilder.build();

        if (shouldStop(isCancelled)) {
            return;
        }
        String baseUrl = baseUrlOverride == null
                ? DeepSeekNativeWebSearchSupport.anthropicBaseUrl(runtime.baseUrl())
                        .orElseThrow(() -> new IllegalArgumentException("DeepSeek native Web Search requires the official API endpoint."))
                : baseUrlOverride;
        Set<String> emittedSources = new HashSet<>();
        AnthropicClient client = clientFactory.create(runtime.apiKey(), baseUrl);
        try {
            if (shouldStop(isCancelled)) {
                return;
            }
            try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
                registerActiveStream.accept(stream);
                try {
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    Iterator<RawMessageStreamEvent> iterator = stream.stream().iterator();
                    boolean emittedAssistantText = false;
                    while (!shouldStop(isCancelled) && iterator.hasNext()) {
                        RawMessageStreamEvent event = iterator.next();
                        if (shouldStop(isCancelled)) {
                            return;
                        }
                        if (event.isMessageStop()) {
                            if (!emittedAssistantText) {
                                throw new IllegalStateException("DeepSeek completed without assistant output.");
                            }
                            return;
                        }
                        emittedAssistantText |= handleEvent(
                                event,
                                level,
                                emittedSources,
                                onToken,
                                onThinkingToken,
                                onWebSearchSource,
                                isCancelled
                        );
                    }
                    if (!shouldStop(isCancelled)) {
                        throw new IllegalStateException("DeepSeek stream ended before message_stop.");
                    }
                } finally {
                    clearActiveStream.run();
                }
            }
        } finally {
            client.close();
        }
    }

    private boolean handleEvent(
            RawMessageStreamEvent event,
            ReasoningLevel reasoningLevel,
            Set<String> emittedSources,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<WebSearchSource> onWebSearchSource,
            BooleanSupplier isCancelled
    ) {
        if (event.isContentBlockDelta()) {
            RawContentBlockDelta delta = event.asContentBlockDelta().delta();
            if (delta.isText()) {
                String text = delta.asText().text();
                emit(text, onToken, isCancelled);
                return StringUtils.isNotBlank(text);
            }
            if (reasoningLevel.enabled() && delta.isThinking()) {
                emit(delta.asThinking().thinking(), onThinkingToken, isCancelled);
            }
            return false;
        }
        if (!event.isContentBlockStart()) {
            return false;
        }
        var block = event.asContentBlockStart().contentBlock();
        if (!block.isWebSearchToolResult()) {
            return false;
        }
        WebSearchToolResultBlockContent content = block.asWebSearchToolResult().content();
        if (!content.isResultBlocks()) {
            return false;
        }
        content.asResultBlocks().forEach(result -> emitSource(result, emittedSources, onWebSearchSource, isCancelled));
        return false;
    }

    private void emitSource(
            WebSearchResultBlock result,
            Set<String> emittedSources,
            Consumer<WebSearchSource> onWebSearchSource,
            BooleanSupplier isCancelled
    ) {
        WebSearchSourceUrlNormalizer.normalize(result.url()).ifPresent(normalized -> {
            if (shouldStop(isCancelled) || !emittedSources.add(normalized.key())) {
                return;
            }
            String title = StringUtils.normalizeSpace(result.title());
            WebSearchSource source = new WebSearchSource(
                    StringUtils.defaultIfBlank(title, normalized.host()),
                    normalized.displayUrl()
            );
            if (!shouldStop(isCancelled)) {
                onWebSearchSource.accept(source);
            }
        });
    }

    private void emit(String value, Consumer<String> callback, BooleanSupplier isCancelled) {
        if (StringUtils.isNotEmpty(value) && !shouldStop(isCancelled)) {
            callback.accept(value);
        }
    }

    private MessageParam toParam(ProjectedMessage message) {
        List<ContentBlockParam> blocks = message.parts().stream()
                .map(AttachmentProjectionPlan::textFallback)
                .filter(StringUtils::isNotBlank)
                .map(text -> ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()))
                .toList();
        return MessageParam.builder()
                .role(message.role() == Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build();
    }

    private List<TextBlockParam> systemBlocks(AttachmentProjectionPlan projectionPlan) {
        return projectionPlan.messages().stream()
                .filter(message -> message.role() == Role.SYSTEM)
                .flatMap(message -> message.parts().stream())
                .map(AttachmentProjectionPlan::textFallback)
                .filter(StringUtils::isNotBlank)
                .map(text -> TextBlockParam.builder().text(text).build())
                .toList();
    }

    private void configureThinking(MessageCreateParams.Builder paramsBuilder, ReasoningLevel reasoningLevel) {
        if (!reasoningLevel.enabled()) {
            paramsBuilder.thinking(ThinkingConfigDisabled.builder().build());
            return;
        }
        paramsBuilder.enabledThinking(reasoningBudget(reasoningLevel));
        paramsBuilder.outputConfig(OutputConfig.builder().effort(outputEffort(reasoningLevel)).build());
    }

    private long reasoningBudget(ReasoningLevel reasoningLevel) {
        return switch (reasoningLevel) {
            case OFF -> 0L;
            case LOW -> 1_024L;
            case MEDIUM -> 2_048L;
            case HIGH -> 4_096L;
            case EXTRA_HIGH, MAX, ULTRA -> 8_192L;
        };
    }

    private OutputConfig.Effort outputEffort(ReasoningLevel reasoningLevel) {
        return switch (reasoningLevel) {
            case OFF -> throw new IllegalArgumentException("Disabled thinking has no output effort.");
            case LOW -> OutputConfig.Effort.LOW;
            case MEDIUM, HIGH -> OutputConfig.Effort.HIGH;
            case EXTRA_HIGH, MAX, ULTRA -> OutputConfig.Effort.MAX;
        };
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    @FunctionalInterface
    interface ClientFactory {
        ClientFactory PRODUCTION = (apiKey, baseUrl) -> AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        AnthropicClient create(String apiKey, String baseUrl);
    }
}
