package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.CopilotRequestHeaders;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.models.responses.WebSearchTool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;

@Slf4j
public class OpenAiChatCompletionClient implements ChatCompletionClient {

    private static final String COPILOT_PROVIDER_NAME = "GitHub Copilot";
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/chat/completions";
    private static final String RESPONSES_ENDPOINT = "/responses";
    private static final Map<CopilotModelKey, CopilotEndpointMode> COPILOT_ENDPOINT_BY_MODEL = new ConcurrentHashMap<>();
    private static final AtomicReference<String> LAST_COPILOT_MODEL_ID = new AtomicReference<>(null);
    private static final AtomicReference<String> LAST_COPILOT_ENDPOINT = new AtomicReference<>(null);
    private static final AtomicLong LAST_COPILOT_ENDPOINT_UPDATE_EPOCH_MS = new AtomicLong(0L);

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
        ReasoningLevel normalizedReasoningLevel = normalizeReasoningLevel(reasoningLevel);
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(runtime.apiKey())
                .baseUrl(runtime.baseUrl());

        if (COPILOT_PROVIDER_NAME.equals(runtime.descriptor().name())) {
            CopilotRequestHeaders.asMap().forEach(builder::putHeader);
        }

        OpenAIClient client = builder.build();
        if (COPILOT_PROVIDER_NAME.equals(runtime.descriptor().name())) {
            streamCopilotCompletion(runtime, history, client, normalizedReasoningLevel, onToken, onThinkingToken, isCancelled, registerActiveStream, clearActiveStream);
            return;
        }

        if (webSearchOptions != null && webSearchOptions.enabled() && "OpenAI".equals(runtime.descriptor().name())) {
            if (supportsFlattenedResponsesInput(history)) {
                streamWithResponses(runtime, history, client, normalizedReasoningLevel, true, onToken, onThinkingToken, isCancelled, registerActiveStream, clearActiveStream);
                return;
            }
            log.info("Skipping OpenAI native web search for model {} because the request contains non-text attachments", runtime.selectedModel());
        }

        streamWithChatCompletions(runtime, history, client, normalizedReasoningLevel, onToken, onThinkingToken, isCancelled, registerActiveStream, clearActiveStream);
    }

    private boolean supportsFlattenedResponsesInput(List<Message> history) {
        return history.stream()
                .flatMap(message -> message.parts().stream())
                .allMatch(part -> part instanceof TextPart);
    }

    private void streamCopilotCompletion(
            ProviderRuntime runtime,
            List<Message> history,
            OpenAIClient client,
            ReasoningLevel reasoningLevel,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        String modelId = runtime.selectedModel();
        CopilotModelKey modelKey = new CopilotModelKey(runtime.baseUrl(), modelId);
        CopilotEndpointMode mode = COPILOT_ENDPOINT_BY_MODEL.getOrDefault(modelKey, preferredCopilotEndpointMode(runtime));

        if (mode == CopilotEndpointMode.RESPONSES) {
            try {
                streamWithResponses(runtime, history, client, reasoningLevel, false, onToken, onThinkingToken, isCancelled, registerActiveStream, clearActiveStream);
                updateCopilotDiagnostics(modelId, RESPONSES_ENDPOINT);
                return;
            } catch (Exception e) {
                if (!isUnsupportedApiForEndpoint(e, RESPONSES_ENDPOINT)) {
                    throw e;
                }
                log.info("Switching Copilot endpoint for model {} from {} to {} after failure: {}",
                        StringUtils.defaultIfBlank(modelId, "unknown"),
                        RESPONSES_ENDPOINT,
                        CHAT_COMPLETIONS_ENDPOINT,
                        ExceptionUtils.getMessage(e));
                COPILOT_ENDPOINT_BY_MODEL.put(modelKey, CopilotEndpointMode.CHAT_COMPLETIONS);
                streamWithChatCompletions(runtime, history, client, reasoningLevel, onToken, onThinkingToken, isCancelled, registerActiveStream, clearActiveStream);
                updateCopilotDiagnostics(modelId, CHAT_COMPLETIONS_ENDPOINT);
                return;
            }
        }

        try {
            streamWithChatCompletions(runtime, history, client, reasoningLevel, onToken, onThinkingToken, isCancelled, registerActiveStream, clearActiveStream);
            updateCopilotDiagnostics(modelId, CHAT_COMPLETIONS_ENDPOINT);
        } catch (Exception e) {
            if (!isUnsupportedApiForEndpoint(e, CHAT_COMPLETIONS_ENDPOINT)) {
                throw e;
            }
            log.info("Switching Copilot endpoint for model {} from {} to {} after failure: {}",
                    StringUtils.defaultIfBlank(modelId, "unknown"),
                    CHAT_COMPLETIONS_ENDPOINT,
                    RESPONSES_ENDPOINT,
                    ExceptionUtils.getMessage(e));
            COPILOT_ENDPOINT_BY_MODEL.put(modelKey, CopilotEndpointMode.RESPONSES);
            streamWithResponses(runtime, history, client, reasoningLevel, false, onToken, onThinkingToken, isCancelled, registerActiveStream, clearActiveStream);
            updateCopilotDiagnostics(modelId, RESPONSES_ENDPOINT);
        }
    }

    private void streamWithChatCompletions(
            ProviderRuntime runtime,
            List<Message> history,
            OpenAIClient client,
            ReasoningLevel reasoningLevel,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        List<ChatCompletionMessageParam> messages = history.stream()
                .map(message -> toParam(message, runtime))
                .toList();

        List<ReasoningLevel> attempts = reasoningAttempts(reasoningLevel);
        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            ReasoningLevel attemptLevel = attempts.get(attemptIndex);
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(runtime.selectedModel()))
                    .messages(messages);
            applyChatCompletionsThinkingHints(paramsBuilder, runtime, attemptLevel);
            ChatCompletionCreateParams params = paramsBuilder.build();

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
                        if (attemptLevel.enabled()) {
                            emitChatCompletionsThinkingDelta(choice, onThinkingToken);
                        }
                    }
                }
                return;
            } catch (Exception e) {
                if (!shouldRetryWithLowerReasoning(attempts, attemptIndex, e)) {
                    throw e;
                }
                log.info("Retrying {} model {} with lower reasoning effort ({} -> {}) after failure: {}",
                        runtime.descriptor().name(),
                        runtime.selectedModel(),
                        attempts.get(attemptIndex),
                        attempts.get(attemptIndex + 1),
                        ExceptionUtils.getMessage(e));
            } finally {
                clearActiveStream.run();
            }
        }
    }

    private void streamWithResponses(
            ProviderRuntime runtime,
            List<Message> history,
            OpenAIClient client,
            ReasoningLevel reasoningLevel,
            boolean webSearchEnabled,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        String input = StringUtils.defaultIfBlank(toResponsesInput(history), "Continue.");

        List<ReasoningLevel> attempts = reasoningAttempts(reasoningLevel);
        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            ReasoningLevel attemptLevel = attempts.get(attemptIndex);
            ResponseCreateParams.Builder paramsBuilder = ResponseCreateParams.builder()
                    .model(runtime.selectedModel())
                    .input(input);
            applyResponsesReasoningHints(paramsBuilder, attemptLevel);
            if (webSearchEnabled) {
                paramsBuilder.addTool(WebSearchTool.builder().build());
            }
            ResponseCreateParams params = paramsBuilder.build();

            try (StreamResponse<ResponseStreamEvent> stream = client.responses().createStreaming(params)) {
                registerActiveStream.accept(stream);
                boolean emittedReasoningSummary = false;
                Iterator<ResponseStreamEvent> iterator = stream.stream().iterator();
                while (iterator.hasNext()) {
                    if (shouldStop(isCancelled)) {
                        return;
                    }

                    ResponseStreamEvent event = iterator.next();
                    event.outputTextDelta()
                            .map(ResponseTextDeltaEvent::delta)
                            .filter(OpenAiChatCompletionClient::shouldEmitOutputDelta)
                            .ifPresent(onToken);

                    if (attemptLevel.enabled()) {
                        String reasoningSummaryDelta = event.reasoningSummaryTextDelta()
                                .map(summaryDelta -> summaryDelta.delta())
                                .filter(Objects::nonNull)
                                .orElse(null);
                        if (reasoningSummaryDelta != null) {
                            emittedReasoningSummary = true;
                            onThinkingToken.accept(reasoningSummaryDelta);
                        }

                        if (!emittedReasoningSummary) {
                            event.reasoningTextDelta()
                                    .map(reasoningDelta -> reasoningDelta.delta())
                                    .filter(Objects::nonNull)
                                    .ifPresent(onThinkingToken);
                        }
                    }

                    if (event.error().isPresent()) {
                        throw new IllegalStateException(event.error().get().message());
                    }
                }
                return;
            } catch (Exception e) {
                if (!shouldRetryWithLowerReasoning(attempts, attemptIndex, e)) {
                    throw e;
                }
                log.info("Retrying {} model {} with lower reasoning effort ({} -> {}) after failure: {}",
                        runtime.descriptor().name(),
                        runtime.selectedModel(),
                        attempts.get(attemptIndex),
                        attempts.get(attemptIndex + 1),
                        ExceptionUtils.getMessage(e));
            } finally {
                clearActiveStream.run();
            }
        }
    }

    private void applyChatCompletionsThinkingHints(
            ChatCompletionCreateParams.Builder paramsBuilder,
            ProviderRuntime runtime,
            ReasoningLevel reasoningLevel
    ) {
        if (!reasoningLevel.enabled()) {
            return;
        }

        if (shouldEnableOllamaThinking(runtime)) {
            paramsBuilder.putAdditionalBodyProperty("think", JsonValue.from(true));
            return;
        }

        toOpenAiReasoningEffort(reasoningLevel).ifPresent(paramsBuilder::reasoningEffort);
    }

    private void applyResponsesReasoningHints(ResponseCreateParams.Builder paramsBuilder, ReasoningLevel reasoningLevel) {
        if (!reasoningLevel.enabled()) {
            return;
        }

        Reasoning.Builder reasoningBuilder = Reasoning.builder().summary(Reasoning.Summary.DETAILED);
        toOpenAiReasoningEffort(reasoningLevel).ifPresent(reasoningBuilder::effort);
        paramsBuilder.reasoning(reasoningBuilder.build());
    }

    private Optional<ReasoningEffort> toOpenAiReasoningEffort(ReasoningLevel reasoningLevel) {
        return switch (reasoningLevel) {
            case OFF -> Optional.empty();
            case LOW -> Optional.of(ReasoningEffort.LOW);
            case MEDIUM -> Optional.of(ReasoningEffort.MEDIUM);
            case HIGH -> Optional.of(ReasoningEffort.HIGH);
            case EXTRA_HIGH -> Optional.of(ReasoningEffort.XHIGH);
        };
    }

    private List<ReasoningLevel> reasoningAttempts(ReasoningLevel reasoningLevel) {
        return switch (reasoningLevel) {
            case OFF -> List.of(ReasoningLevel.OFF);
            case LOW -> List.of(ReasoningLevel.LOW, ReasoningLevel.OFF);
            case MEDIUM -> List.of(ReasoningLevel.MEDIUM, ReasoningLevel.LOW, ReasoningLevel.OFF);
            case HIGH -> List.of(ReasoningLevel.HIGH, ReasoningLevel.MEDIUM, ReasoningLevel.LOW, ReasoningLevel.OFF);
            case EXTRA_HIGH -> List.of(
                    ReasoningLevel.EXTRA_HIGH,
                    ReasoningLevel.HIGH,
                    ReasoningLevel.MEDIUM,
                    ReasoningLevel.LOW,
                    ReasoningLevel.OFF
            );
        };
    }

    private boolean shouldRetryWithLowerReasoning(List<ReasoningLevel> attempts, int attemptIndex, Exception exception) {
        if (attemptIndex >= attempts.size() - 1) {
            return false;
        }

        return isUnsupportedReasoningEffort(exception);
    }

    private boolean isUnsupportedReasoningEffort(Exception exception) {
        String message = flattenErrorMessage(exception).toLowerCase();
        boolean mentionsReasoning = message.contains("reasoning_effort")
                || message.contains("reasoning.effort")
                || message.contains("reasoning effort")
                || message.contains("reasoning");

        if (!mentionsReasoning) {
            return false;
        }

        return message.contains("unsupported")
                || message.contains("not supported")
                || message.contains("invalid")
                || message.contains("unknown");
    }

    private ReasoningLevel normalizeReasoningLevel(ReasoningLevel reasoningLevel) {
        return reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
    }

    private static boolean shouldEmitOutputDelta(String delta) {
        return StringUtils.isNotEmpty(delta);
    }

    private boolean shouldEnableOllamaThinking(ProviderRuntime runtime) {
        if (!StringUtils.equals(runtime.descriptor().name(), "Ollama")) {
            return false;
        }

        return ProviderCapabilityResolver.supportsReasoning(
                runtime.descriptor().capabilities(),
                runtime.descriptor().name(),
                runtime.selectedModel(),
                runtime.baseUrl(),
                runtime.apiKey()
        );
    }

    private void emitChatCompletionsThinkingDelta(ChatCompletionChunk.Choice choice, Consumer<String> onThinkingToken) {
        if (choice == null || onThinkingToken == null) {
            return;
        }

        boolean emitted = emitThinkingDeltaFromProperties(choice.delta()._additionalProperties(), onThinkingToken);
        if (!emitted) {
            emitThinkingDeltaFromProperties(choice._additionalProperties(), onThinkingToken);
        }
    }

    private boolean emitThinkingDeltaFromProperties(Map<String, JsonValue> properties, Consumer<String> onThinkingToken) {
        if (properties == null || properties.isEmpty()) {
            return false;
        }

        List<String> keys = List.of("reasoning_content", "reasoning", "thinking", "thought");
        for (String key : keys) {
            JsonValue value = properties.get(key);
            String text = thinkingText(value);
            if (StringUtils.isBlank(text)) {
                continue;
            }

            onThinkingToken.accept(text);
            return true;
        }

        return false;
    }

    private String thinkingText(JsonValue value) {
        if (value == null) {
            return null;
        }

        try {
            String text = value.convert(String.class);
            return text == null ? null : text;
        } catch (Exception e) {
            String raw = value.toString();
            if (raw == null) {
                return null;
            }

            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
                return raw.substring(1, raw.length() - 1);
            }
            return raw;
        }
    }

    private String toResponsesInput(List<Message> history) {
        return history.stream()
                .map(this::toResponsesInputLine)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n\n"));
    }

    private String toResponsesInputLine(Message message) {
        String content = StringUtils.trimToEmpty(toResponsesMessageContent(message));
        if (content.isBlank()) {
            return "";
        }

        return "%s: %s".formatted(responseRoleLabel(message), content);
    }

    private String toResponsesMessageContent(Message message) {
        if (message.parts().isEmpty()) {
            return message.content();
        }

        return message.parts().stream()
                .map(ContentPart::asTextProjection)
                .map(StringUtils::trimToEmpty)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n"));
    }

    private String responseRoleLabel(Message message) {
        return switch (message.role()) {
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case SYSTEM -> "System";
        };
    }

    private boolean isUnsupportedApiForEndpoint(Exception exception, String endpoint) {
        String message = flattenErrorMessage(exception).toLowerCase();
        String normalizedEndpoint = endpoint.toLowerCase();
        return message.contains("unsupported_api_for_model")
                || message.contains("not accessible via the %s endpoint".formatted(normalizedEndpoint))
                || message.contains("not accessible via the %s".formatted(normalizedEndpoint))
                || (RESPONSES_ENDPOINT.equals(normalizedEndpoint)
                        && (message.contains("does not support responses api")
                        || message.contains("is not supported via responses api")))
                || (CHAT_COMPLETIONS_ENDPOINT.equals(normalizedEndpoint)
                        && (message.contains("does not support chat completions api")
                        || message.contains("is not supported via chat completions api")));
    }

    private String flattenErrorMessage(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (StringUtils.isNotBlank(current.getMessage())) {
                if (builder.length() > 0) {
                    builder.append(" | ");
                }
                builder.append(current.getMessage());
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    private CopilotEndpointMode preferredCopilotEndpointMode(ProviderRuntime runtime) {
        List<String> supportedEndpoints = runtime.selectedModelSupportedEndpoints();
        if (supportedEndpoints.isEmpty()) {
            return CopilotEndpointMode.RESPONSES;
        }

        if (supportedEndpoints.contains(RESPONSES_ENDPOINT)) {
            return CopilotEndpointMode.RESPONSES;
        }

        if (supportedEndpoints.contains(CHAT_COMPLETIONS_ENDPOINT)) {
            return CopilotEndpointMode.CHAT_COMPLETIONS;
        }

        return CopilotEndpointMode.CHAT_COMPLETIONS;
    }

    private void updateCopilotDiagnostics(String modelId, String endpoint) {
        LAST_COPILOT_MODEL_ID.set(StringUtils.trimToNull(modelId));
        LAST_COPILOT_ENDPOINT.set(StringUtils.trimToNull(endpoint));
        LAST_COPILOT_ENDPOINT_UPDATE_EPOCH_MS.set(System.currentTimeMillis());
    }

    public static CopilotEndpointDiagnosticsSnapshot diagnosticsSnapshot() {
        return new CopilotEndpointDiagnosticsSnapshot(
                LAST_COPILOT_MODEL_ID.get(),
                LAST_COPILOT_ENDPOINT.get(),
                LAST_COPILOT_ENDPOINT_UPDATE_EPOCH_MS.get()
        );
    }

    public record CopilotEndpointDiagnosticsSnapshot(
            String modelId,
            String endpoint,
            long updatedAtEpochMs
    ) {
    }

    private enum CopilotEndpointMode {
        CHAT_COMPLETIONS,
        RESPONSES
    }

    private record CopilotModelKey(String baseUrl, String modelId) {
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
            return emptyList();
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
                runtime.selectedModel(),
                runtime.baseUrl(),
                runtime.apiKey()
        );
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
