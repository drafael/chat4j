package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.NativeImage;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.PlainText;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedPart;
import com.github.drafael.chat4j.provider.support.CopilotRequestHeaders;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.github.drafael.chat4j.provider.support.TogetherModelSupport;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIServiceException;
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
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputTextAnnotationAddedEvent;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningTextDeltaEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.models.responses.WebSearchTool;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static java.util.stream.Collectors.joining;

@Slf4j
public class OpenAiChatCompletionClient implements ChatCompletionClient {

    private static final String COPILOT_PROVIDER_NAME = "GitHub Copilot";
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/chat/completions";
    private static final String RESPONSES_ENDPOINT = "/responses";

    private final ProviderAttachmentSupport attachmentSupport;

    public OpenAiChatCompletionClient(@NonNull ProviderAttachmentSupport attachmentSupport) {
        this.attachmentSupport = attachmentSupport;
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
        streamCompletion(
                runtime,
                history,
                reasoningLevel,
                webSearchOptions,
                onToken,
                onThinkingToken,
                part -> {
                },
                citation -> {
                },
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
        BooleanSupplier isCancelled,
        Consumer<AutoCloseable> registerActiveStream,
        Runnable clearActiveStream
    ) throws Exception {
        Consumer<CitationRef> safeOnCitation = noOpIfNull(onCitation);
        ReasoningLevel normalizedReasoningLevel = normalizeReasoningLevel(reasoningLevel);
        AttachmentProjectionPlan projectionPlan = AttachmentProjectionPlan.create(
                history,
                attachmentSupport,
                AttachmentProjectionPlan.openAi(supportsNativeImages(runtime)),
                isCancelled
        );
        if (shouldStop(isCancelled)) {
            return;
        }

        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(runtime.apiKey())
                .baseUrl(runtime.baseUrl());
        if (COPILOT_PROVIDER_NAME.equals(runtime.descriptor().name())) {
            CopilotRequestHeaders.asMap().forEach(builder::putHeader);
        }

        OpenAIClient client = builder.build();
        try {
            if (COPILOT_PROVIDER_NAME.equals(runtime.descriptor().name())) {
                streamCopilotCompletion(
                        runtime,
                        projectionPlan,
                        client,
                        normalizedReasoningLevel,
                        webSearchOptions,
                        onToken,
                        onThinkingToken,
                        safeOnCitation,
                        isCancelled,
                        registerActiveStream,
                        clearActiveStream
                );
                return;
            }

            if (isOpenAiSearchPreviewModel(runtime)) {
                if (webSearchOptions != null && webSearchOptions.enabled()
                        && !Strings.CS.equals(runtime.baseUrl(), runtime.normalizedDefaultBaseUrl())) {
                    throw new IllegalArgumentException("Native Web Search is unavailable for this provider endpoint.");
                }
                streamWithChatCompletions(runtime, projectionPlan, client, normalizedReasoningLevel, onToken, onThinkingToken, safeOnCitation, isCancelled, registerActiveStream, clearActiveStream);
                return;
            }

            if (webSearchOptions != null && webSearchOptions.enabled()) {
                if (usesResponsesNativeWebSearchTransport(runtime)) {
                    if (!shouldUseResponsesNativeWebSearch(runtime, webSearchOptions)) {
                        throw new IllegalArgumentException("Native Web Search is unavailable for this provider endpoint.");
                    }
                    streamWithResponses(runtime, projectionPlan, client, normalizedReasoningLevel, true, onToken, onThinkingToken, safeOnCitation, isCancelled, registerActiveStream, clearActiveStream);
                    return;
                }
                if (!usesRequiredChatCompletionsSearchTransport(runtime)) {
                    throw new IllegalArgumentException("Native Web Search is unavailable for this provider transport.");
                }
            }

            streamWithChatCompletions(runtime, projectionPlan, client, normalizedReasoningLevel, onToken, onThinkingToken, safeOnCitation, isCancelled, registerActiveStream, clearActiveStream);
        } catch (OpenAIServiceException e) {
            if (TogetherModelSupport.isTogether(runtime.descriptor().name())) {
                throw ProviderExceptionMapper.mapHttpStatus(
                        runtime.descriptor().name(),
                        runtime.baseUrl(),
                        e.statusCode(),
                        e.type().orElse(""),
                        e.code().orElse(""),
                        e.getMessage(),
                        runtime.apiKey()
                );
            }
            throw e;
        } finally {
            client.close();
        }
    }

    private boolean usesResponsesNativeWebSearchTransport(ProviderRuntime runtime) {
        if (runtime == null || runtime.descriptor() == null) {
            return false;
        }
        String providerName = runtime.descriptor().name();
        return Strings.CS.equals(providerName, "OpenAI") || Strings.CS.equals(providerName, "xAI");
    }

    private boolean usesRequiredChatCompletionsSearchTransport(ProviderRuntime runtime) {
        if (runtime == null || runtime.descriptor() == null) {
            return false;
        }
        String providerName = runtime.descriptor().name();
        if (!Strings.CS.equals(providerName, "Groq") && !Strings.CS.equals(providerName, "OpenRouter")) {
            return false;
        }
        return ProviderCapabilityResolver.nativeWebSearchOutcome(
                providerName,
                runtime.selectedModel(),
                runtime.baseUrl(),
                runtime.normalizedDefaultBaseUrl()
        ).required();
    }

    private boolean shouldUseResponsesNativeWebSearch(ProviderRuntime runtime, WebSearchRequestOptions webSearchOptions) {
        if (runtime == null || runtime.descriptor() == null || webSearchOptions == null || !webSearchOptions.enabled()) {
            return false;
        }
        String providerName = runtime.descriptor().name();
        if (Strings.CS.equals(providerName, "OpenAI")) {
            return Strings.CS.equals(runtime.baseUrl(), runtime.normalizedDefaultBaseUrl())
                    && !isOpenAiSearchPreviewModel(runtime);
        }
        return Strings.CS.equals(providerName, "xAI")
                && Strings.CS.equals(runtime.baseUrl(), runtime.normalizedDefaultBaseUrl())
                && ProviderCapabilityResolver.supportsXaiNativeWebSearch(runtime.selectedModel());
    }

    private boolean isOpenAiSearchPreviewModel(ProviderRuntime runtime) {
        return runtime != null
                && runtime.descriptor() != null
                && Strings.CS.equals(runtime.descriptor().name(), "OpenAI")
                && ProviderCapabilityResolver.isOpenAiSearchPreviewModel(runtime.selectedModel());
    }

    private Consumer<CitationRef> noOpIfNull(Consumer<CitationRef> onCitation) {
        return onCitation == null ? citation -> {
        } : onCitation;
    }

    private <T> Consumer<T> trackOutput(AtomicBoolean emittedOutput, Consumer<T> delegate) {
        return value -> {
            emittedOutput.set(true);
            delegate.accept(value);
        };
    }

    private Consumer<String> trackAssistantOutput(
            AtomicBoolean emittedOutput,
            AtomicBoolean emittedAssistantText,
            Consumer<String> delegate
    ) {
        return value -> {
            emittedOutput.set(true);
            if (StringUtils.isNotBlank(value)) {
                emittedAssistantText.set(true);
            }
            delegate.accept(value);
        };
    }

    private void streamCopilotCompletion(
            ProviderRuntime runtime,
            AttachmentProjectionPlan projectionPlan,
            OpenAIClient client,
            ReasoningLevel reasoningLevel,
            WebSearchRequestOptions webSearchOptions,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        if (webSearchOptions != null && webSearchOptions.enabled()) {
            if (!ProviderCapabilityResolver.supportsCopilotResponsesWebSearchRoute(
                    runtime.descriptor().name(),
                    runtime.selectedModel(),
                    runtime.baseUrl(),
                    runtime.normalizedDefaultBaseUrl()
            ) || (!runtime.selectedModelSupportedEndpoints().contains(RESPONSES_ENDPOINT)
                    && !webSearchOptions.capabilityEvidenceAdmitted())) {
                throw new IllegalArgumentException("Native Web Search is unavailable for this Copilot model or endpoint.");
            }
            streamWithResponses(
                    runtime,
                    projectionPlan,
                    client,
                    reasoningLevel,
                    true,
                    onToken,
                    onThinkingToken,
                    onCitation,
                    isCancelled,
                    registerActiveStream,
                    clearActiveStream
            );
            return;
        }

        String modelId = runtime.selectedModel();
        CopilotEndpointMode mode = preferredCopilotEndpointMode(runtime);
        AtomicBoolean emittedOutput = new AtomicBoolean();
        Consumer<String> trackedOnToken = trackOutput(emittedOutput, onToken);
        Consumer<String> trackedOnThinkingToken = trackOutput(emittedOutput, onThinkingToken);
        Consumer<CitationRef> trackedOnCitation = trackOutput(emittedOutput, onCitation);

        if (mode == CopilotEndpointMode.RESPONSES) {
            try {
                streamWithResponses(runtime, projectionPlan, client, reasoningLevel, false, trackedOnToken, trackedOnThinkingToken, trackedOnCitation, isCancelled, registerActiveStream, clearActiveStream);
                return;
            } catch (Exception e) {
                if (emittedOutput.get() || !isUnsupportedApiForEndpoint(e, RESPONSES_ENDPOINT)) {
                    throw e;
                }
                log.info("Switching Copilot endpoint for model {} from {} to {} after failure: {}",
                        StringUtils.defaultIfBlank(modelId, "unknown"),
                        RESPONSES_ENDPOINT,
                        CHAT_COMPLETIONS_ENDPOINT,
                        ProviderExceptionMapper.sanitizeMessage(ExceptionUtils.getMessage(e), runtime.apiKey()));
                streamWithChatCompletions(runtime, projectionPlan, client, reasoningLevel, trackedOnToken, trackedOnThinkingToken, trackedOnCitation, isCancelled, registerActiveStream, clearActiveStream);
                return;
            }
        }

        try {
            streamWithChatCompletions(runtime, projectionPlan, client, reasoningLevel, trackedOnToken, trackedOnThinkingToken, trackedOnCitation, isCancelled, registerActiveStream, clearActiveStream);
        } catch (Exception e) {
            if (emittedOutput.get() || !isUnsupportedApiForEndpoint(e, CHAT_COMPLETIONS_ENDPOINT)) {
                throw e;
            }
            log.info("Switching Copilot endpoint for model {} from {} to {} after failure: {}",
                    StringUtils.defaultIfBlank(modelId, "unknown"),
                    CHAT_COMPLETIONS_ENDPOINT,
                    RESPONSES_ENDPOINT,
                    ProviderExceptionMapper.sanitizeMessage(ExceptionUtils.getMessage(e), runtime.apiKey()));
            streamWithResponses(runtime, projectionPlan, client, reasoningLevel, false, trackedOnToken, trackedOnThinkingToken, trackedOnCitation, isCancelled, registerActiveStream, clearActiveStream);
        }
    }

    private void streamWithChatCompletions(
            ProviderRuntime runtime,
            AttachmentProjectionPlan projectionPlan,
            OpenAIClient client,
            ReasoningLevel reasoningLevel,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        List<ChatCompletionMessageParam> messages = projectionPlan.messages().stream()
                .map(this::toParam)
                .toList();
        CitationAccumulator citationAccumulator = new CitationAccumulator();
        AtomicBoolean emittedOutput = new AtomicBoolean();
        AtomicBoolean emittedAssistantText = new AtomicBoolean();
        Consumer<String> trackedOnToken = trackAssistantOutput(emittedOutput, emittedAssistantText, onToken);
        Consumer<String> trackedOnThinkingToken = trackOutput(emittedOutput, onThinkingToken);
        Consumer<CitationRef> trackedOnCitation = trackOutput(emittedOutput, onCitation);

        List<ReasoningLevel> attempts = reasoningAttempts(runtime, reasoningLevel);
        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            ReasoningLevel attemptLevel = attempts.get(attemptIndex);
            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(ChatModel.of(runtime.selectedModel()))
                    .messages(messages);
            applyChatCompletionsThinkingHints(paramsBuilder, runtime, attemptLevel);
            ChatCompletionCreateParams params = paramsBuilder.build();
            if (shouldStop(isCancelled)) {
                return;
            }

            try (StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(params)) {
                registerActiveStream.accept(stream);
                boolean terminalChoiceObserved = false;
                Iterator<ChatCompletionChunk> iterator = stream.stream().iterator();
                while (!shouldStop(isCancelled) && iterator.hasNext()) {
                    ChatCompletionChunk chunk = iterator.next();
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    emitChatCompletionsCitations(chunk, citationAccumulator, trackedOnCitation, isCancelled);
                    for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                        if (shouldStop(isCancelled)) {
                            return;
                        }
                        terminalChoiceObserved |= choice.finishReason().isPresent();
                        choice.delta().content()
                                .filter(OpenAiChatCompletionClient::shouldEmitOutputDelta)
                                .ifPresent(trackedOnToken);
                        if (shouldStop(isCancelled)) {
                            return;
                        }
                        if (attemptLevel.enabled()) {
                            emitChatCompletionsThinkingDelta(choice, trackedOnThinkingToken);
                        }
                    }
                }
                if (shouldStop(isCancelled)) {
                    return;
                }
                if (!emittedAssistantText.get()) {
                    throw new IllegalStateException("Chat Completions stream completed without assistant output.");
                }
                if (!terminalChoiceObserved) {
                    throw new IllegalStateException("Chat Completions stream ended before a terminal choice.");
                }
                return;
            } catch (Exception e) {
                if (shouldStop(isCancelled)) {
                    return;
                }
                if (emittedOutput.get() || !shouldRetryWithLowerReasoning(attempts, attemptIndex, e)) {
                    throw e;
                }
                log.info("Retrying {} model {} with lower reasoning effort ({} -> {}) after failure: {}",
                        runtime.descriptor().name(),
                        runtime.selectedModel(),
                        attempts.get(attemptIndex),
                        attempts.get(attemptIndex + 1),
                        ProviderExceptionMapper.sanitizeMessage(ExceptionUtils.getMessage(e), runtime.apiKey()));
            } finally {
                clearActiveStream.run();
            }
        }
    }

    private void streamWithResponses(
            ProviderRuntime runtime,
            AttachmentProjectionPlan projectionPlan,
            OpenAIClient client,
            ReasoningLevel reasoningLevel,
            boolean webSearchEnabled,
            Consumer<String> onToken,
            Consumer<String> onThinkingToken,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled,
            Consumer<AutoCloseable> registerActiveStream,
            Runnable clearActiveStream
    ) throws Exception {
        List<ResponseInputItem> input = toResponsesInput(projectionPlan);
        CitationAccumulator citationAccumulator = new CitationAccumulator();
        AtomicBoolean emittedOutput = new AtomicBoolean();
        AtomicBoolean emittedAssistantText = new AtomicBoolean();
        Consumer<String> trackedOnToken = trackAssistantOutput(emittedOutput, emittedAssistantText, onToken);
        Consumer<String> trackedOnThinkingToken = trackOutput(emittedOutput, onThinkingToken);
        Consumer<CitationRef> trackedOnCitation = trackOutput(emittedOutput, onCitation);

        List<ReasoningLevel> attempts = reasoningAttempts(reasoningLevel);
        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            ReasoningLevel attemptLevel = attempts.get(attemptIndex);
            ResponseCreateParams params = createResponsesParams(
                    runtime,
                    input,
                    attemptLevel,
                    webSearchEnabled
            );
            if (shouldStop(isCancelled)) {
                return;
            }

            try (StreamResponse<ResponseStreamEvent> stream = client.responses().createStreaming(params)) {
                registerActiveStream.accept(stream);
                boolean emittedReasoningSummary = false;
                Iterator<ResponseStreamEvent> iterator = stream.stream().iterator();
                while (!shouldStop(isCancelled) && iterator.hasNext()) {
                    ResponseStreamEvent event = iterator.next();
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    if (event.failed().isPresent()) {
                        throw new IllegalStateException(responseFailureMessage(event.failed().get().response()));
                    }
                    if (event.incomplete().isPresent()) {
                        throw new IllegalStateException(responseIncompleteMessage(event.incomplete().get().response()));
                    }
                    if (event.completed().isPresent()) {
                        if (emittedAssistantText.get()) {
                            return;
                        }
                        throw new IllegalStateException("OpenAI Responses completed without assistant output.");
                    }
                    event.outputTextAnnotationAdded()
                            .ifPresent(annotation -> emitResponseAnnotationCitation(annotation, citationAccumulator, trackedOnCitation));
                    if (shouldStop(isCancelled)) {
                        return;
                    }
                    event.outputTextDelta()
                            .map(ResponseTextDeltaEvent::delta)
                            .filter(OpenAiChatCompletionClient::shouldEmitOutputDelta)
                            .ifPresent(trackedOnToken);
                    if (shouldStop(isCancelled)) {
                        return;
                    }

                    if (attemptLevel.enabled()) {
                        String reasoningSummaryDelta = event.reasoningSummaryTextDelta()
                                .map(ResponseReasoningSummaryTextDeltaEvent::delta)
                                .filter(OpenAiChatCompletionClient::shouldEmitOutputDelta)
                                .orElse(null);
                        if (reasoningSummaryDelta != null) {
                            emittedReasoningSummary = true;
                            trackedOnThinkingToken.accept(reasoningSummaryDelta);
                        }

                        if (!emittedReasoningSummary && !shouldStop(isCancelled)) {
                            event.reasoningTextDelta()
                                    .map(ResponseReasoningTextDeltaEvent::delta)
                                    .filter(OpenAiChatCompletionClient::shouldEmitOutputDelta)
                                    .ifPresent(trackedOnThinkingToken);
                        }
                    }

                    if (!shouldStop(isCancelled) && event.error().isPresent()) {
                        throw new IllegalStateException(event.error().get().message());
                    }
                }
                if (shouldStop(isCancelled)) {
                    return;
                }
                throw new IllegalStateException("OpenAI Responses stream ended before response.completed.");
            } catch (Exception e) {
                if (shouldStop(isCancelled)) {
                    return;
                }
                if (emittedOutput.get() || !shouldRetryWithLowerReasoning(attempts, attemptIndex, e)) {
                    throw e;
                }
                log.info("Retrying {} model {} with lower reasoning effort ({} -> {}) after failure: {}",
                        runtime.descriptor().name(),
                        runtime.selectedModel(),
                        attempts.get(attemptIndex),
                        attempts.get(attemptIndex + 1),
                        ProviderExceptionMapper.sanitizeMessage(ExceptionUtils.getMessage(e), runtime.apiKey()));
            } finally {
                clearActiveStream.run();
            }
        }
    }

    private String responseFailureMessage(Response response) {
        return response.error()
                .map(error -> StringUtils.defaultIfBlank(error.message(), "OpenAI Responses request failed."))
                .orElse("OpenAI Responses request failed.");
    }

    private String responseIncompleteMessage(Response response) {
        String reason = response.incompleteDetails()
                .flatMap(Response.IncompleteDetails::reason)
                .map(Object::toString)
                .orElse("unknown reason");
        return "OpenAI Responses request was incomplete: %s".formatted(reason);
    }

    ResponseCreateParams createResponsesParams(
            ProviderRuntime runtime,
            List<ResponseInputItem> input,
            ReasoningLevel reasoningLevel,
            boolean webSearchEnabled
    ) {
        ResponseCreateParams.Builder paramsBuilder = ResponseCreateParams.builder()
                .model(runtime.selectedModel())
                .inputOfResponse(input);
        applyResponsesReasoningHints(paramsBuilder, reasoningLevel);
        if (webSearchEnabled) {
            paramsBuilder.addTool(WebSearchTool.builder()
                    .type(WebSearchTool.Type.WEB_SEARCH)
                    .build());
        }
        return paramsBuilder.build();
    }

    private void emitResponseAnnotationCitation(
            ResponseOutputTextAnnotationAddedEvent annotation,
            CitationAccumulator citationAccumulator,
            Consumer<CitationRef> onCitation
    ) {
        OpenAiCompatibleCitationMapper.fromResponseAnnotation(annotation._annotation()).stream()
                .map(citationAccumulator::addNew)
                .flatMap(Optional::stream)
                .forEach(onCitation);
    }

    private void emitChatCompletionsCitations(
            ChatCompletionChunk chunk,
            CitationAccumulator citationAccumulator,
            Consumer<CitationRef> onCitation,
            BooleanSupplier isCancelled
    ) {
        OpenAiCompatibleCitationMapper.fromAdditionalProperties(chunk._additionalProperties()).stream()
                .map(citationAccumulator::addNew)
                .flatMap(Optional::stream)
                .takeWhile(ignored -> !shouldStop(isCancelled))
                .forEach(onCitation);
        if (shouldStop(isCancelled)) {
            return;
        }
        chunk.choices().stream()
                .flatMap(choice -> OpenAiCompatibleCitationMapper.fromAdditionalProperties(choice._additionalProperties()).stream())
                .map(citationAccumulator::addNew)
                .flatMap(Optional::stream)
                .takeWhile(ignored -> !shouldStop(isCancelled))
                .forEach(onCitation);
        if (shouldStop(isCancelled)) {
            return;
        }
        chunk.choices().stream()
                .flatMap(choice -> OpenAiCompatibleCitationMapper.fromAdditionalProperties(choice.delta()._additionalProperties()).stream())
                .map(citationAccumulator::addNew)
                .flatMap(Optional::stream)
                .takeWhile(ignored -> !shouldStop(isCancelled))
                .forEach(onCitation);
    }

    private void applyChatCompletionsThinkingHints(
            ChatCompletionCreateParams.Builder paramsBuilder,
            ProviderRuntime runtime,
            ReasoningLevel reasoningLevel
    ) {
        if (TogetherModelSupport.isTogether(runtime.descriptor().name())) {
            applyTogetherReasoningHints(paramsBuilder, runtime, reasoningLevel);
            return;
        }

        if (!reasoningLevel.enabled()) {
            return;
        }

        if (shouldEnableOllamaThinking(runtime)) {
            paramsBuilder.putAdditionalBodyProperty("think", JsonValue.from(true));
            return;
        }

        toOpenAiReasoningEffort(reasoningLevel).ifPresent(paramsBuilder::reasoningEffort);
    }

    private void applyTogetherReasoningHints(
            ChatCompletionCreateParams.Builder paramsBuilder,
            ProviderRuntime runtime,
            ReasoningLevel reasoningLevel
    ) {
        TogetherModelSupport.ReasoningRequest request = TogetherModelSupport.reasoningRequest(
                runtime.baseUrl(),
                runtime.selectedModel(),
                reasoningLevel
        );
        if (request.enabledPropertyPresent()) {
            paramsBuilder.putAdditionalBodyProperty(
                    "reasoning",
                    JsonValue.from(Map.of("enabled", request.enabled()))
            );
        }
        if (StringUtils.isNotBlank(request.effort())) {
            paramsBuilder.putAdditionalBodyProperty("reasoning_effort", JsonValue.from(request.effort()));
        }
        if (request.mediumEffort()) {
            paramsBuilder.putAdditionalBodyProperty(
                    "chat_template_kwargs",
                    JsonValue.from(Map.of("medium_effort", true))
            );
        }
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

    private List<ReasoningLevel> reasoningAttempts(ProviderRuntime runtime, ReasoningLevel reasoningLevel) {
        return TogetherModelSupport.isTogether(runtime.descriptor().name())
                ? List.of(reasoningLevel)
                : reasoningAttempts(reasoningLevel);
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
        if (!Strings.CS.equals(runtime.descriptor().name(), "Ollama")) {
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
        if (ObjectUtils.isEmpty(properties)) {
            return false;
        }

        List<String> keys = List.of("reasoning_content", "reasoning", "thinking", "thought");
        for (String key : keys) {
            JsonValue value = properties.get(key);
            String text = thinkingText(value);
            if (StringUtils.isEmpty(text)) {
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

    private List<ResponseInputItem> toResponsesInput(AttachmentProjectionPlan projectionPlan) {
        List<ResponseInputItem> input = projectionPlan.messages().stream()
                .map(this::toResponseInputItem)
                .toList();
        return input.isEmpty()
                ? List.of(toResponseInputItem(new ProjectedMessage(Role.USER, List.of(new PlainText("Continue.")))))
                : input;
    }

    private ResponseInputItem toResponseInputItem(ProjectedMessage message) {
        List<ResponseInputContent> content = message.parts().stream()
                .map(this::toResponseInputContent)
                .flatMap(Optional::stream)
                .toList();
        EasyInputMessage easyMessage = EasyInputMessage.builder()
                .role(toResponseRole(message.role()))
                .contentOfResponseInputMessageContentList(content)
                .build();
        return ResponseInputItem.ofEasyInputMessage(easyMessage);
    }

    private Optional<ResponseInputContent> toResponseInputContent(ProjectedPart part) {
        if (part instanceof NativeImage image) {
            ResponseInputImage inputImage = ResponseInputImage.builder()
                    .detail(ResponseInputImage.Detail.AUTO)
                    .imageUrl(dataUrl(image))
                    .build();
            return Optional.of(ResponseInputContent.ofInputImage(inputImage));
        }
        String text = AttachmentProjectionPlan.textFallback(part);
        if (StringUtils.isBlank(text)) {
            return Optional.empty();
        }
        ResponseInputText inputText = ResponseInputText.builder().text(text).build();
        return Optional.of(ResponseInputContent.ofInputText(inputText));
    }

    private EasyInputMessage.Role toResponseRole(Role role) {
        return switch (role) {
            case USER -> EasyInputMessage.Role.USER;
            case ASSISTANT -> EasyInputMessage.Role.ASSISTANT;
            case SYSTEM -> EasyInputMessage.Role.SYSTEM;
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
                if (!builder.isEmpty()) {
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

    private enum CopilotEndpointMode {
        CHAT_COMPLETIONS,
        RESPONSES
    }

    private ChatCompletionMessageParam toParam(ProjectedMessage message) {
        return switch (message.role()) {
            case USER -> ChatCompletionMessageParam.ofUser(toUserMessage(message));
            case ASSISTANT -> ChatCompletionMessageParam.ofAssistant(
                    ChatCompletionAssistantMessageParam.builder()
                            .content(projectedText(message))
                            .build());
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                            .content(projectedText(message))
                            .build());
        };
    }

    private ChatCompletionUserMessageParam toUserMessage(ProjectedMessage message) {
        boolean containsNativeImage = message.parts().stream().anyMatch(NativeImage.class::isInstance);
        if (!containsNativeImage) {
            return ChatCompletionUserMessageParam.builder()
                    .content(projectedText(message))
                    .build();
        }
        List<ChatCompletionContentPart> parts = message.parts().stream()
                .map(this::toChatContentPart)
                .flatMap(Optional::stream)
                .toList();
        return ChatCompletionUserMessageParam.builder()
                .contentOfArrayOfContentParts(parts)
                .build();
    }

    private Optional<ChatCompletionContentPart> toChatContentPart(ProjectedPart part) {
        if (part instanceof NativeImage image) {
            ChatCompletionContentPartImage.ImageUrl imageUrl = ChatCompletionContentPartImage.ImageUrl.builder()
                    .url(dataUrl(image))
                    .build();
            return Optional.of(ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder().imageUrl(imageUrl).build()
            ));
        }
        String text = AttachmentProjectionPlan.textFallback(part);
        return StringUtils.isBlank(text) ? Optional.empty() : Optional.of(toTextPart(text));
    }

    private String dataUrl(NativeImage image) {
        return "data:%s;base64,%s".formatted(image.mediaType(), image.base64Data());
    }

    private String projectedText(ProjectedMessage message) {
        return message.parts().stream()
                .map(AttachmentProjectionPlan::textFallback)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n"));
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
