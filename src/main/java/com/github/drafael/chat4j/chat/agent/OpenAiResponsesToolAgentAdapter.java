package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport.RedirectPolicy;
import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.core.error.ProviderException;
import com.github.drafael.chat4j.provider.core.error.ProviderExceptionMapper;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.BaseUrlNormalizer;
import com.github.drafael.chat4j.provider.support.OpenAiReasoningSupport;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderCapabilityResolver;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

final class OpenAiResponsesToolAgentAdapter implements AgentProviderAdapter {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final HttpTransport HTTP_TRANSPORT = JavaNetHttpTransport.create(Duration.ofSeconds(5), RedirectPolicy.NEVER);

    private final String modelId;
    private final String baseUrl;
    private final String apiKey;
    private final String systemPromptAppend;
    private final ProviderAttachmentSupport attachmentSupport;
    private final List<AgentToolDefinition> toolDefinitions;
    private final List<Map<String, Object>> toolExchangeItems = new ArrayList<>();
    private ValidatedResponse pendingResponse;

    OpenAiResponsesToolAgentAdapter(
            String modelId,
            String baseUrl,
            String apiKey,
            String systemPromptAppend,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull List<AgentToolDefinition> toolDefinitions
    ) {
        this.modelId = modelId;
        this.baseUrl = BaseUrlNormalizer.normalize(baseUrl, "");
        this.apiKey = apiKey;
        this.systemPromptAppend = StringUtils.defaultString(systemPromptAppend);
        this.attachmentSupport = attachmentSupport;
        this.toolDefinitions = List.copyOf(toolDefinitions);
    }

    @Override
    public AgentTurnResult executeTurn(@NonNull AgentRunRequest request, @NonNull AgentRunCallbacks callbacks) {
        try {
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            List<Map<String, Object>> exchange = prepareToolExchange(request.toolResults());
            Map<String, Object> payload = buildPayload(request, exchange);
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            if (StringUtils.isNotBlank(apiKey)) {
                headers.put("Authorization", "Bearer %s".formatted(apiKey));
            }
            var httpRequest = new HttpExchangeRequest(
                    "POST",
                    URI.create(baseUrl.endsWith("/responses") ? baseUrl : "%s/responses".formatted(baseUrl)),
                    headers,
                    HttpBody.bytes(JSON.writeBytes(payload)),
                    REQUEST_TIMEOUT,
                    0
            );
            HttpExchangeResponse response = HTTP_TRANSPORT.send(httpRequest, request.isCancelled());
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpError(response);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = JSON.read(response.body(), Map.class);
            ValidatedResponse validated = validateResponse(root);
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            if (request.reasoningLevel().enabled() && StringUtils.isNotBlank(validated.reasoningText())) {
                callbacks.onThinkingToken().accept(validated.reasoningText());
            }
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            if (validated.toolInvocations().isEmpty()) {
                callbacks.onToken().accept(validated.assistantText());
            }
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }

            // Commit only after a valid, uncancelled turn; a failed continuation retains its pending call batch.
            toolExchangeItems.addAll(exchange);
            pendingResponse = validated.toolInvocations().isEmpty() ? null : validated;
            return pendingResponse == null
                    ? AgentTurnResult.complete()
                    : AgentTurnResult.continueWithTools(validated.toolInvocations());
        } catch (CancellationException e) {
            return new AgentTurnResult(false, emptyList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AgentTurnResult(false, emptyList());
        } catch (HttpTimeoutException e) {
            if (!shouldStop(request)) {
                callbacks.onError().accept(new IllegalStateException(
                        "OpenAI Responses tool turn timed out after %d seconds.".formatted(REQUEST_TIMEOUT.toSeconds())
                ));
            }
            return new AgentTurnResult(false, emptyList());
        } catch (Exception e) {
            if (!shouldStop(request)) {
                callbacks.onError().accept(ProviderExceptionMapper.map(e, apiKey));
            }
            return new AgentTurnResult(false, emptyList());
        }
    }

    private boolean shouldStop(AgentRunRequest request) {
        return Thread.currentThread().isInterrupted() || request.isCancelled().getAsBoolean();
    }

    private List<Map<String, Object>> prepareToolExchange(List<ToolInvocationResult> results) {
        if (pendingResponse == null) {
            if (!results.isEmpty()) {
                throw new IllegalStateException("Tool results were received without a pending Responses tool call.");
            }
            return emptyList();
        }
        Set<String> expectedIds = pendingResponse.toolInvocations().stream().map(ToolInvocationRequest::id).collect(toSet());
        Set<String> resultIds = results.stream().map(ToolInvocationResult::id).collect(toSet());
        if (results.size() != expectedIds.size() || !resultIds.equals(expectedIds)) {
            throw new IllegalStateException("Tool results do not match the pending Responses tool calls.");
        }
        List<Map<String, Object>> exchange = new ArrayList<>(pendingResponse.outputItems());
        results.forEach(result -> exchange.add(Map.of(
                "type", "function_call_output",
                "call_id", result.id(),
                "output", result.success() ? result.output() : "ERROR: %s".formatted(result.error())
        )));
        return exchange;
    }

    private Map<String, Object> buildPayload(AgentRunRequest request, List<Map<String, Object>> exchange) {
        AttachmentProjectionPlan projection = AttachmentProjectionPlan.create(
                request.history(), attachmentSupport, AttachmentProjectionPlan.metadataOnly(), request.isCancelled()
        );
        List<Map<String, Object>> input = new ArrayList<>();
        projection.messages().forEach(message -> input.add(Map.of(
                "role", switch (message.role()) {
                    case SYSTEM -> "system";
                    case ASSISTANT -> "assistant";
                    case USER -> "user";
                },
                "content", message.parts().stream()
                        .map(AttachmentProjectionPlan::textFallback)
                        .filter(StringUtils::isNotBlank)
                        .collect(joining("\n"))
        )));
        input.addAll(toolExchangeItems);
        input.addAll(exchange);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", modelId);
        payload.put("instructions", AgentSystemPromptBuilder.buildToolAgentPrompt(
                request.projectRoot(), systemPromptAppend, toolDefinitions.stream().anyMatch(tool -> tool.source() == AgentToolSource.MCP)
        ));
        payload.put("input", input);
        payload.put("tools", toolDefinitions.stream().map(tool -> Map.of(
                "type", "function",
                "name", tool.name(),
                "description", tool.description(),
                "parameters", tool.inputSchema(),
                "strict", false
        )).toList());
        payload.put("tool_choice", "auto");
        payload.put("stream", false);
        payload.put("store", false);
        payload.put("include", List.of("reasoning.encrypted_content"));
        ReasoningLevel level = request.reasoningLevel();
        if (level.enabled()) {
            payload.put("reasoning", Map.of("effort", OpenAiReasoningSupport.effort(level), "summary", "auto"));
        } else if (ProviderCapabilityResolver.supportsExplicitReasoningOff("OpenAI", modelId)) {
            payload.put("reasoning", Map.of("effort", "none"));
        }
        return payload;
    }

    private ValidatedResponse validateResponse(Map<String, Object> root) throws ProviderException {
        if (root == null) {
            throw invalidResponse("a response object is required");
        }
        if ("failed".equals(root.get("status"))) {
            throw providerError(200, objectMap(root.get("error")));
        }
        if (!"completed".equals(root.get("status"))) {
            throw invalidResponse("the response was incomplete or did not reach completed status");
        }
        if (!(root.get("output") instanceof List<?> output) || output.isEmpty()) {
            throw invalidResponse("a nonempty output array is required");
        }
        var text = new StringBuilder();
        var thinking = new StringBuilder();
        List<Map<String, Object>> items = new ArrayList<>();
        List<ToolInvocationRequest> invocations = new ArrayList<>();
        Set<String> callIds = new HashSet<>();
        for (Object value : output) {
            Map<String, Object> item = objectMap(value);
            if (item == null || !(item.get("type") instanceof String type)) {
                throw invalidResponse("every output item must have a type");
            }
            if (item.get("status") != null && !"completed".equals(item.get("status"))) {
                throw invalidResponse("an output item was not completed");
            }
            switch (type) {
                case "function_call" -> invocations.add(validateFunctionCall(item, callIds));
                case "message" -> appendAssistantText(item, text);
                case "reasoning" -> appendSummary(item, thinking);
                default -> throw invalidResponse("an unsupported output item was returned");
            }
            Map<String, Object> replayItem = new LinkedHashMap<>(item);
            // created_by is output-only provenance; keep opaque reasoning, IDs, and assistant phase unchanged.
            replayItem.remove("created_by");
            items.add(replayItem);
        }
        if (invocations.isEmpty() && StringUtils.isBlank(text)) {
            throw invalidResponse("assistant text, a refusal, or function calls are required");
        }
        return new ValidatedResponse(text.toString(), thinking.toString(), List.copyOf(items), List.copyOf(invocations));
    }

    private ToolInvocationRequest validateFunctionCall(Map<String, Object> item, Set<String> callIds) {
        String callId = text(item.get("call_id"));
        String name = text(item.get("name"));
        if (StringUtils.isBlank(callId) || !callIds.add(callId) || StringUtils.isBlank(name)
                || !(item.get("arguments") instanceof String arguments)) {
            throw invalidResponse("function calls require unique call IDs, names, and textual arguments");
        }
        return new ToolInvocationRequest(callId, name, arguments);
    }

    private void appendAssistantText(Map<String, Object> item, StringBuilder text) {
        if (!"assistant".equals(item.get("role")) || !(item.get("content") instanceof List<?> parts)) {
            throw invalidResponse("a message must contain assistant content");
        }
        for (Object value : parts) {
            Map<String, Object> part = objectMap(value);
            if (part == null) {
                throw invalidResponse("message content must contain objects");
            }
            Object content = switch (text(part.get("type"))) {
                case "output_text" -> part.get("text");
                case "refusal" -> part.get("refusal");
                default -> throw invalidResponse("an unsupported message content type was returned");
            };
            if (!(content instanceof String contentText)) {
                throw invalidResponse("message content must be textual");
            }
            text.append(contentText);
        }
    }

    private void appendSummary(Map<String, Object> item, StringBuilder thinking) {
        if (item.get("summary") instanceof List<?> summary) {
            summary.stream().map(this::objectMap)
                    .filter(part -> part != null && "summary_text".equals(part.get("type")))
                    .map(part -> text(part.get("text")))
                    .forEach(thinking::append);
        }
    }

    private ProviderException httpError(HttpExchangeResponse response) {
        Map<String, Object> error = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = JSON.read(response.body(), Map.class);
            error = root == null ? null : objectMap(root.get("error"));
        } catch (Exception ignored) {
        }
        return providerError(response.statusCode(), error);
    }

    private ProviderException providerError(int status, Map<String, Object> error) {
        return ProviderExceptionMapper.mapHttpStatus(
                "OpenAI", baseUrl, status,
                error == null ? "" : text(error.get("type")),
                error == null ? "" : text(error.get("code")),
                error == null ? "Responses request failed." : text(error.get("message")),
                apiKey
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private String text(Object value) {
        return value instanceof String string ? string : "";
    }

    private IllegalStateException invalidResponse(String detail) {
        return new IllegalStateException("OpenAI Responses tool turn returned an invalid response: %s.".formatted(detail));
    }

    private record ValidatedResponse(
            String assistantText,
            String reasoningText,
            List<Map<String, Object>> outputItems,
            List<ToolInvocationRequest> toolInvocations
    ) {
        @Override
        public String toString() {
            return "ValidatedResponse[assistantText=****, reasoningText=****, outputItems=****, toolInvocations=****]";
        }
    }
}
