package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.WebSearchSource;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.MistralNativeWebSearchSupport;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.stream.Collectors.joining;

public class MistralConversationsWebSearchClient implements ChatCompletionClient {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final int MAX_ERROR_BODY_BYTES = 8_192;

    private final ProviderAttachmentSupport attachmentSupport;
    private final MistralSseTransport transport;
    private final URI endpointOverride;

    public MistralConversationsWebSearchClient(@NonNull ProviderAttachmentSupport attachmentSupport) {
        this(attachmentSupport, new MistralSseTransport(), null);
    }

    MistralConversationsWebSearchClient(
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull MistralSseTransport transport,
            URI endpointOverride
    ) {
        this.attachmentSupport = attachmentSupport;
        this.transport = transport;
        this.endpointOverride = endpointOverride;
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
        throw new IllegalArgumentException("Mistral native Web Search must be enabled explicitly.");
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
            throw new IllegalArgumentException("Mistral native Web Search must be enabled explicitly.");
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
        MistralConversationsApi.Request requestBody = requestBody(
                runtime.selectedModel(),
                projectionPlan,
                reasoningLevel
        );
        URI endpoint = endpointOverride == null
                ? MistralNativeWebSearchSupport.conversationsUri(runtime.baseUrl())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Mistral native Web Search requires the official API endpoint."
                        ))
                : endpointOverride;
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer %s".formatted(runtime.apiKey()),
                "Content-Type", "application/json",
                "Accept", "text/event-stream"
        );

        streamResponse(
                runtime,
                endpoint,
                headers,
                JSON.writeBytes(requestBody),
                reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel,
                onToken,
                onThinkingToken,
                onCitation,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    private MistralConversationsApi.Request requestBody(
            String model,
            AttachmentProjectionPlan projectionPlan,
            ReasoningLevel reasoningLevel
    ) {
        List<ProjectedMessage> messages = projectionPlan.messages();
        int firstInputIndex = IntStream.range(0, messages.size())
                .filter(index -> messages.get(index).role() != Role.SYSTEM)
                .findFirst()
                .orElse(messages.size());
        List<ProjectedMessage> inputMessages = messages.subList(firstInputIndex, messages.size());
        if (inputMessages.stream().anyMatch(message -> message.role() == Role.SYSTEM
                && StringUtils.isNotBlank(projectedText(message)))) {
            throw new IllegalArgumentException(
                    "Mistral Conversations cannot preserve system messages after conversation input."
            );
        }

        List<MistralConversationsApi.Input> inputs = inputMessages.stream()
                .map(this::toInput)
                .filter(input -> StringUtils.isNotBlank(input.content()))
                .toList();
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Mistral native Web Search requires usable message text.");
        }

        String instructions = messages.subList(0, firstInputIndex).stream()
                .map(this::projectedText)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n\n"));
        ReasoningLevel level = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        return new MistralConversationsApi.Request(
                model,
                true,
                false,
                inputs,
                StringUtils.isBlank(instructions) ? null : instructions,
                List.of(new MistralConversationsApi.Tool("web_search")),
                new MistralConversationsApi.CompletionArgs(reasoningEffort(level))
        );
    }

    private MistralConversationsApi.Input toInput(ProjectedMessage message) {
        return new MistralConversationsApi.Input(
                message.role() == Role.USER ? "user" : "assistant",
                projectedText(message)
        );
    }

    private String projectedText(ProjectedMessage message) {
        return message.parts().stream()
                .map(AttachmentProjectionPlan::textFallback)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n\n"));
    }

    private String reasoningEffort(ReasoningLevel level) {
        return level.enabled() ? "high" : "none";
    }

    private void streamResponse(
            ProviderRuntime runtime,
            URI endpoint,
            Map<String, String> headers,
            byte[] requestBody,
            ReasoningLevel reasoningLevel,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        try (MistralSseTransport.Call call = transport.open(endpoint, headers, requestBody)) {
            try {
                registerActiveStream.accept(call);
            } catch (RuntimeException | Error e) {
                call.close();
                try {
                    clearActiveStream.run();
                } catch (RuntimeException | Error clearFailure) {
                    e.addSuppressed(clearFailure);
                }
                throw e;
            }
            try {
                MistralSseTransport.Response response = call.await(isCancelled);
                if (response == null) {
                    return;
                }
                try (response) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw ProviderExceptionMapper.map(
                                new IOException("Mistral Conversations request failed with HTTP %d: %s".formatted(
                                        response.statusCode(),
                                        readErrorMessage(response.body())
                                )),
                                runtime.apiKey()
                        );
                    }
                    parseEvents(
                            response.body(),
                            reasoningLevel,
                            onToken,
                            onThinkingToken,
                            onCitation,
                            isCancelled
                    );
                }
            } catch (CancellationException e) {
                if (!shouldStop(isCancelled)) {
                    throw e;
                }
            } catch (IOException e) {
                if (!shouldStop(isCancelled)) {
                    throw ProviderExceptionMapper.map(e, runtime.apiKey());
                }
            } finally {
                clearActiveStream.run();
            }
        }
    }

    private void parseEvents(
            InputStream inputStream,
            ReasoningLevel reasoningLevel,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled
    ) throws IOException {
        CitationAccumulator citations = new CitationAccumulator();
        var emittedAnswer = new AtomicBoolean();
        StringBuilder data = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while (!shouldStop(isCancelled) && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    boolean completed = handlePayload(
                            data.toString(),
                            reasoningLevel,
                            citations,
                            onToken,
                            onThinkingToken,
                            onCitation,
                            emittedAnswer,
                            isCancelled
                    );
                    data.setLength(0);
                    if (completed) {
                        ensureAnswerEmitted(emittedAnswer);
                        return;
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
        }
        if (shouldStop(isCancelled)) {
            return;
        }
        if (!data.isEmpty() && handlePayload(
                data.toString(),
                reasoningLevel,
                citations,
                onToken,
                onThinkingToken,
                onCitation,
                emittedAnswer,
                isCancelled
        )) {
            ensureAnswerEmitted(emittedAnswer);
            return;
        }
        throw new IOException("Mistral Conversations stream ended before a completion event.");
    }

    private void ensureAnswerEmitted(AtomicBoolean emittedAnswer) throws IOException {
        if (!emittedAnswer.get()) {
            throw new IOException("Mistral Conversations completed without answer output.");
        }
    }

    private boolean handlePayload(
            String payload,
            ReasoningLevel reasoningLevel,
            CitationAccumulator citations,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<CitationRef> onCitation,
            AtomicBoolean emittedAnswer,
            BooleanSupplier isCancelled
    ) throws IOException {
        if (StringUtils.isBlank(payload)) {
            return false;
        }
        if ("[DONE]".equals(payload)) {
            return true;
        }

        MistralConversationsApi.Event event;
        try {
            event = JSON.read(payload, MistralConversationsApi.Event.class);
        } catch (Exception e) {
            throw new IOException("Mistral Conversations returned malformed SSE data.", e);
        }
        if (event == null || StringUtils.isBlank(event.type())) {
            throw new IOException("Mistral Conversations returned malformed SSE data.");
        }
        return switch (event.type()) {
            case "message.output.delta" -> {
                handleContent(
                        event.content(),
                        reasoningLevel,
                        citations,
                        onToken,
                        onThinkingToken,
                        onCitation,
                        emittedAnswer,
                        isCancelled
                );
                yield false;
            }
            case "conversation.response.done" -> true;
            case "conversation.response.error" -> throw new IOException(
                    "Mistral Conversations error %s: %s".formatted(
                            StringUtils.defaultIfBlank(event.code(), "unknown"),
                            StringUtils.defaultIfBlank(event.message(), "Request failed")
                    )
            );
            default -> false;
        };
    }

    private void handleContent(
            MistralConversationsApi.Content content,
            ReasoningLevel reasoningLevel,
            CitationAccumulator citations,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<CitationRef> onCitation,
            AtomicBoolean emittedAnswer,
            BooleanSupplier isCancelled
    ) {
        if (content == null || shouldStop(isCancelled)) {
            return;
        }
        switch (content) {
            case MistralConversationsApi.TextValue text ->
                    emitAnswer(text.value(), emittedAnswer, onToken, isCancelled);
            case MistralConversationsApi.Chunks chunks -> chunks.values().forEach(chunk -> handleContent(
                    chunk,
                    reasoningLevel,
                    citations,
                    onToken,
                    onThinkingToken,
                    onCitation,
                    emittedAnswer,
                    isCancelled
            ));
            case MistralConversationsApi.Text text ->
                    emitAnswer(text.value(), emittedAnswer, onToken, isCancelled);
            case MistralConversationsApi.ToolReference reference ->
                    emitCitation(reference, citations, onToken, onCitation, isCancelled);
            case MistralConversationsApi.Thinking thinking -> {
                if (reasoningLevel.enabled()) {
                    emitThinking(thinking.value(), onThinkingToken, isCancelled);
                }
            }
            case MistralConversationsApi.Unknown ignored -> {
            }
        }
    }

    private void emitAnswer(
            String value,
            AtomicBoolean emittedAnswer,
            Consumer<String> onToken,
            BooleanSupplier isCancelled
    ) {
        if (StringUtils.isNotBlank(value)) {
            emittedAnswer.set(true);
        }
        emit(value, onToken, isCancelled);
    }

    private void emitThinking(
            MistralConversationsApi.Content thinking,
            Consumer<String> onThinkingToken,
            BooleanSupplier isCancelled
    ) {
        if (thinking == null || shouldStop(isCancelled)) {
            return;
        }
        switch (thinking) {
            case MistralConversationsApi.TextValue text -> emit(text.value(), onThinkingToken, isCancelled);
            case MistralConversationsApi.Chunks chunks ->
                    chunks.values().forEach(chunk -> emitThinking(chunk, onThinkingToken, isCancelled));
            case MistralConversationsApi.Text text -> emit(text.value(), onThinkingToken, isCancelled);
            default -> {
            }
        }
    }

    private void emitCitation(
            MistralConversationsApi.ToolReference content,
            CitationAccumulator citations,
            Consumer<String> onToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled
    ) {
        UrlCitationMapper.fromUrl(
                content.title(),
                content.url(),
                content.description()
        ).ifPresent(mapped -> {
            var newCitation = citations.addNew(mapped);
            CitationRef citation = newCitation.orElseGet(() -> citations.add(mapped));
            if (shouldStop(isCancelled)) {
                return;
            }
            newCitation.ifPresent(onCitation);
            if (!shouldStop(isCancelled)) {
                onToken.accept(" [%d]".formatted(citation.number()));
            }
        });
    }

    private void emit(String value, Consumer<String> callback, BooleanSupplier isCancelled) {
        if (StringUtils.isNotEmpty(value) && !shouldStop(isCancelled)) {
            callback.accept(value);
        }
    }

    private String readErrorMessage(InputStream inputStream) throws IOException {
        String body = new String(inputStream.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
        if (StringUtils.isBlank(body)) {
            return "empty error response";
        }
        try {
            MistralConversationsApi.ErrorResponse response = JSON.read(
                    body,
                    MistralConversationsApi.ErrorResponse.class
            );
            return StringUtils.defaultIfBlank(response.message(), "unrecognized error response");
        } catch (Exception e) {
            return "unparseable error response";
        }
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
