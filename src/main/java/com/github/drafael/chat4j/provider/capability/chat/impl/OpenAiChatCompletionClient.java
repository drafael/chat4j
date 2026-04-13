package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
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
                .map(message -> toParam(message, runtime))
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

    private ChatCompletionMessageParam toParam(Message msg, ProviderRuntime runtime) {
        return switch (msg.role()) {
            case USER -> ChatCompletionMessageParam.ofUser(toUserMessage(msg, runtime));
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

    private ChatCompletionUserMessageParam toUserMessage(Message message, ProviderRuntime runtime) {
        List<ChatCompletionContentPart> parts = mapUserParts(message, runtime);
        if (parts.isEmpty()) {
            return ChatCompletionUserMessageParam.builder()
                    .content(message.content())
                    .build();
        }

        return ChatCompletionUserMessageParam.builder()
                .contentOfArrayOfContentParts(parts)
                .build();
    }

    private List<ChatCompletionContentPart> mapUserParts(Message message, ProviderRuntime runtime) {
        if (!supportsNativeImages(runtime) || message.parts().isEmpty()) {
            return List.of();
        }

        List<ChatCompletionContentPart> parts = new ArrayList<>();
        message.parts().stream()
                .map(part -> mapPart(part, runtime))
                .flatMap(List::stream)
                .forEach(parts::add);

        return parts;
    }

    private List<ChatCompletionContentPart> mapPart(ContentPart part, ProviderRuntime runtime) {
        if (part instanceof TextPart textPart && !textPart.text().isBlank()) {
            return List.of(toTextPart(textPart.text()));
        }

        if (part instanceof ImagePart imagePart && supportsNativeImages(runtime)) {
            return imageToPart(imagePart)
                    .map(List::of)
                    .orElseGet(() -> List.of(toTextPart(imagePart.asTextProjection())));
        }

        return List.of(toTextPart(part.asTextProjection()));
    }

    private Optional<ChatCompletionContentPart> imageToPart(ImagePart imagePart) {
        return ProviderAttachmentSupport.loadEncodedImage(imagePart)
                .map(encodedImage -> {
                    String dataUrl = "data:%s;base64,%s".formatted(encodedImage.mediaType(), encodedImage.base64Data());

                    ChatCompletionContentPartImage.ImageUrl imageUrl = ChatCompletionContentPartImage.ImageUrl.builder()
                            .url(dataUrl)
                            .build();

                    return ChatCompletionContentPart.ofImageUrl(
                            ChatCompletionContentPartImage.builder()
                                    .imageUrl(imageUrl)
                                    .build()
                    );
                });
    }

    private ChatCompletionContentPart toTextPart(String text) {
        return ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder()
                        .text(text)
                        .build()
        );
    }

    private boolean supportsNativeImages(ProviderRuntime runtime) {
        return ProviderCapabilityResolver.supportsImageInput(
                runtime.descriptor().capabilities(),
                runtime.descriptor().name(),
                runtime.selectedModel()
        );
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
