package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.support.CopilotRequestHeaders;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

final class OpenAiToolAgentAdapter implements AgentProviderAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASH_TOOL_DESCRIPTION = String.join(
            " ",
            "Execute a bash shell command with the project root as working directory.",
            "This command is not sandboxed and can access files outside the project root",
            "with the Chat4J app user's permissions."
    );
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String providerName;
    private final String modelId;
    private final String baseUrl;
    private final String apiKey;
    private final String systemPromptAppend;
    private final List<Map<String, Object>> toolExchangeMessages = new ArrayList<>();
    private List<Map<String, Object>> pendingToolCalls = emptyList();
    private String pendingReasoningContent = "";

    OpenAiToolAgentAdapter(String providerName, String modelId, String baseUrl, String apiKey) {
        this(providerName, modelId, baseUrl, apiKey, "");
    }

    OpenAiToolAgentAdapter(String providerName, String modelId, String baseUrl, String apiKey, String systemPromptAppend) {
        this.providerName = StringUtils.defaultString(providerName);
        this.modelId = StringUtils.defaultString(modelId);
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.systemPromptAppend = StringUtils.defaultString(systemPromptAppend);
    }

    @Override
    public AgentTurnResult executeTurn(AgentRunRequest request, AgentRunCallbacks callbacks) {
        try {
            if (!request.toolResults().isEmpty() && !pendingToolCalls.isEmpty()) {
                appendToolExchange(request.toolResults());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", modelId);
            payload.put("messages", buildMessages(request));
            payload.put("tools", toolDefinitions());
            payload.put("tool_choice", "auto");
            payload.put("stream", false);

            Duration requestTimeout = resolveRequestTimeout(providerName, baseUrl);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsEndpoint(baseUrl)))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8));
            applyAuthHeaders(requestBuilder);

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(buildHttpErrorMessage(response.statusCode(), response.body()));
            }

            JsonNode root = JSON.readTree(response.body());
            JsonNode messageNode = root.path("choices").path(0).path("message");

            String assistantText = extractAssistantText(messageNode);
            if (StringUtils.isNotBlank(assistantText)) {
                callbacks.onToken().accept(assistantText);
            }

            String reasoningContent = extractReasoningContent(messageNode);
            if (StringUtils.isNotBlank(reasoningContent)) {
                callbacks.onThinkingToken().accept(reasoningContent);
            }

            List<ToolInvocationRequest> toolInvocations = extractToolInvocations(messageNode.path("tool_calls"));
            if (!toolInvocations.isEmpty()) {
                pendingToolCalls = toPendingToolCalls(messageNode.path("tool_calls"));
                pendingReasoningContent = reasoningContent;
                return AgentTurnResult.continueWithTools(toolInvocations);
            }

            pendingToolCalls = emptyList();
            pendingReasoningContent = "";
            return AgentTurnResult.complete();
        } catch (HttpTimeoutException e) {
            callbacks.onError().accept(new IllegalStateException(buildTimeoutMessage(), e));
            return new AgentTurnResult(false, emptyList());
        } catch (Exception e) {
            callbacks.onError().accept(e);
            return new AgentTurnResult(false, emptyList());
        }
    }

    private void appendToolExchange(List<ToolInvocationResult> toolResults) {
        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", null);
        if (StringUtils.isNotBlank(pendingReasoningContent)) {
            assistantMessage.put("reasoning_content", pendingReasoningContent);
        }
        assistantMessage.put("tool_calls", pendingToolCalls);
        toolExchangeMessages.add(assistantMessage);

        for (ToolInvocationResult toolResult : toolResults) {
            Map<String, Object> toolMessage = new LinkedHashMap<>();
            toolMessage.put("role", "tool");
            toolMessage.put("tool_call_id", toolResult.id());
            String content = toolResult.success()
                    ? toolResult.output()
                    : "ERROR: %s".formatted(toolResult.error());
            toolMessage.put("content", StringUtils.defaultString(content));
            toolExchangeMessages.add(toolMessage);
        }

        pendingToolCalls = emptyList();
        pendingReasoningContent = "";
    }

    private List<Map<String, Object>> buildMessages(AgentRunRequest request) {
        List<Map<String, Object>> combined = new ArrayList<>();
        combined.add(systemPromptMessage(request));

        List<Map<String, Object>> messages = request.history().stream()
                .map(this::toChatMessage)
                .toList();

        combined.addAll(messages);
        combined.addAll(toolExchangeMessages);
        return combined;
    }

    private Map<String, Object> systemPromptMessage(AgentRunRequest request) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "system");
        message.put("content", AgentSystemPromptBuilder.buildToolAgentPrompt(request.projectRoot(), systemPromptAppend));
        return message;
    }

    private Map<String, Object> toChatMessage(Message message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", toChatRole(message.role()));
        payload.put("content", StringUtils.defaultString(message.content()));
        return payload;
    }

    private String toChatRole(Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case USER -> "user";
        };
    }

    private String extractAssistantText(JsonNode messageNode) {
        JsonNode contentNode = messageNode.path("content");
        if (contentNode.isTextual()) {
            return contentNode.asText("");
        }

        if (!contentNode.isArray()) {
            return "";
        }

        StringBuilder collected = new StringBuilder();
        for (JsonNode partNode : contentNode) {
            if (partNode.path("type").asText("").equals("text")) {
                collected.append(partNode.path("text").asText(""));
            }
        }

        return collected.toString();
    }

    private String extractReasoningContent(JsonNode messageNode) {
        if (messageNode == null || messageNode.isMissingNode()) {
            return "";
        }

        List<String> keys = List.of("reasoning_content", "reasoning", "thinking", "thought");
        return keys.stream()
                .map(key -> messageNode.path(key).asText(""))
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    private List<ToolInvocationRequest> extractToolInvocations(JsonNode toolCallsNode) {
        if (!toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return emptyList();
        }

        List<ToolInvocationRequest> invocations = new ArrayList<>();
        for (JsonNode toolCallNode : toolCallsNode) {
            String id = toolCallNode.path("id").asText("");
            JsonNode functionNode = toolCallNode.path("function");
            String name = functionNode.path("name").asText("");
            String argumentsJson = functionNode.path("arguments").asText("{}");
            if (StringUtils.isBlank(name)) {
                continue;
            }
            invocations.add(new ToolInvocationRequest(id, name, argumentsJson));
        }

        return invocations;
    }

    private List<Map<String, Object>> toPendingToolCalls(JsonNode toolCallsNode) {
        if (!toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return emptyList();
        }

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (JsonNode toolCallNode : toolCallsNode) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", toolCallNode.path("function").path("name").asText(""));
            function.put("arguments", toolCallNode.path("function").path("arguments").asText("{}"));

            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", toolCallNode.path("id").asText(""));
            toolCall.put("type", "function");
            toolCall.put("function", function);
            serialized.add(toolCall);
        }

        return serialized;
    }

    static Duration resolveRequestTimeout(String providerName, String normalizedBaseUrl) {
        return Duration.ofSeconds(DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    private String buildTimeoutMessage() {
        int timeoutSeconds = (int) resolveRequestTimeout(providerName, baseUrl).toSeconds();
        return "%s tool turn timed out after %d seconds for model %s."
                .formatted(
                        StringUtils.defaultIfBlank(providerName, "OpenAI-compatible provider"),
                        timeoutSeconds,
                        StringUtils.defaultIfBlank(modelId, "unknown")
                );
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = StringUtils.defaultString(baseUrl).trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String chatCompletionsEndpoint(String normalizedBaseUrl) {
        return normalizedBaseUrl.endsWith("/chat/completions")
                ? normalizedBaseUrl
                : "%s/chat/completions".formatted(normalizedBaseUrl);
    }

    private void applyAuthHeaders(HttpRequest.Builder requestBuilder) {
        if (StringUtils.isNotBlank(apiKey)) {
            requestBuilder.header("Authorization", "Bearer %s".formatted(apiKey));
            if (StringUtils.containsIgnoreCase(providerName, "google")) {
                requestBuilder.header("x-goog-api-key", apiKey);
            }
        }

        if (StringUtils.equals(providerName, "GitHub Copilot")) {
            CopilotRequestHeaders.asMap().forEach(requestBuilder::header);
        }
    }

    private String buildHttpErrorMessage(int statusCode, String responseBody) {
        String providerLabel = StringUtils.defaultIfBlank(providerName, "OpenAI-compatible provider");

        try {
            JsonNode root = JSON.readTree(StringUtils.defaultString(responseBody));
            JsonNode errorNode = root.path("error");
            String message = sanitizeErrorMessage(errorNode.path("message").asText(""));
            String code = StringUtils.trimToEmpty(errorNode.path("code").asText(""));

            if (statusCode == 429 && StringUtils.equalsIgnoreCase(code, "insufficient_quota")) {
                return "%s tool-calling is unavailable due to insufficient_quota (HTTP 429)."
                        .formatted(providerLabel);
            }

            if (StringUtils.isNotBlank(message) && StringUtils.isNotBlank(code)) {
                return "%s tool turn failed (HTTP %d, %s): %s"
                        .formatted(providerLabel, statusCode, code, message);
            }

            if (StringUtils.isNotBlank(message)) {
                return "%s tool turn failed (HTTP %d): %s"
                        .formatted(providerLabel, statusCode, message);
            }
        } catch (Exception e) {
            // Fall through to generic message.
        }

        return "%s tool turn failed with HTTP %d".formatted(providerLabel, statusCode);
    }

    private String sanitizeErrorMessage(String message) {
        if (StringUtils.isBlank(message)) {
            return "";
        }

        String flattened = message.replace('\n', ' ').replace('\r', ' ').trim();
        return flattened.replaceAll("\\s{2,}", " ");
    }

    private List<Map<String, Object>> toolDefinitions() {
        return List.of(
                toolDefinition("read", "Read a UTF-8 text file from the project", Map.of(
                        "path", stringProperty("Path to file, relative to project root")
                ), List.of("path")),
                toolDefinition("write", "Write UTF-8 text content to a file", Map.of(
                        "path", stringProperty("Path to file, relative to project root"),
                        "content", stringProperty("File content")
                ), List.of("path", "content")),
                toolDefinition("edit", "Apply exact text replacement edits in a file", Map.of(
                        "path", stringProperty("Path to file, relative to project root"),
                        "oldText", stringProperty("Old text to replace"),
                        "newText", stringProperty("Replacement text")
                ), List.of("path", "oldText", "newText")),
                toolDefinition("ls", "List files in a directory", Map.of(
                        "path", stringProperty("Directory path, defaults to .")
                ), emptyList()),
                toolDefinition("find", "Find files recursively by name pattern", Map.of(
                        "path", stringProperty("Directory path, defaults to ."),
                        "pattern", stringProperty("Glob-style pattern, for example *.java")
                ), emptyList()),
                toolDefinition("grep", "Search for text in files", Map.of(
                        "query", stringProperty("Text to search for"),
                        "path", stringProperty("Path to file or directory, defaults to .")
                ), List.of("query")),
                toolDefinition("bash", BASH_TOOL_DESCRIPTION, Map.of(
                        "command", stringProperty("Command to execute"),
                        "timeoutSeconds", Map.of("type", "integer", "description", "Timeout in seconds")
                ), List.of("command"))
        );
    }

    private Map<String, Object> toolDefinition(
            String name,
            String description,
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", required);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("type", "function");
        definition.put("function", function);
        return definition;
    }

    private Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }
}
