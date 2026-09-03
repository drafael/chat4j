package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.core.error.AuthenticationException;
import com.github.drafael.chat4j.provider.core.error.InvalidRequestException;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCompletedEvent;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseFailedEvent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseReasoningSummaryTextDeltaEvent;
import com.openai.models.responses.ResponseReasoningTextDeltaEvent;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseTextDeltaEvent;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiChatCompletionClientTest {

    private final ProviderAttachmentSupport attachmentSupport = ProviderAttachmentTestSupport.authority();
    private final OpenAiChatCompletionClient subject = new OpenAiChatCompletionClient(attachmentSupport);

    @Test
    @DisplayName("Responses input uses structured ordered message content instead of flattened role-prefixed text")
    void toResponsesInput_whenMessageContainsParts_preservesRoleAndOrderedStructuredContent() throws Exception {
        Path storedImage = ProviderAttachmentTestSupport.managedRoot(attachmentSupport)
                .resolve(UUID.randomUUID().toString());
        Files.write(storedImage, new byte[]{1, 2, 3});
        try {
            List<ContentPart> parts = List.of(
                    new TextPart("Describe this screenshot"),
                    new ImagePart(
                            new AttachmentRef(UUID.randomUUID(), storedImage.toString(), "img.png", "image/png", 3L, "sha"),
                            512,
                            320
                    )
            );
            Message message = new Message(Role.USER, parts, Instant.now());
            AttachmentProjectionPlan plan = AttachmentProjectionPlan.create(
                    List.of(message),
                    attachmentSupport,
                    AttachmentProjectionPlan.openAi(true),
                    () -> false
            );

            List<ResponseInputItem> input = invokeToResponsesInput(plan);

            assertThat(input).hasSize(1);
            var easyMessage = input.getFirst().asEasyInputMessage();
            assertThat(easyMessage.role().known()).isEqualTo(EasyInputMessage.Role.Known.USER);
            assertThat(easyMessage.content().asResponseInputMessageContentList())
                    .satisfiesExactly(
                            content -> assertThat(content.asInputText().text()).isEqualTo("Describe this screenshot"),
                            content -> assertThat(content.asInputImage().imageUrl())
                                    .contains("data:image/png;base64,AQID")
                    );
        } finally {
            Files.deleteIfExists(storedImage);
        }
    }

    @Test
    @DisplayName("Together sends native images only to reviewed hosted vision models")
    void streamWithChatCompletions_whenTogetherImageEndpointVaries_respectsHostedCapability() throws Exception {
        Path storedImage = ProviderAttachmentTestSupport.managedRoot(attachmentSupport)
                .resolve(UUID.randomUUID().toString());
        Files.write(storedImage, new byte[]{1, 2, 3});
        try {
            Message message = new Message(
                    Role.USER,
                    List.of(
                            new TextPart("Describe this screenshot"),
                            new ImagePart(
                                    new AttachmentRef(
                                            UUID.randomUUID(),
                                            storedImage.toString(),
                                            "img.png",
                                            "image/png",
                                            3L,
                                            "sha"
                                    ),
                                    512,
                                    320
                            )
                    ),
                    Instant.now()
            );

            ChatCompletionCreateParams hosted = captureChatCompletionParams(
                    runtime("Together", "Qwen/Qwen3.5-9B", "https://api.together.ai/v1"),
                    message
            );
            ChatCompletionCreateParams custom = captureChatCompletionParams(
                    runtime("Together", "Qwen/Qwen3.5-9B", "https://proxy.example/v1"),
                    message
            );

            var hostedContent = hosted.messages().getFirst().asUser().content();
            assertThat(hostedContent.isArrayOfContentParts()).isTrue();
            assertThat(hostedContent.asArrayOfContentParts())
                    .filteredOn(part -> part.isImageUrl())
                    .singleElement()
                    .satisfies(part -> assertThat(part.asImageUrl().imageUrl().url())
                            .isEqualTo("data:image/png;base64,AQID"));
            var customContent = custom.messages().getFirst().asUser().content();
            assertThat(customContent.isText()).isTrue();
            assertThat(customContent.asText())
                    .contains("Describe this screenshot", "img.png")
                    .doesNotContain("base64");
        } finally {
            Files.deleteIfExists(storedImage);
        }
    }

    @Test
    @DisplayName("Responses input supplies a structured fallback when projected history is empty")
    void toResponsesInput_whenHistoryIsEmpty_returnsContinueMessage() throws Exception {
        AttachmentProjectionPlan plan = AttachmentProjectionPlan.create(
                emptyList(),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(true),
                () -> false
        );

        List<ResponseInputItem> input = invokeToResponsesInput(plan);

        assertThat(input).singleElement().satisfies(item -> {
            var message = item.asEasyInputMessage();
            assertThat(message.role().known()).isEqualTo(EasyInputMessage.Role.Known.USER);
            assertThat(message.content().asResponseInputMessageContentList())
                    .singleElement()
                    .satisfies(content -> assertThat(content.asInputText().text()).isEqualTo("Continue."));
        });
    }

    @Test
    @DisplayName("Unsupported API detection matches endpoint-specific Copilot errors")
    void isUnsupportedApiForEndpoint_whenErrorIndicatesUnsupportedEndpoint_returnsTrue() throws Exception {
        Exception exception = new IllegalStateException("request failed", new RuntimeException(
                "model \"gpt-5.4-mini\" is not accessible via the /responses endpoint"
        ));

        boolean unsupported = invokeIsUnsupportedApiForEndpoint(exception, "/responses");

        assertThat(unsupported).isTrue();
    }

    @Test
    @DisplayName("Unsupported API detection matches current Copilot Responses API wording")
    void isUnsupportedApiForEndpoint_whenCopilotUsesResponsesApiWording_returnsTrue() throws Exception {
        Exception exception = new IllegalStateException("request failed", new RuntimeException(
                "model claude-sonnet-4.6 does not support Responses API."
        ));

        boolean unsupported = invokeIsUnsupportedApiForEndpoint(exception, "/responses");

        assertThat(unsupported).isTrue();
    }

    @Test
    @DisplayName("Unsupported API detection ignores unrelated errors")
    void isUnsupportedApiForEndpoint_whenErrorIsUnrelated_returnsFalse() throws Exception {
        Exception exception = new IllegalStateException("rate limited");

        boolean unsupported = invokeIsUnsupportedApiForEndpoint(exception, "/chat/completions");

        assertThat(unsupported).isFalse();
    }

    @Test
    @DisplayName("Copilot endpoint preference uses selected model metadata when available")
    void preferredCopilotEndpointMode_whenRuntimeIncludesChatOnlyMetadata_returnsChatCompletions() throws Exception {
        var runtime = copilotRuntime(List.of("/chat/completions"));

        Object preferredMode = invokePreferredCopilotEndpointMode(runtime);

        assertThat(preferredMode).hasToString("CHAT_COMPLETIONS");
    }

    @Test
    @DisplayName("Copilot endpoint preference does not treat websocket-only metadata as Responses API support")
    void preferredCopilotEndpointMode_whenRuntimeIncludesWebsocketOnlyMetadata_returnsChatCompletions() throws Exception {
        var runtime = copilotRuntime(List.of("ws:/responses"));

        Object preferredMode = invokePreferredCopilotEndpointMode(runtime);

        assertThat(preferredMode).hasToString("CHAT_COMPLETIONS");
    }

    @Test
    @DisplayName("Copilot search failures remain on Responses without Chat Completions fallback")
    void streamCopilotCompletion_whenSearchResponsesFails_doesNotFallback() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        when(client.responses()).thenReturn(responses);
        when(responses.createStreaming(any(ResponseCreateParams.class)))
                .thenThrow(new IllegalStateException("search unavailable"));
        assertThatThrownBy(() -> invokeStreamCopilotCompletion(plan, client))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessage("search unavailable");
        verify(client, never()).chat();
    }

    @Test
    @DisplayName("Copilot search transport requires captured Responses endpoint evidence")
    void streamCopilotCompletion_whenSearchRuntimeLacksResponsesEvidence_rejectsBeforeTransport() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime("gpt-5.4-mini", emptyList()),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
                ignored -> {
                }
        )).hasRootCauseInstanceOf(IllegalArgumentException.class);
        verify(client, never()).responses();
    }

    @Test
    @DisplayName("Copilot search transport preserves previously admitted capability evidence")
    void streamCopilotCompletion_whenContinuationEvidenceWasAdmitted_usesResponses() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        when(client.responses()).thenReturn(responses);
        when(responses.createStreaming(any(ResponseCreateParams.class)))
                .thenThrow(new IllegalStateException("search unavailable"));

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime("gpt-5.4-mini", emptyList()),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true, true),
                ignored -> {
                }
        )).hasRootCauseMessage("search unavailable");
        verify(responses).createStreaming(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("Responses reasoning fallback stops after partial output")
    void streamCopilotCompletion_whenFailedTerminalFollowsOutput_doesNotRetryReasoning() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> stream = mock(StreamResponse.class);
        ResponseStreamEvent deltaEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent textDelta = mock(ResponseTextDeltaEvent.class);
        ResponseStreamEvent failedEvent = mock(ResponseStreamEvent.class);
        ResponseFailedEvent failed = mock(ResponseFailedEvent.class);
        Response response = mock(Response.class);
        ResponseError error = mock(ResponseError.class);
        List<String> tokens = new ArrayList<>();
        when(client.responses()).thenReturn(responses);
        when(responses.createStreaming(any(ResponseCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.of(deltaEvent, failedEvent));
        when(deltaEvent.outputTextDelta()).thenReturn(Optional.of(textDelta));
        when(textDelta.delta()).thenReturn("partial");
        when(failedEvent.failed()).thenReturn(Optional.of(failed));
        when(failed.response()).thenReturn(response);
        when(response.error()).thenReturn(Optional.of(error));
        when(error.message()).thenReturn("invalid reasoning_effort: low");

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime("gpt-5.4-mini", List.of("/responses")),
                ReasoningLevel.LOW,
                new WebSearchRequestOptions(true),
                tokens::add
        )).hasRootCauseMessage("invalid reasoning_effort: low");
        assertThat(tokens).containsExactly("partial");
        verify(responses, times(1)).createStreaming(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("Empty Responses reasoning deltas do not block reasoning fallback")
    void streamCopilotCompletion_whenResponsesReasoningDeltasAreEmpty_retriesReasoning() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> failedStream = mock(StreamResponse.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> successfulStream = mock(StreamResponse.class);
        ResponseStreamEvent reasoningEvent = mock(ResponseStreamEvent.class);
        ResponseReasoningSummaryTextDeltaEvent summaryDelta = mock(ResponseReasoningSummaryTextDeltaEvent.class);
        ResponseReasoningTextDeltaEvent rawDelta = mock(ResponseReasoningTextDeltaEvent.class);
        ResponseStreamEvent failedEvent = mock(ResponseStreamEvent.class);
        ResponseFailedEvent failed = mock(ResponseFailedEvent.class);
        Response failedResponse = mock(Response.class);
        ResponseError error = mock(ResponseError.class);
        ResponseStreamEvent successfulDeltaEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent successfulTextDelta = mock(ResponseTextDeltaEvent.class);
        ResponseStreamEvent completedEvent = mock(ResponseStreamEvent.class);
        when(client.responses()).thenReturn(responses);
        when(responses.createStreaming(any(ResponseCreateParams.class)))
                .thenReturn(failedStream, successfulStream);
        when(failedStream.stream()).thenReturn(Stream.of(reasoningEvent, failedEvent));
        when(successfulStream.stream()).thenReturn(Stream.of(successfulDeltaEvent, completedEvent));
        when(successfulDeltaEvent.outputTextDelta()).thenReturn(Optional.of(successfulTextDelta));
        when(successfulTextDelta.delta()).thenReturn("answer");
        when(reasoningEvent.reasoningSummaryTextDelta()).thenReturn(Optional.of(summaryDelta));
        when(summaryDelta.delta()).thenReturn("");
        when(reasoningEvent.reasoningTextDelta()).thenReturn(Optional.of(rawDelta));
        when(rawDelta.delta()).thenReturn("");
        when(failedEvent.failed()).thenReturn(Optional.of(failed));
        when(failed.response()).thenReturn(failedResponse);
        when(failedResponse.error()).thenReturn(Optional.of(error));
        when(error.message()).thenReturn("invalid reasoning_effort: low");
        when(completedEvent.completed()).thenReturn(Optional.of(mock(ResponseCompletedEvent.class)));

        assertThatCode(() -> invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime("gpt-5.4-mini", List.of("/responses")),
                ReasoningLevel.LOW,
                new WebSearchRequestOptions(true),
                ignored -> {
                }
        )).doesNotThrowAnyException();
        verify(responses, times(2)).createStreaming(any(ResponseCreateParams.class));
    }

    @Test
    @DisplayName("Chat Completions reasoning fallback stops after partial output")
    void streamCopilotCompletion_whenChatStreamFailsAfterOutput_doesNotRetryReasoning() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ChatService chat = mock(ChatService.class);
        ChatCompletionService completions = mock(ChatCompletionService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> stream = mock(StreamResponse.class);
        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
        ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
        ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);
        List<String> tokens = new ArrayList<>();
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.concat(
                Stream.of(chunk),
                Stream.generate(() -> {
                    throw new IllegalStateException("invalid reasoning_effort: low");
                })
        ));
        when(chunk._additionalProperties()).thenReturn(emptyMap());
        when(chunk.choices()).thenReturn(List.of(choice));
        when(choice._additionalProperties()).thenReturn(emptyMap());
        when(choice.delta()).thenReturn(delta);
        when(delta._additionalProperties()).thenReturn(emptyMap());
        when(delta.content()).thenReturn(Optional.of("partial"));

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime(List.of("/chat/completions")),
                ReasoningLevel.LOW,
                WebSearchRequestOptions.disabled(),
                tokens::add
        )).hasRootCauseMessage("invalid reasoning_effort: low");
        assertThat(tokens).containsExactly("partial");
        verify(completions, times(1)).createStreaming(any(ChatCompletionCreateParams.class));
    }

    @Test
    @DisplayName("Empty Chat Completions deltas do not block reasoning fallback")
    void streamCopilotCompletion_whenChatStreamEmitsEmptyDelta_retriesReasoning() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ChatService chat = mock(ChatService.class);
        ChatCompletionService completions = mock(ChatCompletionService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> failedStream = mock(StreamResponse.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> successfulStream = mock(StreamResponse.class);
        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
        ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
        ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);
        List<String> tokens = new ArrayList<>();
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);
        when(completions.createStreaming(any(ChatCompletionCreateParams.class)))
                .thenReturn(failedStream, successfulStream);
        when(failedStream.stream()).thenReturn(Stream.concat(
                Stream.of(chunk),
                Stream.generate(() -> {
                    throw new IllegalStateException("invalid reasoning_effort: low");
                })
        ));
        when(successfulStream.stream()).thenReturn(Stream.of(chunk));
        when(chunk._additionalProperties()).thenReturn(emptyMap());
        when(chunk.choices()).thenReturn(List.of(choice));
        when(choice._additionalProperties()).thenReturn(emptyMap());
        when(choice.delta()).thenReturn(delta);
        when(choice.finishReason()).thenReturn(Optional.of(mock(ChatCompletionChunk.Choice.FinishReason.class)));
        when(delta._additionalProperties()).thenReturn(emptyMap());
        when(delta.content()).thenReturn(Optional.of(""), Optional.of("answer"));

        assertThatCode(() -> invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime(List.of("/chat/completions")),
                ReasoningLevel.LOW,
                WebSearchRequestOptions.disabled(),
                tokens::add
        )).doesNotThrowAnyException();
        assertThat(tokens).containsExactly("answer");
        verify(completions, times(2)).createStreaming(any(ChatCompletionCreateParams.class));
    }

    @Test
    @DisplayName("Chat Completions streams reject partial output without a terminal choice")
    void streamCopilotCompletion_whenChatStreamEndsAfterPartialOutput_throws() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ChatService chat = mock(ChatService.class);
        ChatCompletionService completions = mock(ChatCompletionService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> stream = mock(StreamResponse.class);
        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
        ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
        ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.of(chunk));
        when(chunk._additionalProperties()).thenReturn(emptyMap());
        when(chunk.choices()).thenReturn(List.of(choice));
        when(choice._additionalProperties()).thenReturn(emptyMap());
        when(choice.delta()).thenReturn(delta);
        when(delta._additionalProperties()).thenReturn(emptyMap());
        when(delta.content()).thenReturn(Optional.of("partial"));

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime(List.of("/chat/completions")),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled(),
                ignored -> {
                }
        )).hasRootCauseMessage("Chat Completions stream ended before a terminal choice.");
    }

    @Test
    @DisplayName("Chat Completions streams without assistant output are rejected")
    void streamCopilotCompletion_whenChatStreamCompletesWithoutOutput_throws() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ChatService chat = mock(ChatService.class);
        ChatCompletionService completions = mock(ChatCompletionService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> stream = mock(StreamResponse.class);
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.empty());

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime(List.of("/chat/completions")),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled(),
                ignored -> {
                }
        )).hasRootCauseMessage("Chat Completions stream completed without assistant output.");
    }

    @Test
    @DisplayName("Copilot endpoint fallback stops after partial output")
    void streamCopilotCompletion_whenUnsupportedEndpointFollowsOutput_doesNotSwitchEndpoint() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> stream = mock(StreamResponse.class);
        ResponseStreamEvent deltaEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent textDelta = mock(ResponseTextDeltaEvent.class);
        ResponseStreamEvent failedEvent = mock(ResponseStreamEvent.class);
        ResponseFailedEvent failed = mock(ResponseFailedEvent.class);
        Response response = mock(Response.class);
        ResponseError error = mock(ResponseError.class);
        List<String> tokens = new ArrayList<>();
        when(client.responses()).thenReturn(responses);
        when(responses.createStreaming(any(ResponseCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.of(deltaEvent, failedEvent));
        when(deltaEvent.outputTextDelta()).thenReturn(Optional.of(textDelta));
        when(textDelta.delta()).thenReturn("partial");
        when(failedEvent.failed()).thenReturn(Optional.of(failed));
        when(failed.response()).thenReturn(response);
        when(response.error()).thenReturn(Optional.of(error));
        when(error.message()).thenReturn("model does not support Responses API");

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime(List.of("/responses")),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled(),
                tokens::add
        )).hasRootCauseMessage("model does not support Responses API");
        assertThat(tokens).containsExactly("partial");
        verify(client, never()).chat();
    }

    @Test
    @DisplayName("Responses failed terminal events are surfaced instead of completing partial output")
    void streamCopilotCompletion_whenResponsesEmitsFailedTerminal_throws() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> stream = mock(StreamResponse.class);
        ResponseStreamEvent event = mock(ResponseStreamEvent.class);
        ResponseFailedEvent failed = mock(ResponseFailedEvent.class);
        Response response = mock(Response.class);
        ResponseError error = mock(ResponseError.class);
        when(client.responses()).thenReturn(responses);
        when(responses.createStreaming(any(ResponseCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.of(event));
        when(event.failed()).thenReturn(Optional.of(failed));
        when(failed.response()).thenReturn(response);
        when(response.error()).thenReturn(Optional.of(error));
        when(error.message()).thenReturn("provider terminal failure");

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(plan, client))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessage("provider terminal failure");
    }

    @Test
    @DisplayName("Responses completed terminal events finish successfully")
    void streamCopilotCompletion_whenResponsesEmitsCompletedTerminal_returns() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> stream = mock(StreamResponse.class);
        ResponseStreamEvent deltaEvent = mock(ResponseStreamEvent.class);
        ResponseTextDeltaEvent textDelta = mock(ResponseTextDeltaEvent.class);
        ResponseStreamEvent completedEvent = mock(ResponseStreamEvent.class);
        when(client.responses()).thenReturn(responses);
        when(responses.createStreaming(any(ResponseCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.of(deltaEvent, completedEvent));
        when(deltaEvent.outputTextDelta()).thenReturn(Optional.of(textDelta));
        when(textDelta.delta()).thenReturn("answer");
        when(completedEvent.completed()).thenReturn(Optional.of(mock(ResponseCompletedEvent.class)));

        assertThatCode(() -> invokeStreamCopilotCompletion(plan, client)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Responses completed events without assistant output are rejected")
    void streamCopilotCompletion_whenResponsesCompletesWithoutOutput_throws() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> stream = mock(StreamResponse.class);
        ResponseStreamEvent completedEvent = mock(ResponseStreamEvent.class);
        when(client.responses()).thenReturn(responses);
        when(responses.createStreaming(any(ResponseCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.of(completedEvent));
        when(completedEvent.completed()).thenReturn(Optional.of(mock(ResponseCompletedEvent.class)));

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(plan, client))
                .hasRootCauseMessage("OpenAI Responses completed without assistant output.");
    }

    @Test
    @DisplayName("Responses streams require a completed terminal event")
    void streamCopilotCompletion_whenResponsesEndsWithoutTerminal_throws() throws Exception {
        var plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ResponseService responses = mock(ResponseService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ResponseStreamEvent> stream = mock(StreamResponse.class);
        when(client.responses()).thenReturn(responses);
        when(responses.createStreaming(any(ResponseCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.empty());

        assertThatThrownBy(() -> invokeStreamCopilotCompletion(plan, client))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessage("OpenAI Responses stream ended before response.completed.");
    }

    @Test
    @DisplayName("Responses request includes the hosted Web Search tool only when enabled")
    void createResponsesParams_whenWebSearchEnabled_addsWebSearchTool() throws Exception {
        AttachmentProjectionPlan plan = AttachmentProjectionPlan.create(
                List.of(Message.user("latest release")),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(false),
                () -> false
        );
        List<ResponseInputItem> input = invokeToResponsesInput(plan);
        var runtime = copilotRuntime("gpt-5.4-mini", List.of("/responses"));

        var enabled = subject.createResponsesParams(runtime, input, ReasoningLevel.OFF, true);
        var disabled = subject.createResponsesParams(runtime, input, ReasoningLevel.OFF, false);

        assertThat(enabled.tools()).hasValueSatisfying(tools -> assertThat(tools)
                .singleElement()
                .satisfies(tool -> assertThat(tool.isWebSearch()).isTrue()));
        assertThat(disabled.tools()).isEmpty();
    }

    @Test
    @DisplayName("Reasoning attempts degrade progressively from extra high to off")
    void reasoningAttempts_whenExtraHighSelected_degradesToOff() throws Exception {
        List<ReasoningLevel> attempts = invokeReasoningAttempts(ReasoningLevel.EXTRA_HIGH);

        assertThat(attempts).containsExactly(
                ReasoningLevel.EXTRA_HIGH,
                ReasoningLevel.HIGH,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.LOW,
                ReasoningLevel.OFF
        );
    }

    @Test
    @DisplayName("OpenAI reasoning effort maps extra high to xhigh")
    void toOpenAiReasoningEffort_whenExtraHighSelected_returnsXhigh() throws Exception {
        Optional<ReasoningEffort> effort = invokeToOpenAiReasoningEffort(ReasoningLevel.EXTRA_HIGH);

        assertThat(effort).contains(ReasoningEffort.XHIGH);
    }

    @Test
    @DisplayName("OpenAI reasoning effort maps maximum and ultra to the API maximum")
    void toOpenAiReasoningEffort_whenMaximumOrUltraSelected_returnsMax() throws Exception {
        assertThat(invokeToOpenAiReasoningEffort(ReasoningLevel.MAX)).contains(ReasoningEffort.MAX);
        assertThat(invokeToOpenAiReasoningEffort(ReasoningLevel.ULTRA)).contains(ReasoningEffort.MAX);
        assertThat(invokeReasoningAttempts(ReasoningLevel.ULTRA)).startsWith(
                ReasoningLevel.MAX,
                ReasoningLevel.EXTRA_HIGH
        );
    }

    @Test
    @DisplayName("Unsupported reasoning effort detection matches invalid reasoning parameter errors")
    void isUnsupportedReasoningEffort_whenReasoningEffortIsInvalid_returnsTrue() throws Exception {
        Exception exception = new IllegalStateException("invalid reasoning_effort: xhigh");

        boolean unsupportedReasoningEffort = invokeIsUnsupportedReasoningEffort(exception);

        assertThat(unsupportedReasoningEffort).isTrue();
    }

    @Test
    @DisplayName("Responses-native web search is enabled only for supported OpenAI and xAI models")
    void shouldUseResponsesNativeWebSearch_whenProviderAndModelSupportResponsesSearch_returnsExpectedValue() throws Exception {
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("OpenAI", "gpt-5"), new WebSearchRequestOptions(true))).isTrue();
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("xAI", "grok-4"), new WebSearchRequestOptions(true))).isTrue();
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("xAI", "grok-4-fast"), new WebSearchRequestOptions(true))).isTrue();
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("xAI", "vendor-grok-4"), new WebSearchRequestOptions(true))).isFalse();
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("xAI", "gpt-5"), new WebSearchRequestOptions(true))).isFalse();
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("OpenRouter", "openai/gpt-5:online"), new WebSearchRequestOptions(true))).isFalse();
    }

    @Test
    @DisplayName("Required Chat Completions search transports are recognized without Responses rerouting")
    void usesRequiredChatCompletionsSearchTransport_whenRequiredModelIsSelected_returnsTrue() throws Exception {
        assertThat(invokeUsesRequiredChatCompletionsSearchTransport(runtime("Groq", "compound"))).isTrue();
        assertThat(invokeUsesRequiredChatCompletionsSearchTransport(
                runtime("OpenRouter", "anthropic/claude-haiku:online")
        )).isTrue();
        assertThat(invokeUsesRequiredChatCompletionsSearchTransport(
                runtime("OpenRouter", "perplexity/sonar-pro")
        )).isTrue();
        assertThat(invokeUsesRequiredChatCompletionsSearchTransport(
                runtime("OpenAI", "gpt-4o-search-preview")
        )).isFalse();
        assertThat(invokeShouldUseResponsesNativeWebSearch(
                runtime("OpenAI", "gpt-4o-search-preview"),
                new WebSearchRequestOptions(true)
        )).isFalse();
        assertThat(invokeShouldUseResponsesNativeWebSearch(
                runtime("OpenAI", "gpt-5-search-api"),
                new WebSearchRequestOptions(true)
        )).isFalse();
    }

    @Test
    @DisplayName("Required Web Search models send through Chat Completions instead of Responses")
    void streamCompletion_whenSearchIsRequired_usesChatCompletionsTransport() throws Exception {
        var chatRequests = new AtomicInteger();
        var responsesRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            chatRequests.incrementAndGet();
            byte[] body = """
                    data: {"id":"chatcmpl","object":"chat.completion.chunk","created":0,"model":"test","choices":[{"index":0,"delta":{"content":"answer"},"finish_reason":null}]}

                    data: {"id":"chatcmpl","object":"chat.completion.chunk","created":0,"model":"test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/responses", exchange -> {
            responsesRequests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        try {
            String endpoint = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
            for (ProviderRuntime runtime : List.of(
                    runtime("Groq", "compound", endpoint),
                    runtime("OpenRouter", "anthropic/claude-haiku:online", endpoint),
                    runtime("OpenRouter", "perplexity/sonar-pro", endpoint),
                    runtime("OpenAI", "gpt-4o-search-preview", endpoint),
                    runtime("OpenAI", "gpt-5-search-api", endpoint)
            )) {
                subject.streamCompletion(
                        runtime,
                        List.of(Message.user("question")),
                        ReasoningLevel.OFF,
                        new WebSearchRequestOptions(true),
                        ignored -> {
                        },
                        ignored -> {
                        },
                        ignored -> {
                        },
                        ignored -> {
                        },
                        () -> false,
                        ignored -> {
                        },
                        () -> {
                        }
                );
            }

            assertThat(chatRequests).hasValue(5);
            assertThat(responsesRequests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OpenRouter Web Search sends the server tool and emits response citation spans")
    void streamCompletion_whenOpenRouterWebSearchEnabled_sendsServerToolAndEmitsCitation() throws Exception {
        var requestBody = new AtomicReference<String>();
        List<CitationRef> citations = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    data: {"id":"chatcmpl","object":"chat.completion.chunk","created":0,"model":"test","choices":[{"index":0,"delta":{"content":"Fact <citation>."},"finish_reason":null}]}

                    data: {"id":"chatcmpl","object":"chat.completion.chunk","created":0,"model":"test","choices":[{"index":0,"delta":{"annotations":[{"type":"url_citation","title":"Example","url":"https://example.com","start_index":5,"end_index":15}]},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            String endpoint = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());

            subject.streamCompletion(
                    runtime("OpenRouter", "openai/gpt-5-mini", endpoint),
                    List.of(Message.user("question")),
                    ReasoningLevel.OFF,
                    new WebSearchRequestOptions(true),
                    ignored -> {
                    },
                    ignored -> {
                    },
                    ignored -> {
                    },
                    citations::add,
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            );

            assertThat(requestBody.get()).contains("\"type\":\"openrouter:web_search\"");
            assertThat(citations)
                    .singleElement()
                    .satisfies(citation -> {
                        assertThat(citation.number()).isEqualTo(1);
                        assertThat(citation.url()).isEqualTo("https://example.com");
                        assertThat(citation.responseStartIndex()).isEqualTo(5L);
                        assertThat(citation.responseEndIndex()).isEqualTo(15L);
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Responses-native web search is disabled when request option is disabled")
    void shouldUseResponsesNativeWebSearch_whenRequestOptionDisabled_returnsFalse() throws Exception {
        boolean enabled = invokeShouldUseResponsesNativeWebSearch(runtime("OpenAI", "gpt-5"), WebSearchRequestOptions.disabled());

        assertThat(enabled).isFalse();
    }

    @Test
    @DisplayName("Responses output delta emission keeps newline tokens")
    void shouldEmitOutputDelta_whenDeltaContainsOnlyNewline_returnsTrue() throws Exception {
        boolean shouldEmit = invokeShouldEmitOutputDelta("\n");

        assertThat(shouldEmit).isTrue();
    }

    @Test
    @DisplayName("Chat Completions reasoning preserves newline-only deltas")
    void emitThinkingDeltaFromProperties_whenDeltaIsNewline_emitsFormatting() throws Exception {
        List<String> tokens = new ArrayList<>();

        boolean emitted = invokeEmitThinkingDeltaFromProperties(
                Map.of("reasoning_content", JsonValue.from("\n")),
                tokens::add
        );

        assertThat(emitted).isTrue();
        assertThat(tokens).containsExactly("\n");
    }

    @Test
    @DisplayName("Responses output delta emission skips empty tokens")
    void shouldEmitOutputDelta_whenDeltaIsEmpty_returnsFalse() throws Exception {
        boolean shouldEmit = invokeShouldEmitOutputDelta("");

        assertThat(shouldEmit).isFalse();
    }

    @Test
    @DisplayName("Kimi K3 reasoning off uses the lowest supported effort")
    void applyChatCompletionsThinkingHints_whenKimiK3ReasoningIsOff_sendsLowEffort() throws Exception {
        Map<String, JsonValue> properties = togetherReasoningProperties(
                "moonshotai/Kimi-K3",
                ReasoningLevel.OFF,
                "https://api.together.ai/v1"
        );

        assertThat(jsonValue(properties, "reasoning_effort")).isEqualTo("low");
        assertThat(properties).doesNotContainKey("reasoning");
    }

    @Test
    @DisplayName("Hosted Together reasoning models serialize only their documented wire policy")
    void applyChatCompletionsThinkingHints_whenTogetherModelVaries_usesCentralPolicy() throws Exception {
        Map<String, JsonValue> binaryOff = togetherReasoningProperties(
                "MiniMaxAI/MiniMax-M3",
                ReasoningLevel.OFF,
                "https://api.together.ai/v1"
        );
        Map<String, JsonValue> binaryOn = togetherReasoningProperties(
                "MiniMaxAI/MiniMax-M3",
                ReasoningLevel.EXTRA_HIGH,
                "https://api.together.ai/v1"
        );
        Map<String, JsonValue> kimiLow = togetherReasoningProperties(
                "moonshotai/Kimi-K3",
                ReasoningLevel.LOW,
                "https://api.together.ai/v1"
        );
        Map<String, JsonValue> kimiMax = togetherReasoningProperties(
                "moonshotai/Kimi-K3",
                ReasoningLevel.EXTRA_HIGH,
                "https://api.together.ai/v1"
        );
        Map<String, JsonValue> gptOss = togetherReasoningProperties(
                "openai/gpt-oss-120b",
                ReasoningLevel.EXTRA_HIGH,
                "https://api.together.ai/v1"
        );
        Map<String, JsonValue> highMax = togetherReasoningProperties(
                "zai-org/GLM-5.2",
                ReasoningLevel.EXTRA_HIGH,
                "https://api.together.ai/v1"
        );
        Map<String, JsonValue> nemotron = togetherReasoningProperties(
                "nvidia/nemotron-3-ultra-550b-a55b",
                ReasoningLevel.MEDIUM,
                "https://api.together.ai/v1"
        );

        assertThat(jsonValue(binaryOff, "reasoning")).isEqualTo(Map.of("enabled", false));
        assertThat(jsonValue(binaryOn, "reasoning")).isEqualTo(Map.of("enabled", true));
        assertThat(jsonValue(kimiLow, "reasoning_effort")).isEqualTo("low");
        assertThat(jsonValue(kimiMax, "reasoning_effort")).isEqualTo("max");
        assertThat(jsonValue(gptOss, "reasoning_effort")).isEqualTo("high");
        assertThat(gptOss).doesNotContainKey("reasoning");
        assertThat(jsonValue(highMax, "reasoning_effort")).isEqualTo("max");
        assertThat(highMax.values()).noneMatch(value -> value.toString().contains("xhigh"));
        assertThat(jsonValue(nemotron, "reasoning")).isEqualTo(Map.of("enabled", true));
        assertThat(jsonValue(nemotron, "chat_template_kwargs")).isEqualTo(Map.of("medium_effort", true));
    }

    @Test
    @DisplayName("Unknown and custom-base Together models omit generic reasoning properties")
    void applyChatCompletionsThinkingHints_whenTogetherPolicyIsUnavailable_omitsReasoning() throws Exception {
        assertThat(togetherReasoningProperties(
                "Qwen/Qwen3.7-Max",
                ReasoningLevel.HIGH,
                "https://api.together.ai/v1"
        )).isEmpty();
        assertThat(togetherReasoningProperties(
                "MiniMaxAI/MiniMax-M3",
                ReasoningLevel.HIGH,
                "https://proxy.example/v1"
        )).isEmpty();
    }

    @Test
    @DisplayName("Together reasoning uses one semantic attempt while other providers retain downgrade attempts")
    void reasoningAttempts_whenProviderIsTogether_returnsSingleRequestedLevel() throws Exception {
        assertThat(invokeReasoningAttempts(
                runtime("Together", "openai/gpt-oss-120b", "https://api.together.ai/v1"),
                ReasoningLevel.EXTRA_HIGH
        )).containsExactly(ReasoningLevel.EXTRA_HIGH);
        assertThat(invokeReasoningAttempts(runtime("OpenAI", "gpt-5"), ReasoningLevel.HIGH))
                .containsExactly(ReasoningLevel.HIGH, ReasoningLevel.MEDIUM, ReasoningLevel.LOW, ReasoningLevel.OFF);
    }

    @Test
    @DisplayName("Together reasoning parameter failures are surfaced after one ordinary-chat attempt")
    void streamCompletion_whenTogetherRejectsReasoning_doesNotDowngradeOrRetry() throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            byte[] body = """
                    {"error":{"type":"invalid_request_error","code":"invalid_reasoning","message":"invalid reasoning_effort"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());

            assertThatThrownBy(() -> subject.streamCompletion(
                    runtime("Together", "openai/gpt-oss-120b", endpoint),
                    List.of(Message.user("question")),
                    ReasoningLevel.EXTRA_HIGH,
                    ignored -> {
                    },
                    ignored -> {
                    },
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            )).isInstanceOf(InvalidRequestException.class);
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Custom Together ordinary-chat errors retain generic status semantics")
    void streamCompletion_whenCustomTogetherReturns403_mapsAuthoritativeStatusGenerically() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = """
                    {"error":{"type":"context_length","code":"context_length","message":"context length exceeded"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());

            assertThatThrownBy(() -> subject.streamCompletion(
                    runtime("Together", "Qwen/Qwen3.5-9B", endpoint),
                    List.of(Message.user("question")),
                    ReasoningLevel.OFF,
                    ignored -> {
                    },
                    ignored -> {
                    },
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            )).isInstanceOf(AuthenticationException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Together ordinary chat suppresses streamed reasoning when the requested level is off")
    void streamCompletion_whenTogetherReasoningIsOff_suppressesThinkingDeltas() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = """
                    data: {"id":"chatcmpl","object":"chat.completion.chunk","created":0,"model":"test","choices":[{"index":0,"delta":{"reasoning":"hidden","content":"answer"},"finish_reason":null}]}

                    data: {"id":"chatcmpl","object":"chat.completion.chunk","created":0,"model":"test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
            List<String> thinking = new ArrayList<>();

            subject.streamCompletion(
                    runtime("Together", "MiniMaxAI/MiniMax-M3", endpoint),
                    List.of(Message.user("question")),
                    ReasoningLevel.OFF,
                    ignored -> {
                    },
                    thinking::add,
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            );

            assertThat(thinking).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Together canonical reasoning deltas remain available to the thinking callback")
    void emitThinkingDeltaFromProperties_whenTogetherUsesReasoning_emitsCanonicalValue() throws Exception {
        List<String> tokens = new ArrayList<>();

        boolean emitted = invokeEmitThinkingDeltaFromProperties(
                Map.of("reasoning", JsonValue.from("thinking")),
                tokens::add
        );

        assertThat(emitted).isTrue();
        assertThat(tokens).containsExactly("thinking");
    }

    private ChatCompletionCreateParams captureChatCompletionParams(
            ProviderRuntime runtime,
            Message message
    ) throws Exception {
        boolean nativeImages = invokeSupportsNativeImages(runtime);
        AttachmentProjectionPlan plan = AttachmentProjectionPlan.create(
                List.of(message),
                attachmentSupport,
                AttachmentProjectionPlan.openAi(nativeImages),
                () -> false
        );
        OpenAIClient client = mock(OpenAIClient.class);
        ChatService chat = mock(ChatService.class);
        ChatCompletionService completions = mock(ChatCompletionService.class);
        @SuppressWarnings("unchecked")
        StreamResponse<ChatCompletionChunk> stream = mock(StreamResponse.class);
        ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
        ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
        ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);
        when(completions.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(stream);
        when(stream.stream()).thenReturn(Stream.of(chunk));
        when(chunk.choices()).thenReturn(List.of(choice));
        when(chunk._additionalProperties()).thenReturn(emptyMap());
        when(choice.delta()).thenReturn(delta);
        when(choice.finishReason()).thenReturn(Optional.of(mock(ChatCompletionChunk.Choice.FinishReason.class)));
        when(choice._additionalProperties()).thenReturn(emptyMap());
        when(delta.content()).thenReturn(Optional.of("done"));
        when(delta._additionalProperties()).thenReturn(emptyMap());
        var captor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);

        invokeStreamWithChatCompletions(runtime, plan, client);

        verify(completions).createStreaming(captor.capture());
        return captor.getValue();
    }

    private Map<String, JsonValue> togetherReasoningProperties(
            String modelId,
            ReasoningLevel level,
            String baseUrl
    ) throws Exception {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(modelId)
                .addUserMessage("question");
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod(
                "applyChatCompletionsThinkingHints",
                ChatCompletionCreateParams.Builder.class,
                ProviderRuntime.class,
                ReasoningLevel.class
        );
        method.setAccessible(true);
        method.invoke(subject, builder, runtime("Together", modelId, baseUrl), level);
        return builder.build()._additionalBodyProperties();
    }

    private Object jsonValue(Map<String, JsonValue> properties, String key) {
        return properties.get(key).convert(Object.class);
    }

    @SuppressWarnings("unchecked")
    private List<ReasoningLevel> invokeReasoningAttempts(
            ProviderRuntime runtime,
            ReasoningLevel reasoningLevel
    ) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod(
                "reasoningAttempts",
                ProviderRuntime.class,
                ReasoningLevel.class
        );
        method.setAccessible(true);
        return (List<ReasoningLevel>) method.invoke(subject, runtime, reasoningLevel);
    }

    private void invokeStreamWithChatCompletions(
            ProviderRuntime runtime,
            AttachmentProjectionPlan plan,
            OpenAIClient client
    ) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod(
                "streamWithChatCompletions",
                ProviderRuntime.class,
                AttachmentProjectionPlan.class,
                OpenAIClient.class,
                ReasoningLevel.class,
                Consumer.class,
                Consumer.class,
                Consumer.class,
                BooleanSupplier.class,
                Consumer.class,
                Runnable.class
        );
        method.setAccessible(true);
        method.invoke(
                subject,
                runtime,
                plan,
                client,
                ReasoningLevel.OFF,
                (Consumer<String>) ignored -> {
                },
                (Consumer<String>) ignored -> {
                },
                (Consumer<CitationRef>) ignored -> {
                },
                (BooleanSupplier) () -> false,
                (Consumer<AutoCloseable>) ignored -> {
                },
                (Runnable) () -> {
                }
        );
    }

    private boolean invokeSupportsNativeImages(ProviderRuntime runtime) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod(
                "supportsNativeImages",
                ProviderRuntime.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(subject, runtime);
    }

    private void invokeStreamCopilotCompletion(AttachmentProjectionPlan plan, OpenAIClient client) throws Exception {
        invokeStreamCopilotCompletion(
                plan,
                client,
                copilotRuntime("gpt-5.4-mini", List.of("/responses")),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
                ignored -> {
                }
        );
    }

    private void invokeStreamCopilotCompletion(
            AttachmentProjectionPlan plan,
            OpenAIClient client,
            ProviderRuntime runtime,
            ReasoningLevel reasoningLevel,
            WebSearchRequestOptions webSearchOptions,
            Consumer<String> onToken
    ) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod(
                "streamCopilotCompletion",
                ProviderRuntime.class,
                AttachmentProjectionPlan.class,
                OpenAIClient.class,
                ReasoningLevel.class,
                WebSearchRequestOptions.class,
                Consumer.class,
                Consumer.class,
                Consumer.class,
                BooleanSupplier.class,
                Consumer.class,
                Runnable.class
        );
        method.setAccessible(true);
        method.invoke(
                subject,
                runtime,
                plan,
                client,
                reasoningLevel,
                webSearchOptions,
                onToken,
                (Consumer<String>) ignored -> {
                },
                (Consumer<CitationRef>) ignored -> {
                },
                (BooleanSupplier) () -> false,
                (Consumer<AutoCloseable>) ignored -> {
                },
                (Runnable) () -> {
                }
        );
    }

    @SuppressWarnings("unchecked")
    private List<ResponseInputItem> invokeToResponsesInput(AttachmentProjectionPlan plan) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod(
                "toResponsesInput",
                AttachmentProjectionPlan.class
        );
        method.setAccessible(true);
        return (List<ResponseInputItem>) method.invoke(subject, plan);
    }

    private boolean invokeIsUnsupportedApiForEndpoint(Exception exception, String endpoint) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod("isUnsupportedApiForEndpoint", Exception.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(subject, exception, endpoint);
    }

    @SuppressWarnings("unchecked")
    private List<ReasoningLevel> invokeReasoningAttempts(ReasoningLevel reasoningLevel) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod("reasoningAttempts", ReasoningLevel.class);
        method.setAccessible(true);
        return (List<ReasoningLevel>) method.invoke(subject, reasoningLevel);
    }

    @SuppressWarnings("unchecked")
    private Optional<ReasoningEffort> invokeToOpenAiReasoningEffort(ReasoningLevel reasoningLevel) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod("toOpenAiReasoningEffort", ReasoningLevel.class);
        method.setAccessible(true);
        return (Optional<ReasoningEffort>) method.invoke(subject, reasoningLevel);
    }

    private boolean invokeIsUnsupportedReasoningEffort(Exception exception) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod("isUnsupportedReasoningEffort", Exception.class);
        method.setAccessible(true);
        return (boolean) method.invoke(subject, exception);
    }

    private boolean invokeEmitThinkingDeltaFromProperties(
            Map<String, JsonValue> properties,
            Consumer<String> onThinkingToken
    ) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod(
                "emitThinkingDeltaFromProperties",
                Map.class,
                Consumer.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(subject, properties, onThinkingToken);
    }

    private boolean invokeShouldEmitOutputDelta(String delta) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod("shouldEmitOutputDelta", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(subject, delta);
    }

    private boolean invokeShouldUseResponsesNativeWebSearch(ProviderRuntime runtime, WebSearchRequestOptions webSearchOptions) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod(
                "shouldUseResponsesNativeWebSearch",
                ProviderRuntime.class,
                WebSearchRequestOptions.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(subject, runtime, webSearchOptions);
    }

    private boolean invokeUsesRequiredChatCompletionsSearchTransport(ProviderRuntime runtime) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod(
                "usesRequiredChatCompletionsSearchTransport",
                ProviderRuntime.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(subject, runtime);
    }

    private ProviderRuntime runtime(String providerName, String modelId) {
        return runtime(providerName, modelId, "https://example.test/v1");
    }

    private ProviderRuntime runtime(String providerName, String modelId, String baseUrl) {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        providerName,
                        AuthType.ENV_VAR,
                        null,
                        null,
                        baseUrl,
                        emptyList(),
                        ProviderCapabilities.chatAndModels(),
                        UnaryOperator.identity()
                ),
                null,
                baseUrl,
                "test-token",
                modelId,
                emptyList()
        );
    }

    private ProviderRuntime copilotRuntime(List<String> supportedEndpoints) {
        return copilotRuntime("claude-sonnet-4.6", supportedEndpoints);
    }

    private ProviderRuntime copilotRuntime(String modelId, List<String> supportedEndpoints) {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        "GitHub Copilot",
                        AuthType.COPILOT_OAUTH,
                        null,
                        null,
                        "https://api.githubcopilot.com",
                        emptyList(),
                        ProviderCapabilities.chatAndModels(),
                        UnaryOperator.identity()
                ),
                null,
                "https://api.githubcopilot.com",
                "copilot-token",
                modelId,
                supportedEndpoints
        );
    }

    private Object invokePreferredCopilotEndpointMode(ProviderRuntime runtime) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod("preferredCopilotEndpointMode", ProviderRuntime.class);
        method.setAccessible(true);
        return method.invoke(subject, runtime);
    }
}
