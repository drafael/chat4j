package com.github.drafael.chat4j.provider.api;

import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface ProviderService {

    void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled
    );

    default void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled
    ) {
        streamCompletion(history, reasoningLevel, onToken, onThinkingToken, onComplete, onError, isCancelled);
    }

    default void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) {
        streamCompletion(history, reasoningLevel, webSearchOptions, onToken, onThinkingToken, onComplete, onError, isCancelled);
    }

    default void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Consumer<ContentPart> onPart,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) {
        streamCompletion(
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onComplete,
                onError,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    default void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Consumer<ContentPart> onPart,
        Consumer<CitationRef> onCitation,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) {
        streamCompletion(
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onPart,
                onComplete,
                onError,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    default void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Consumer<ContentPart> onPart,
        Consumer<CitationRef> onCitation,
        Consumer<WebSearchSource> onWebSearchSource,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) {
        streamCompletion(
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onPart,
                onCitation,
                onComplete,
                onError,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    default void streamCompletion(
        List<Message> history,
        ReasoningLevel reasoningLevel,
        WebSearchRequestOptions webSearchOptions,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        Consumer<ContentPart> onPart,
        Consumer<CitationRef> onCitation,
        Consumer<String> onWebSearchQuery,
        Consumer<WebSearchSource> onWebSearchSource,
        Runnable onComplete,
        Consumer<Exception> onError,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) {
        streamCompletion(
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                onPart,
                onCitation,
                onWebSearchSource,
                onComplete,
                onError,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    default void cancelActiveRequest() {
        // no-op by default
    }

    default String apiKey() {
        return "";
    }

}
