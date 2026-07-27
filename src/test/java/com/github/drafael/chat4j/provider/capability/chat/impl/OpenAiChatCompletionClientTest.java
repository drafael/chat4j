package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiChatCompletionClientTest {

    private final ProviderAttachmentSupport attachmentSupport = ProviderAttachmentTestSupport.authority();
    private final OpenAiChatCompletionClient subject = new OpenAiChatCompletionClient(attachmentSupport);

    @Test
    @DisplayName("Responses input uses structured ordered message content instead of flattened role-prefixed text")
    void toResponsesInput_whenMessageContainsParts_preservesRoleAndOrderedStructuredContent() throws Exception {
        Path storedImage = ProviderAttachmentTestSupport.managedRoot(attachmentSupport)
                .resolve(UUID.randomUUID().toString());
        Files.write(storedImage, new byte[]{1, 2, 3});
        storedImage.toFile().deleteOnExit();
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
    @DisplayName("Unsupported reasoning effort detection matches invalid reasoning parameter errors")
    void isUnsupportedReasoningEffort_whenReasoningEffortIsInvalid_returnsTrue() throws Exception {
        Exception exception = new IllegalStateException("invalid reasoning_effort: xhigh");

        boolean unsupportedReasoningEffort = invokeIsUnsupportedReasoningEffort(exception);

        assertThat(unsupportedReasoningEffort).isTrue();
    }

    @Test
    @DisplayName("Responses-native web search is enabled only for supported OpenAI and xAI models")
    void shouldUseResponsesNativeWebSearch_whenProviderAndModelSupportResponsesSearch_returnsExpectedValue() throws Exception {
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("OpenAI", "gpt-5"), new WebSearchRequestOptions(true, "native"))).isTrue();
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("xAI", "grok-4"), new WebSearchRequestOptions(true, "native"))).isTrue();
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("xAI", "gpt-5"), new WebSearchRequestOptions(true, "native"))).isFalse();
        assertThat(invokeShouldUseResponsesNativeWebSearch(runtime("OpenRouter", "openai/gpt-5:online"), new WebSearchRequestOptions(true, "native"))).isFalse();
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
    @DisplayName("Responses output delta emission skips empty tokens")
    void shouldEmitOutputDelta_whenDeltaIsEmpty_returnsFalse() throws Exception {
        boolean shouldEmit = invokeShouldEmitOutputDelta("");

        assertThat(shouldEmit).isFalse();
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

    private ProviderRuntime runtime(String providerName, String modelId) {
        return new ProviderRuntime(
                new ProviderDescriptor(
                        providerName,
                        AuthType.ENV_VAR,
                        null,
                        null,
                        "https://example.test/v1",
                        emptyList(),
                        ProviderCapabilities.chatAndModels(),
                        UnaryOperator.identity()
                ),
                null,
                "https://example.test/v1",
                "test-token",
                modelId,
                emptyList()
        );
    }

    private ProviderRuntime copilotRuntime(List<String> supportedEndpoints) {
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
                "claude-sonnet-4.6",
                supportedEndpoints
        );
    }

    private Object invokePreferredCopilotEndpointMode(ProviderRuntime runtime) throws Exception {
        Method method = OpenAiChatCompletionClient.class.getDeclaredMethod("preferredCopilotEndpointMode", ProviderRuntime.class);
        method.setAccessible(true);
        return method.invoke(subject, runtime);
    }
}
