package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.MistralNativeWebSearchSupport;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class MistralChatCompletionClient implements ChatCompletionClient {

    private final ChatCompletionClient ordinaryClient;
    private final ChatCompletionClient nativeSearchClient;

    public MistralChatCompletionClient(@NonNull ProviderAttachmentSupport attachmentSupport) {
        this(
                new OpenAiChatCompletionClient(attachmentSupport),
                new MistralConversationsWebSearchClient(attachmentSupport)
        );
    }

    MistralChatCompletionClient(ChatCompletionClient ordinaryClient, ChatCompletionClient nativeSearchClient) {
        this.ordinaryClient = ordinaryClient;
        this.nativeSearchClient = nativeSearchClient;
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
        ordinaryClient.streamCompletion(
                runtime,
                history,
                reasoningLevel,
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
            Consumer<ContentPart> onPart,
            Consumer<CitationRef> onCitation,
            Consumer<WebSearchSource> onWebSearchSource,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        if (webSearchOptions == null || !webSearchOptions.enabled()) {
            ordinaryClient.streamCompletion(
                    runtime,
                    history,
                    reasoningLevel,
                    webSearchOptions,
                    onToken,
                    onThinkingToken,
                    onPart,
                    onCitation,
                    onWebSearchSource,
                    isCancelled,
                    registerActiveStream,
                    clearActiveStream
            );
            return;
        }
        if (!MistralNativeWebSearchSupport.supports(
                runtime.descriptor().name(),
                runtime.selectedModel(),
                runtime.baseUrl()
        )) {
            throw new IllegalArgumentException(
                    "Mistral native Web Search requires a supported chat model and the official API endpoint."
            );
        }
        nativeSearchClient.streamCompletion(
                runtime,
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onPart,
                onCitation,
                onWebSearchSource,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }
}
