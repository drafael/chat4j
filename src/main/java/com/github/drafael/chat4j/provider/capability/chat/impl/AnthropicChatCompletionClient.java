package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;

import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class AnthropicChatCompletionClient implements ChatCompletionClient {

    @Override
    public void streamCompletion(
        ProviderRuntime runtime,
        List<Message> history,
        Consumer<String> onToken,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) throws Exception {
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(runtime.apiKey())
                .baseUrl(runtime.baseUrl())
                .build();

        List<MessageParam> messages = history.stream()
                .filter(message -> message.role() != Role.SYSTEM)
                .map(this::toParam)
                .toList();

        var paramsBuilder = MessageCreateParams.builder()
                .model(Model.of(runtime.selectedModel()))
                .maxTokens(4096)
                .messages(messages);

        history.stream()
                .filter(message -> message.role() == Role.SYSTEM)
                .findFirst()
                .ifPresent(message -> paramsBuilder.system(message.content()));

        MessageCreateParams params = paramsBuilder.build();

        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            registerActiveStream.accept(stream);
            Iterator<RawMessageStreamEvent> iterator = stream.stream().iterator();
            while (iterator.hasNext()) {
                if (shouldStop(isCancelled)) {
                    return;
                }
                RawMessageStreamEvent event = iterator.next();
                if (event.isContentBlockDelta()
                        && event.asContentBlockDelta().delta().isText()
                ) {
                    onToken.accept(event.asContentBlockDelta().delta().asText().text());
                }
            }
        } finally {
            clearActiveStream.run();
        }
    }

    private MessageParam toParam(Message message) {
        return MessageParam.builder()
                .role(message.role() == Role.USER
                        ? MessageParam.Role.USER
                        : MessageParam.Role.ASSISTANT
                )
                .content(message.content())
                .build();
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
