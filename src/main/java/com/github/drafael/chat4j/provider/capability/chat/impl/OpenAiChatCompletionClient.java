package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class OpenAiChatCompletionClient implements ChatCompletionClient {

    @Override
    public void streamCompletion(
        ProviderRuntime runtime,
        List<Message> history,
        Consumer<String> onToken,
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) throws Exception {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(runtime.apiKey())
                .baseUrl(runtime.baseUrl())
                .build();

        List<ChatCompletionMessageParam> messages = history.stream()
                .map(this::toParam)
                .toList();

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(runtime.selectedModel()))
                .messages(messages)
                .build();

        try (StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(params)) {
            registerActiveStream.accept(stream);
            Iterator<ChatCompletionChunk> iterator = stream.stream().iterator();
            while (iterator.hasNext()) {
                if (shouldStop(isCancelled)) {
                    return;
                }
                ChatCompletionChunk chunk = iterator.next();
                for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    choice.delta().content().ifPresent(onToken);
                }
            }
        } finally {
            clearActiveStream.run();
        }
    }

    private ChatCompletionMessageParam toParam(Message msg) {
        return switch (msg.role()) {
            case USER -> ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                            .content(msg.content())
                            .build());
            case ASSISTANT -> ChatCompletionMessageParam.ofAssistant(
                    ChatCompletionAssistantMessageParam.builder()
                            .content(msg.content())
                            .build());
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                            .content(msg.content())
                            .build());
        };
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
