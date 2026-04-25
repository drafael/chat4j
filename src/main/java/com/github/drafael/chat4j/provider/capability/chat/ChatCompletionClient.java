package com.github.drafael.chat4j.provider.capability.chat;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@FunctionalInterface
public interface ChatCompletionClient {

    void streamCompletion(
        ProviderRuntime runtime,
        List<Message> history,
        ReasoningLevel reasoningLevel,
        Consumer<String> onToken,
        Consumer<String> onThinkingToken,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) throws Exception;
}
