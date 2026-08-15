package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.core.error.ProviderException;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.BaseUrlNormalizer;
import com.github.drafael.chat4j.provider.support.CopilotRequestHeaders;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.TogetherModelSupport;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

final class OpenAiToolAgentAdapter implements AgentProviderAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String providerName;
    private final String modelId;
    private final String baseUrl;
    private final String apiKey;
    private final String systemPromptAppend;
    private final ProviderAttachmentSupport attachmentSupport;
    private final List<AgentToolDefinition> agentToolDefinitions;
    private final List<Map<String, Object>> toolExchangeMessages = new ArrayList<>();
    private PendingAssistantContinuation pendingAssistantContinuation;

    OpenAiToolAgentAdapter(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        this(providerName, modelId, baseUrl, apiKey, "", attachmentSupport);
    }

    OpenAiToolAgentAdapter(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey,
            String systemPromptAppend,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        this(
                providerName,
                modelId,
                baseUrl,
                apiKey,
                systemPromptAppend,
                attachmentSupport,
                LocalAgentToolCatalog.definitions()
        );
    }

    OpenAiToolAgentAdapter(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey,
            String systemPromptAppend,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull List<AgentToolDefinition> agentToolDefinitions
    ) {
        this.providerName = StringUtils.defaultString(providerName);
        this.modelId = StringUtils.defaultString(modelId);
        this.baseUrl = BaseUrlNormalizer.normalize(baseUrl, "");
        this.apiKey = apiKey;
        this.systemPromptAppend = StringUtils.defaultString(systemPromptAppend);
        this.attachmentSupport = attachmentSupport;
        this.agentToolDefinitions = List.copyOf(agentToolDefinitions);
    }

    @Override
    public AgentTurnResult executeTurn(@NonNull AgentRunRequest request, @NonNull AgentRunCallbacks callbacks) {
        try {
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            PendingToolExchange pendingToolExchange = request.toolResults().isEmpty()
                    ? null
                    : prepareToolExchange(request.toolResults());

            List<Map<String, Object>> messages = buildMessages(request, pendingToolExchange);
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", modelId);
            payload.put("messages", messages);
            payload.put("tools", toolDefinitions());
            payload.put("tool_choice", "auto");
            payload.put("stream", false);
            applyTogetherReasoning(payload, request.reasoningLevel());

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsEndpoint(baseUrl)))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8));
            applyAuthHeaders(requestBuilder);

            AgentHttpSupport.Response response = AgentHttpSupport.send(
                    HTTP_CLIENT,
                    requestBuilder.build(),
                    request.isCancelled()
            );
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw mapHttpError(response.statusCode(), response.body());
            }

            JsonNode root = JSON.readTree(response.body());
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            ValidatedResponse validated = validateResponse(root, request.reasoningLevel());
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            if (request.reasoningLevel().enabled() && StringUtils.isNotBlank(validated.reasoningText())) {
                callbacks.onThinkingToken().accept(validated.reasoningText());
            }
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            if (validated.toolInvocations().isEmpty() && StringUtils.isNotBlank(validated.assistantText())) {
                callbacks.onToken().accept(validated.assistantText());
            }
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }

            commitToolExchange(pendingToolExchange);
            if (!validated.toolInvocations().isEmpty()) {
                pendingAssistantContinuation = validated.continuation();
                return AgentTurnResult.continueWithTools(validated.toolInvocations());
            }

            pendingAssistantContinuation = null;
            return AgentTurnResult.complete();
        } catch (CancellationException e) {
            return new AgentTurnResult(false, emptyList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AgentTurnResult(false, emptyList());
        } catch (HttpTimeoutException e) {
            if (!shouldStop(request)) {
                callbacks.onError().accept(new IllegalStateException(buildTimeoutMessage(), e));
            }
            return new AgentTurnResult(false, emptyList());
        } catch (Exception e) {
            if (!shouldStop(request)) {
                callbacks.onError().accept(e);
            }
            return new AgentTurnResult(false, emptyList());
        }
    }

    private boolean shouldStop(AgentRunRequest request) {
        return Thread.currentThread().isInterrupted() || request.isCancelled().getAsBoolean();
    }

    private PendingToolExchange prepareToolExchange(List<ToolInvocationResult> toolResults) {
        if (pendingAssistantContinuation == null) {
            throw new IllegalStateException("Tool results were received without a pending assistant tool call.");
        }
        Set<String> expectedIds = pendingAssistantContinuation.toolCalls().stream()
                .map(toolCall -> String.valueOf(toolCall.get("id")))
                .collect(toSet());
        Set<String> resultIds = toolResults.stream()
                .map(ToolInvocationResult::id)
                .collect(toSet());
        if (toolResults.size() != expectedIds.size() || !resultIds.equals(expectedIds)) {
            throw new IllegalStateException("Tool results do not match the pending assistant tool calls.");
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        if (pendingAssistantContinuation.contentPresent()) {
            assistantMessage.put("content", pendingAssistantContinuation.content());
        }
        ReasoningContinuation reasoning = pendingAssistantContinuation.reasoning();
        if (reasoning != null) {
            assistantMessage.put(reasoning.fieldName(), reasoning.value());
        }
        assistantMessage.put("tool_calls", pendingAssistantContinuation.toolCalls());
        messages.add(assistantMessage);

        toolResults.forEach(toolResult -> {
            Map<String, Object> toolMessage = new LinkedHashMap<>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", toolResult.id());
            String content = toolResult.success()
                    ? toolResult.output()
                    : "ERROR: %s".formatted(toolResult.error());
            toolMessage.put("content", StringUtils.defaultString(content));
            messages.add(toolMessage);
        });
        return new PendingToolExchange(pendingAssistantContinuation, List.copyOf(messages));
    }

    private void commitToolExchange(PendingToolExchange pendingToolExchange) {
        if (pendingToolExchange == null) {
            return;
        }
        if (pendingAssistantContinuation != pendingToolExchange.continuation()) {
            throw new IllegalStateException("The pending assistant tool call changed before continuation completed.");
        }
        toolExchangeMessages.addAll(pendingToolExchange.messages());
        pendingAssistantContinuation = null;
    }

    private List<Map<String, Object>> buildMessages(AgentRunRequest request, PendingToolExchange pendingToolExchange) {
        List<Map<String, Object>> combined = new ArrayList<>();
        combined.add(systemPromptMessage(request));

        AttachmentProjectionPlan plan = AttachmentProjectionPlan.create(
                request.history(),
                attachmentSupport,
                AttachmentProjectionPlan.metadataOnly(),
                request.isCancelled()
        );
        List<Map<String, Object>> messages = plan.messages().stream()
                .map(this::toChatMessage)
                .toList();

        combined.addAll(messages);
        combined.addAll(toolExchangeMessages);
        if (pendingToolExchange != null) {
            combined.addAll(pendingToolExchange.messages());
        }
        return combined;
    }

    private Map<String, Object> systemPromptMessage(AgentRunRequest request) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "system");
        message.put("content", AgentSystemPromptBuilder.buildToolAgentPrompt(
                request.projectRoot(),
                systemPromptAppend,
                agentToolDefinitions.stream().anyMatch(tool -> tool.source() == AgentToolSource.MCP)
        ));
        return message;
    }

    private Map<String, Object> toChatMessage(ProjectedMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", toChatRole(message.role()));
        payload.put("content", message.parts().stream()
                .map(AttachmentProjectionPlan::textFallback)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n")));
        return payload;
    }

    private String toChatRole(Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case USER -> "user";
        };
    }

    private ValidatedResponse validateResponse(JsonNode root, ReasoningLevel reasoningLevel) {
        JsonNode choices = root == null ? null : root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty() || !choices.get(0).isObject()) {
            throw invalidResponse("a nonempty choices array is required");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.get("message");
        if (message == null || !message.isObject()) {
            throw invalidResponse("the first choice must contain an object-valued message");
        }

        boolean hostedTogether = TogetherModelSupport.isTogether(providerName)
                && TogetherModelSupport.isHostedEndpoint(baseUrl);
        if (hostedTogether) {
            JsonNode role = message.get("role");
            if (role == null || !role.isTextual() || !"assistant".equals(role.asText())) {
                throw invalidResponse("hosted Together requires message.role=assistant");
            }
            JsonNode content = message.get("content");
            if (content == null || !content.isNull() && !content.isTextual()) {
                throw invalidResponse("hosted Together requires present null or textual message.content");
            }
        }

        ValidatedToolBatch toolBatch = validateToolCalls(message);
        String assistantText = extractAssistantText(message);
        JsonNode finishReasonNode = choice.get("finish_reason");
        if (hostedTogether && finishReasonNode != null && !finishReasonNode.isTextual()) {
            throw invalidResponse("hosted Together requires a textual finish_reason when present");
        }
        String finishReason = textualValue(finishReasonNode);
        if ("length".equals(finishReason)) {
            throw invalidResponse("the response was truncated before completion");
        }
        if (hostedTogether) {
            validateTogetherFinishReason(finishReason, assistantText, toolBatch.invocations());
        }
        if (message.has("function_call")
                && !message.path("function_call").isNull()
                && toolBatch.invocations().isEmpty()) {
            throw invalidResponse("deprecated function_call responses are unsupported without tool_calls");
        }
        if (StringUtils.isBlank(assistantText) && toolBatch.invocations().isEmpty()) {
            throw invalidResponse("assistant content or a valid tool-call batch is required");
        }

        String reasoningText = extractReasoningText(message);
        ReasoningContinuation reasoning = reasoningContinuation(message, reasoningLevel);
        PendingAssistantContinuation continuation = toolBatch.invocations().isEmpty()
                ? null
                : new PendingAssistantContinuation(
                        message.has("content"),
                        jsonValue(message.get("content")),
                        toolBatch.serializedCalls(),
                        reasoning
                );
        return new ValidatedResponse(assistantText, reasoningText, toolBatch.invocations(), continuation);
    }

    private ValidatedToolBatch validateToolCalls(JsonNode message) {
        if (!message.has("tool_calls")) {
            return ValidatedToolBatch.empty();
        }
        JsonNode toolCalls = message.get("tool_calls");
        if (toolCalls == null || !toolCalls.isArray()) {
            throw invalidResponse("message.tool_calls must be an array when present");
        }
        if (toolCalls.isEmpty()) {
            return ValidatedToolBatch.empty();
        }

        List<ToolInvocationRequest> invocations = new ArrayList<>();
        List<Map<String, Object>> serializedCalls = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode toolCall : toolCalls) {
            if (!toolCall.isObject()) {
                throw invalidResponse("every tool call must be an object");
            }
            String id = textualValue(toolCall.get("id"));
            String type = textualValue(toolCall.get("type"));
            JsonNode function = toolCall.get("function");
            String name = function == null || !function.isObject()
                    ? ""
                    : textualValue(function.get("name"));
            JsonNode arguments = function == null || !function.isObject()
                    ? null
                    : function.get("arguments");
            if (StringUtils.isBlank(id)
                    || !ids.add(id)
                    || !"function".equals(type)
                    || StringUtils.isBlank(name)
                    || arguments == null
                    || !arguments.isTextual()) {
                throw invalidResponse("tool calls require unique nonblank IDs, function type/name, and textual arguments");
            }

            String argumentsJson = arguments.asText();
            invocations.add(new ToolInvocationRequest(id, name, argumentsJson));
            serializedCalls.add(toObjectMap(toolCall));
        }
        return new ValidatedToolBatch(List.copyOf(invocations), List.copyOf(serializedCalls));
    }

    private void validateTogetherFinishReason(
            String finishReason,
            String assistantText,
            List<ToolInvocationRequest> toolInvocations
    ) {
        if ("tool_calls".equals(finishReason) && toolInvocations.isEmpty()) {
            throw invalidResponse("finish_reason=tool_calls requires a valid tool-call batch");
        }
        if (Strings.CS.equalsAny(finishReason, "stop", "eos")
                && (StringUtils.isBlank(assistantText) || !toolInvocations.isEmpty())) {
            throw invalidResponse("finish_reason=stop/eos requires final content without tool calls");
        }
    }

    private ReasoningContinuation reasoningContinuation(JsonNode message, ReasoningLevel reasoningLevel) {
        if (TogetherModelSupport.isTogether(providerName)) {
            if (!reasoningLevel.enabled()) {
                return null;
            }
            return switch (TogetherModelSupport.agentContinuationMode(baseUrl, modelId)) {
                case DEEPSEEK_EXACT_FIELD -> firstReasoningContinuation(message, List.of("reasoning", "reasoning_content"));
                case GLM_PRESERVED_REASONING -> firstReasoningContinuation(message, List.of("reasoning"));
                case NONE -> null;
            };
        }
        if (Strings.CI.equals(StringUtils.trim(providerName), "DeepSeek")) {
            return firstReasoningContinuation(message, List.of("reasoning_content"));
        }
        return null;
    }

    private ReasoningContinuation firstReasoningContinuation(JsonNode message, List<String> fieldNames) {
        return fieldNames.stream()
                .filter(message::has)
                .map(fieldName -> new ReasoningContinuation(fieldName, jsonValue(message.get(fieldName))))
                .findFirst()
                .orElse(null);
    }

    private Object jsonValue(JsonNode node) {
        return node == null || node.isMissingNode() ? null : JSON.convertValue(node, Object.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toObjectMap(JsonNode node) {
        return JSON.convertValue(node, Map.class);
    }

    private String extractAssistantText(JsonNode message) {
        JsonNode content = message.get("content");
        if (content != null && content.isTextual()) {
            return content.asText("");
        }
        if (content == null || !content.isArray()) {
            return "";
        }
        StringBuilder collected = new StringBuilder();
        content.forEach(part -> {
            if ("text".equals(part.path("type").asText(""))) {
                collected.append(part.path("text").asText(""));
            }
        });
        return collected.toString();
    }

    private String extractReasoningText(JsonNode message) {
        return List.of("reasoning", "reasoning_content", "thinking", "thought").stream()
                .map(message::get)
                .filter(Objects::nonNull)
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    private String textualValue(JsonNode node) {
        return node != null && node.isTextual() ? node.asText("") : "";
    }

    private IllegalStateException invalidResponse(String detail) {
        return new IllegalStateException("%s tool turn returned an invalid response: %s."
                .formatted(StringUtils.defaultIfBlank(providerName, "OpenAI-compatible provider"), detail));
    }

    private void applyTogetherReasoning(Map<String, Object> payload, ReasoningLevel reasoningLevel) {
        if (!TogetherModelSupport.isTogether(providerName)) {
            return;
        }
        TogetherModelSupport.ReasoningRequest reasoning = TogetherModelSupport.reasoningRequest(
                baseUrl,
                modelId,
                reasoningLevel
        );
        if (reasoning.enabledPropertyPresent()) {
            payload.put("reasoning", Map.of("enabled", reasoning.enabled()));
        }
        if (StringUtils.isNotBlank(reasoning.effort())) {
            payload.put("reasoning_effort", reasoning.effort());
        }

        Map<String, Object> templateArguments = new LinkedHashMap<>();
        if (reasoning.mediumEffort()) {
            templateArguments.put("medium_effort", true);
        }
        if (reasoningLevel.enabled()
                && TogetherModelSupport.agentContinuationMode(baseUrl, modelId)
                == TogetherModelSupport.AgentContinuationMode.GLM_PRESERVED_REASONING) {
            templateArguments.put("clear_thinking", false);
        }
        if (!templateArguments.isEmpty()) {
            payload.put("chat_template_kwargs", templateArguments);
        }
    }

    private ProviderException mapHttpError(int statusCode, String responseBody) {
        String errorType = "";
        String errorCode = "";
        String message = "";
        try {
            JsonNode error = JSON.readTree(StringUtils.defaultString(responseBody)).path("error");
            errorType = error.path("type").asText("");
            errorCode = error.path("code").asText("");
            message = error.path("message").asText("");
        } catch (Exception ignored) {
        }
        return ProviderExceptionMapper.mapHttpStatus(
                providerName,
                baseUrl,
                statusCode,
                errorType,
                errorCode,
                message,
                apiKey
        );
    }

    private String buildTimeoutMessage() {
        int timeoutSeconds = (int) REQUEST_TIMEOUT.toSeconds();
        return "%s tool turn timed out after %d seconds for model %s."
                .formatted(
                        StringUtils.defaultIfBlank(providerName, "OpenAI-compatible provider"),
                        timeoutSeconds,
                        StringUtils.defaultIfBlank(modelId, "unknown")
                );
    }

    private String chatCompletionsEndpoint(String normalizedBaseUrl) {
        return normalizedBaseUrl.endsWith("/chat/completions")
                ? normalizedBaseUrl
                : "%s/chat/completions".formatted(normalizedBaseUrl);
    }

    private void applyAuthHeaders(HttpRequest.Builder requestBuilder) {
        if (StringUtils.isNotBlank(apiKey)) {
            requestBuilder.header("Authorization", "Bearer %s".formatted(apiKey));
            if (Strings.CI.contains(providerName, "google")) {
                requestBuilder.header("x-goog-api-key", apiKey);
            }
        }

        if (Strings.CS.equals(providerName, "GitHub Copilot")) {
            CopilotRequestHeaders.asMap().forEach(requestBuilder::header);
        }
    }

    private List<Map<String, Object>> toolDefinitions() {
        return agentToolDefinitions.stream()
                .map(this::toolDefinition)
                .toList();
    }

    private Map<String, Object> toolDefinition(AgentToolDefinition tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", tool.inputSchema());

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", "function");
        definition.put("function", function);
        return definition;
    }

    private record ValidatedResponse(
            String assistantText,
            String reasoningText,
            List<ToolInvocationRequest> toolInvocations,
            PendingAssistantContinuation continuation
    ) {
    }

    private record ValidatedToolBatch(
            List<ToolInvocationRequest> invocations,
            List<Map<String, Object>> serializedCalls
    ) {
        private static ValidatedToolBatch empty() {
            return new ValidatedToolBatch(emptyList(), emptyList());
        }
    }

    private record PendingAssistantContinuation(
            boolean contentPresent,
            Object content,
            List<Map<String, Object>> toolCalls,
            ReasoningContinuation reasoning
    ) {
    }

    private record PendingToolExchange(
            PendingAssistantContinuation continuation,
            List<Map<String, Object>> messages
    ) {
    }

    private record ReasoningContinuation(String fieldName, Object value) {
    }
}
