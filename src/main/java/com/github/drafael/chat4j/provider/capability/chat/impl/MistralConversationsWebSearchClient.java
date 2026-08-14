package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static java.util.stream.Collectors.joining;

public class MistralConversationsWebSearchClient implements ChatCompletionClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR_BODY_BYTES = 8_192;

    private final ProviderAttachmentSupport attachmentSupport;
    private final HttpClient httpClient;
    private final URI endpointOverride;

    public MistralConversationsWebSearchClient(@NonNull ProviderAttachmentSupport attachmentSupport) {
        this(
                attachmentSupport,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
                null
        );
    }

    MistralConversationsWebSearchClient(
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull HttpClient httpClient,
            URI endpointOverride
    ) {
        this.attachmentSupport = attachmentSupport;
        this.httpClient = httpClient;
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
        ObjectNode requestBody = requestBody(runtime.selectedModel(), projectionPlan, reasoningLevel);
        URI endpoint = endpointOverride == null
                ? MistralNativeWebSearchSupport.conversationsUri(runtime.baseUrl())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Mistral native Web Search requires the official API endpoint."
                        ))
                : endpointOverride;
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer %s".formatted(runtime.apiKey()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(requestBody)))
                .build();

        streamResponse(
                runtime,
                request,
                reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel,
                onToken,
                onThinkingToken,
                onCitation,
                isCancelled,
                registerActiveStream,
                clearActiveStream
        );
    }

    private ObjectNode requestBody(
            String model,
            AttachmentProjectionPlan projectionPlan,
            ReasoningLevel reasoningLevel
    ) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.put("store", false);
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

        ArrayNode inputs = body.putArray("inputs");
        inputMessages.stream()
                .map(this::toInput)
                .filter(input -> StringUtils.isNotBlank(input.path("content").asText()))
                .forEach(inputs::add);
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Mistral native Web Search requires usable message text.");
        }

        String instructions = messages.subList(0, firstInputIndex).stream()
                .map(this::projectedText)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n\n"));
        if (StringUtils.isNotBlank(instructions)) {
            body.put("instructions", instructions);
        }
        body.putArray("tools").addObject().put("type", "web_search");
        ReasoningLevel level = reasoningLevel == null ? ReasoningLevel.OFF : reasoningLevel;
        body.putObject("completion_args").put("reasoning_effort", reasoningEffort(level));
        return body;
    }

    private ObjectNode toInput(ProjectedMessage message) {
        ObjectNode input = JSON.createObjectNode();
        input.put("role", message.role() == Role.USER ? "user" : "assistant");
        input.put("content", projectedText(message));
        return input;
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
            HttpRequest request,
            ReasoningLevel reasoningLevel,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        CompletableFuture<HttpResponse<InputStream>> responseFuture = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );
        try {
            registerActiveStream.accept(() -> cancelResponse(responseFuture));
        } catch (RuntimeException | Error e) {
            cancelResponse(responseFuture);
            try {
                clearActiveStream.run();
            } catch (RuntimeException | Error clearFailure) {
                e.addSuppressed(clearFailure);
            }
            throw e;
        }
        try {
            HttpResponse<InputStream> response = awaitResponse(responseFuture, isCancelled);
            if (response == null) {
                return;
            }
            try (InputStream responseBody = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw ProviderExceptionMapper.map(
                            new IOException("Mistral Conversations request failed with HTTP %d: %s".formatted(
                                    response.statusCode(),
                                    readErrorMessage(responseBody)
                            )),
                            runtime.apiKey()
                    );
                }
                parseEvents(
                        responseBody,
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

    private HttpResponse<InputStream> awaitResponse(
            CompletableFuture<HttpResponse<InputStream>> responseFuture,
            BooleanSupplier isCancelled
    ) throws Exception {
        if (shouldStop(isCancelled)) {
            cancelResponse(responseFuture);
            return null;
        }
        try {
            while (true) {
                if (shouldStop(isCancelled)) {
                    cancelResponse(responseFuture);
                    return null;
                }
                try {
                    HttpResponse<InputStream> response = responseFuture.get(100, TimeUnit.MILLISECONDS);
                    if (shouldStop(isCancelled)) {
                        response.body().close();
                        return null;
                    }
                    return response;
                } catch (TimeoutException ignored) {
                    // Recheck cooperative cancellation while the request is in flight.
                }
            }
        } catch (InterruptedException e) {
            cancelResponse(responseFuture);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IOException("Mistral Conversations request failed", cause);
        }
    }

    private void cancelResponse(CompletableFuture<HttpResponse<InputStream>> responseFuture) {
        if (responseFuture.cancel(true) || !responseFuture.isDone() || responseFuture.isCompletedExceptionally()) {
            return;
        }
        try {
            HttpResponse<InputStream> response = responseFuture.getNow(null);
            if (response != null) {
                response.body().close();
            }
        } catch (IOException | RuntimeException ignored) {
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

        JsonNode event;
        try {
            event = JSON.readTree(payload);
        } catch (Exception e) {
            throw new IOException("Mistral Conversations returned malformed SSE data.", e);
        }
        if (!event.isObject()) {
            throw new IOException("Mistral Conversations returned malformed SSE data.");
        }
        String type = event.path("type").asText("");
        return switch (type) {
            case "message.output.delta" -> {
                handleContent(
                        event.get("content"),
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
                            StringUtils.defaultIfBlank(event.path("code").asText(""), "unknown"),
                            StringUtils.defaultIfBlank(event.path("message").asText(""), "Request failed")
                    )
            );
            default -> false;
        };
    }

    private void handleContent(
            JsonNode content,
            ReasoningLevel reasoningLevel,
            CitationAccumulator citations,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<CitationRef> onCitation,
            AtomicBoolean emittedAnswer,
            BooleanSupplier isCancelled
    ) {
        if (content == null || content.isNull() || shouldStop(isCancelled)) {
            return;
        }
        if (content.isArray()) {
            content.forEach(chunk -> handleContent(
                    chunk,
                    reasoningLevel,
                    citations,
                    onToken,
                    onThinkingToken,
                    onCitation,
                    emittedAnswer,
                    isCancelled
            ));
            return;
        }
        if (content.isTextual()) {
            emitAnswer(content.asText(), emittedAnswer, onToken, isCancelled);
            return;
        }

        switch (content.path("type").asText()) {
            case "text" -> emitAnswer(content.path("text").asText(), emittedAnswer, onToken, isCancelled);
            case "tool_reference" -> emitCitation(content, citations, onToken, onCitation, isCancelled);
            case "thinking" -> {
                if (reasoningLevel.enabled()) {
                    emitThinking(content.path("thinking"), onThinkingToken, isCancelled);
                }
            }
            default -> {
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
            JsonNode thinking,
            Consumer<String> onThinkingToken,
            BooleanSupplier isCancelled
    ) {
        if (thinking == null || shouldStop(isCancelled)) {
            return;
        }
        if (thinking.isArray()) {
            thinking.forEach(chunk -> emitThinking(chunk, onThinkingToken, isCancelled));
            return;
        }
        if (thinking.isTextual()) {
            emit(thinking.asText(), onThinkingToken, isCancelled);
        } else if ("text".equals(thinking.path("type").asText())) {
            emit(thinking.path("text").asText(), onThinkingToken, isCancelled);
        }
    }

    private void emitCitation(
            JsonNode content,
            CitationAccumulator citations,
            Consumer<String> onToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled
    ) {
        UrlCitationMapper.fromUrl(
                content.path("title").asText(),
                content.path("url").asText(),
                content.path("description").asText()
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
            JsonNode root = JSON.readTree(body);
            String message = root == null ? "" : root.path("message").asText("");
            return StringUtils.defaultIfBlank(message, "unrecognized error response");
        } catch (IOException e) {
            return "unparseable error response";
        }
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return isCancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }
}
