package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

final class AnthropicToolAgentAdapter implements AgentProviderAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASH_TOOL_DESCRIPTION = String.join(
            " ",
            "Execute a bash shell command with the project root as working directory.",
            "This command is not sandboxed and can access files outside the project root",
            "with the Chat4J app user's permissions."
    );
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String modelId;
    private final String baseUrl;
    private final String apiKey;
    private final String systemPromptAppend;
    private final AuthMode authMode;
    private final List<Map<String, Object>> toolExchangeMessages = new ArrayList<>();
    private List<Map<String, Object>> pendingToolUses = emptyList();

    AnthropicToolAgentAdapter(String modelId, String baseUrl, String apiKey) {
        this(modelId, baseUrl, apiKey, "");
    }

    AnthropicToolAgentAdapter(String modelId, String baseUrl, String apiKey, String systemPromptAppend) {
        this(modelId, baseUrl, apiKey, systemPromptAppend, AuthMode.ANTHROPIC_API_KEY);
    }

    static AnthropicToolAgentAdapter forCopilot(String modelId, String baseUrl, String apiKey, String systemPromptAppend) {
        return new AnthropicToolAgentAdapter(modelId, baseUrl, apiKey, systemPromptAppend, AuthMode.COPILOT_BEARER);
    }

    private AnthropicToolAgentAdapter(String modelId, String baseUrl, String apiKey, String systemPromptAppend, AuthMode authMode) {
        this.modelId = StringUtils.defaultString(modelId);
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.systemPromptAppend = StringUtils.defaultString(systemPromptAppend);
        this.authMode = authMode;
    }

    @Override
    public AgentTurnResult executeTurn(AgentRunRequest request, AgentRunCallbacks callbacks) {
        try {
            if (!request.toolResults().isEmpty() && !pendingToolUses.isEmpty()) {
                appendToolExchange(request.toolResults());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", modelId);
            payload.put("max_tokens", 4096);
            payload.put("messages", buildMessages(request.history()));
            payload.put("tools", toolDefinitions());
            payload.put("tool_choice", Map.of("type", "auto"));

            String systemPrompt = resolveSystemPrompt(request.history());
            payload.put("system", mergeSystemPrompt(systemPrompt, request));

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(messagesEndpoint(baseUrl)))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8));

            applyAuthHeaders(requestBuilder);

            HttpResponse<String> response = HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Anthropic tool turn failed (%d): %s"
                        .formatted(response.statusCode(), response.body()));
            }

            JsonNode root = JSON.readTree(response.body());
            JsonNode contentNode = root.path("content");
            String assistantText = extractAssistantText(contentNode);
            if (StringUtils.isNotBlank(assistantText)) {
                callbacks.onToken().accept(assistantText);
            }

            List<ToolInvocationRequest> toolInvocations = extractToolInvocations(contentNode);
            if (!toolInvocations.isEmpty()) {
                pendingToolUses = toPendingToolUses(contentNode);
                return AgentTurnResult.continueWithTools(toolInvocations);
            }

            pendingToolUses = emptyList();
            return AgentTurnResult.complete();
        } catch (Exception e) {
            callbacks.onError().accept(e);
            return new AgentTurnResult(false, emptyList());
        }
    }

    private void applyAuthHeaders(HttpRequest.Builder requestBuilder) {
        if (StringUtils.isBlank(apiKey)) {
            return;
        }

        if (authMode == AuthMode.COPILOT_BEARER) {
            requestBuilder.header("Authorization", "Bearer %s".formatted(apiKey));
            CopilotRequestHeaders.asMap().forEach(requestBuilder::header);
            return;
        }

        requestBuilder.header("x-api-key", apiKey);
    }

    private String resolveSystemPrompt(List<Message> history) {
        return history.stream()
                .filter(message -> message.role() == Role.SYSTEM)
                .map(Message::content)
                .findFirst()
                .orElse("");
    }

    private String mergeSystemPrompt(String userSystemPrompt, AgentRunRequest request) {
        String basePrompt = AgentSystemPromptBuilder.buildToolAgentPrompt(request.projectRoot(), systemPromptAppend);
        if (StringUtils.isBlank(userSystemPrompt)) {
            return basePrompt;
        }

        return "%s\n\n%s".formatted(basePrompt, userSystemPrompt.trim());
    }

    private List<Map<String, Object>> buildMessages(List<Message> history) {
        List<Map<String, Object>> base = history.stream()
                .filter(message -> message.role() != Role.SYSTEM)
                .map(this::toAnthropicMessage)
                .toList();

        List<Map<String, Object>> combined = new ArrayList<>(base);
        combined.addAll(toolExchangeMessages);
        return combined;
    }

    private Map<String, Object> toAnthropicMessage(Message message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role() == Role.ASSISTANT ? "assistant" : "user");
        payload.put("content", List.of(Map.of("type", "text", "text", StringUtils.defaultString(message.content()))));
        return payload;
    }

    private void appendToolExchange(List<ToolInvocationResult> toolResults) {
        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", pendingToolUses);
        toolExchangeMessages.add(assistantMessage);

        List<Map<String, Object>> toolResultBlocks = toolResults.stream()
                .map(toolResult -> {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", toolResult.id());
                    block.put("is_error", !toolResult.success());
                    String content = toolResult.success()
                            ? toolResult.output()
                            : "ERROR: %s".formatted(toolResult.error());
                    block.put("content", List.of(Map.of("type", "text", "text", StringUtils.defaultString(content))));
                    return block;
                })
                .toList();

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", toolResultBlocks);
        toolExchangeMessages.add(userMessage);

        pendingToolUses = emptyList();
    }

    private String extractAssistantText(JsonNode contentNode) {
        if (!contentNode.isArray()) {
            return "";
        }

        StringBuilder collected = new StringBuilder();
        for (JsonNode blockNode : contentNode) {
            if (StringUtils.equals(blockNode.path("type").asText(""), "text")) {
                collected.append(blockNode.path("text").asText(""));
            }
        }

        return collected.toString();
    }

    private List<ToolInvocationRequest> extractToolInvocations(JsonNode contentNode) throws Exception {
        if (!contentNode.isArray()) {
            return emptyList();
        }

        List<ToolInvocationRequest> invocations = new ArrayList<>();
        for (JsonNode blockNode : contentNode) {
            if (!StringUtils.equals(blockNode.path("type").asText(""), "tool_use")) {
                continue;
            }

            String id = blockNode.path("id").asText("");
            String name = blockNode.path("name").asText("");
            JsonNode input = blockNode.path("input");
            String argumentsJson = input.isMissingNode() || input.isNull()
                    ? "{}"
                    : JSON.writeValueAsString(input);
            if (StringUtils.isBlank(name)) {
                continue;
            }

            invocations.add(new ToolInvocationRequest(id, name, argumentsJson));
        }

        return invocations;
    }

    private List<Map<String, Object>> toPendingToolUses(JsonNode contentNode) throws Exception {
        if (!contentNode.isArray()) {
            return emptyList();
        }

        List<Map<String, Object>> pending = new ArrayList<>();
        for (JsonNode blockNode : contentNode) {
            if (!StringUtils.equals(blockNode.path("type").asText(""), "tool_use")) {
                continue;
            }

            Map<String, Object> block = JSON.convertValue(blockNode, new TypeReference<>() {
            });
            pending.add(block);
        }

        return pending;
    }

    private String normalizeBaseUrl(String rawBaseUrl) {
        String normalized = StringUtils.defaultString(rawBaseUrl).trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String messagesEndpoint(String normalizedBaseUrl) {
        if (normalizedBaseUrl.endsWith("/messages")) {
            return normalizedBaseUrl;
        }
        if (normalizedBaseUrl.endsWith("/v1")) {
            return "%s/messages".formatted(normalizedBaseUrl);
        }
        return "%s/v1/messages".formatted(normalizedBaseUrl);
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
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", required);

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("description", description);
        definition.put("input_schema", inputSchema);
        return definition;
    }

    private Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private enum AuthMode {
        ANTHROPIC_API_KEY,
        COPILOT_BEARER
    }
}
