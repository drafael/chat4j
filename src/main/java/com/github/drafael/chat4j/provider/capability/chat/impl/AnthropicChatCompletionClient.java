package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static java.util.Collections.emptyList;

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
                .map(message -> toParam(message, runtime))
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

    private MessageParam toParam(Message message, ProviderRuntime runtime) {
        MessageParam.Builder builder = MessageParam.builder()
                .role(message.role() == Role.USER
                        ? MessageParam.Role.USER
                        : MessageParam.Role.ASSISTANT
                );

        if (message.role() == Role.USER) {
            List<ContentBlockParam> blocks = mapUserBlocks(message, runtime);
            if (!blocks.isEmpty()) {
                return builder.contentOfBlockParams(blocks).build();
            }
        }

        return builder.content(message.content()).build();
    }

    private List<ContentBlockParam> mapUserBlocks(Message message, ProviderRuntime runtime) {
        if (!supportsNativeImages(runtime) || message.parts().isEmpty()) {
            return emptyList();
        }

        List<ContentBlockParam> blocks = new ArrayList<>();
        message.parts().stream()
                .map(this::mapPart)
                .flatMap(List::stream)
                .forEach(blocks::add);

        return blocks;
    }

    private List<ContentBlockParam> mapPart(ContentPart part) {
        if (part instanceof TextPart textPart && !textPart.text().isBlank()) {
            return List.of(toTextBlock(textPart.text()));
        }

        if (part instanceof ImagePart imagePart) {
            return imageToBlock(imagePart)
                    .map(List::of)
                    .orElseGet(() -> List.of(toTextBlock(imagePart.asTextProjection())));
        }

        return List.of(toTextBlock(part.asTextProjection()));
    }

    private Optional<ContentBlockParam> imageToBlock(ImagePart imagePart) {
        return ProviderAttachmentSupport.loadEncodedImage(imagePart)
                .flatMap(encodedImage -> resolveMediaType(encodedImage.mediaType())
                        .map(mediaType -> {
                            Base64ImageSource source = Base64ImageSource.builder()
                                    .mediaType(mediaType)
                                    .data(encodedImage.base64Data())
                                    .build();

                            ImageBlockParam imageBlock = ImageBlockParam.builder()
                                    .source(source)
                                    .build();

                            return ContentBlockParam.ofImage(imageBlock);
                        }));
    }

    private Optional<Base64ImageSource.MediaType> resolveMediaType(String mimeType) {
        if (StringUtils.isBlank(mimeType)) {
            return Optional.empty();
        }

        String normalized = mimeType.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "image/jpeg", "image/jpg" -> Optional.of(Base64ImageSource.MediaType.IMAGE_JPEG);
            case "image/png" -> Optional.of(Base64ImageSource.MediaType.IMAGE_PNG);
            case "image/gif" -> Optional.of(Base64ImageSource.MediaType.IMAGE_GIF);
            case "image/webp" -> Optional.of(Base64ImageSource.MediaType.IMAGE_WEBP);
            default -> Optional.empty();
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

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
