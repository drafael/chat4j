package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.chat.render.BoundedUtf8;
import com.github.drafael.chat4j.http.HttpBody;
import com.github.drafael.chat4j.http.HttpExchangeRequest;
import com.github.drafael.chat4j.http.HttpExchangeResponse;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan;
import com.github.drafael.chat4j.provider.support.AttachmentProjectionPlan.ProjectedMessage;
import com.github.drafael.chat4j.provider.support.CopilotRequestHeaders;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import com.github.drafael.chat4j.http.JavaNetHttpTransport.RedirectPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;

final class AnthropicToolAgentAdapter implements AgentProviderAdapter {

    private static final JsonCodec JSON = JsonCodec.standard();
    private static final HttpTransport HTTP_TRANSPORT = JavaNetHttpTransport.create(Duration.ofSeconds(5), RedirectPolicy.NEVER);

    private final String modelId;
    private final String baseUrl;
    private final String apiKey;
    private final String systemPromptAppend;
    private final AuthMode authMode;
    private final ProviderAttachmentSupport attachmentSupport;
    private final List<AgentToolDefinition> agentToolDefinitions;
    private final List<Map<String, Object>> toolExchangeMessages = new ArrayList<>();
    private List<Map<String, Object>> pendingToolUses = emptyList();

    AnthropicToolAgentAdapter(
            String modelId,
            String baseUrl,
            String apiKey,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        this(
                modelId,
                baseUrl,
                apiKey,
                "",
                AuthMode.ANTHROPIC_API_KEY,
                attachmentSupport,
                LocalAgentToolCatalog.definitions()
        );
    }

    AnthropicToolAgentAdapter(
            String modelId,
            String baseUrl,
            String apiKey,
            String systemPromptAppend,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        this(
                modelId,
                baseUrl,
                apiKey,
                systemPromptAppend,
                AuthMode.ANTHROPIC_API_KEY,
                attachmentSupport,
                LocalAgentToolCatalog.definitions()
        );
    }

    AnthropicToolAgentAdapter(
            String modelId,
            String baseUrl,
            String apiKey,
            String systemPromptAppend,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull List<AgentToolDefinition> agentToolDefinitions
    ) {
        this(
                modelId,
                baseUrl,
                apiKey,
                systemPromptAppend,
                AuthMode.ANTHROPIC_API_KEY,
                attachmentSupport,
                agentToolDefinitions
        );
    }

    static AnthropicToolAgentAdapter forCopilot(
            String modelId,
            String baseUrl,
            String apiKey,
            String systemPromptAppend,
            @NonNull ProviderAttachmentSupport attachmentSupport
    ) {
        return new AnthropicToolAgentAdapter(
                modelId,
                baseUrl,
                apiKey,
                systemPromptAppend,
                AuthMode.COPILOT_BEARER,
                attachmentSupport,
                LocalAgentToolCatalog.definitions()
        );
    }

    static AnthropicToolAgentAdapter forCopilot(
            String modelId,
            String baseUrl,
            String apiKey,
            String systemPromptAppend,
            @NonNull ProviderAttachmentSupport attachmentSupport,
            @NonNull List<AgentToolDefinition> agentToolDefinitions
    ) {
        return new AnthropicToolAgentAdapter(
                modelId,
                baseUrl,
                apiKey,
                systemPromptAppend,
                AuthMode.COPILOT_BEARER,
                attachmentSupport,
                agentToolDefinitions
        );
    }

    private AnthropicToolAgentAdapter(
            String modelId,
            String baseUrl,
            String apiKey,
            String systemPromptAppend,
            AuthMode authMode,
            ProviderAttachmentSupport attachmentSupport,
            List<AgentToolDefinition> agentToolDefinitions
    ) {
        this.modelId = StringUtils.defaultString(modelId);
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.systemPromptAppend = StringUtils.defaultString(systemPromptAppend);
        this.authMode = authMode;
        this.attachmentSupport = attachmentSupport;
        this.agentToolDefinitions = List.copyOf(agentToolDefinitions);
    }

    @Override
    public AgentTurnResult executeTurn(@NonNull AgentRunRequest request, @NonNull AgentRunCallbacks callbacks) {
        try {
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            if (!request.toolResults().isEmpty() && !pendingToolUses.isEmpty()) {
                appendToolExchange(request.toolResults());
            }

            AttachmentProjectionPlan projectionPlan = AttachmentProjectionPlan.create(
                    request.history(),
                    attachmentSupport,
                    AttachmentProjectionPlan.metadataOnly(),
                    request.isCancelled()
            );
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", modelId);
            payload.put("max_tokens", 4096);
            payload.put("messages", buildMessages(projectionPlan));
            payload.put("tools", toolDefinitions());
            payload.put("tool_choice", Map.of("type", "auto"));

            String systemPrompt = resolveSystemPrompt(projectionPlan);
            payload.put("system", mergeSystemPrompt(systemPrompt, request));

            Map<String, String> headers = authHeaders();
            headers.put("Content-Type", "application/json");
            headers.put("anthropic-version", "2023-06-01");
            var httpRequest = new HttpExchangeRequest(
                    "POST",
                    URI.create(messagesEndpoint(baseUrl)),
                    headers,
                    HttpBody.bytes(JSON.writeBytes(payload)),
                    Duration.ofSeconds(60),
                    0
            );
            HttpExchangeResponse response = HTTP_TRANSPORT.send(httpRequest, request.isCancelled());
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Anthropic tool turn failed (%d): %s".formatted(
                        response.statusCode(),
                        BoundedUtf8.presentation(response.bodyText(), 512, 2_048)
                ));
            }

            AnthropicAgentApi.Response root = JSON.read(response.body(), AnthropicAgentApi.Response.class);
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            List<AnthropicAgentApi.ContentBlock> content = root.content() == null ? emptyList() : root.content();
            String assistantText = extractAssistantText(content);
            if (StringUtils.isNotBlank(assistantText) && !shouldStop(request)) {
                callbacks.onToken().accept(assistantText);
            }

            List<ToolInvocationRequest> toolInvocations = extractToolInvocations(content);
            if (shouldStop(request)) {
                return new AgentTurnResult(false, emptyList());
            }
            if (!toolInvocations.isEmpty()) {
                pendingToolUses = toPendingToolUses(content);
                return AgentTurnResult.continueWithTools(toolInvocations);
            }

            pendingToolUses = emptyList();
            return AgentTurnResult.complete();
        } catch (CancellationException e) {
            return new AgentTurnResult(false, emptyList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private Map<String, String> authHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (StringUtils.isBlank(apiKey)) {
            return headers;
        }
        if (authMode == AuthMode.COPILOT_BEARER) {
            headers.put("Authorization", "Bearer %s".formatted(apiKey));
            headers.putAll(CopilotRequestHeaders.asMap());
        } else {
            headers.put("x-api-key", apiKey);
        }
        return headers;
    }

    private String resolveSystemPrompt(AttachmentProjectionPlan projectionPlan) {
        return projectionPlan.messages().stream()
                .filter(message -> message.role() == Role.SYSTEM)
                .flatMap(message -> message.parts().stream())
                .map(AttachmentProjectionPlan::textFallback)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n"));
    }

    private String mergeSystemPrompt(String userSystemPrompt, AgentRunRequest request) {
        String basePrompt = AgentSystemPromptBuilder.buildToolAgentPrompt(
                request.projectRoot(),
                systemPromptAppend,
                agentToolDefinitions.stream().anyMatch(tool -> tool.source() == AgentToolSource.MCP)
        );
        if (StringUtils.isBlank(userSystemPrompt)) {
            return basePrompt;
        }

        return "%s\n\n%s".formatted(basePrompt, userSystemPrompt.trim());
    }

    private List<Map<String, Object>> buildMessages(AttachmentProjectionPlan projectionPlan) {
        List<Map<String, Object>> base = projectionPlan.messages().stream()
                .filter(message -> message.role() != Role.SYSTEM)
                .map(this::toAnthropicMessage)
                .toList();

        List<Map<String, Object>> combined = new ArrayList<>(base);
        combined.addAll(toolExchangeMessages);
        return combined;
    }

    private Map<String, Object> toAnthropicMessage(ProjectedMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role() == Role.ASSISTANT ? "assistant" : "user");
        String content = message.parts().stream()
                .map(AttachmentProjectionPlan::textFallback)
                .filter(StringUtils::isNotBlank)
                .collect(joining("\n"));
        payload.put("content", List.of(Map.of("type", "text", "text", content)));
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

    private String extractAssistantText(List<AnthropicAgentApi.ContentBlock> content) {
        return content.stream()
                .filter(block -> block != null && Strings.CS.equals(block.type(), "text"))
                .map(AnthropicAgentApi.ContentBlock::text)
                .map(StringUtils::defaultString)
                .collect(joining());
    }

    private List<ToolInvocationRequest> extractToolInvocations(List<AnthropicAgentApi.ContentBlock> content) {
        return content.stream()
                .filter(block -> block != null && Strings.CS.equals(block.type(), "tool_use"))
                .filter(block -> StringUtils.isNotBlank(block.name()))
                .map(block -> new ToolInvocationRequest(
                        StringUtils.defaultString(block.id()),
                        block.name(),
                        block.input() == null ? "{}" : JSON.writeString(block.input())
                ))
                .toList();
    }

    private List<Map<String, Object>> toPendingToolUses(List<AnthropicAgentApi.ContentBlock> content) {
        return content.stream()
                .filter(block -> block != null && Strings.CS.equals(block.type(), "tool_use"))
                .map(AnthropicAgentApi.ContentBlock::asToolUseMap)
                .toList();
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
        return agentToolDefinitions.stream()
                .map(this::toolDefinition)
                .toList();
    }

    private Map<String, Object> toolDefinition(AgentToolDefinition tool) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", tool.name());
        definition.put("description", tool.description());
        definition.put("input_schema", tool.inputSchema());
        return definition;
    }

    private enum AuthMode {
        ANTHROPIC_API_KEY,
        COPILOT_BEARER
    }
}
