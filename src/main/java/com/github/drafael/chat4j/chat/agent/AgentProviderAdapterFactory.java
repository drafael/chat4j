package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.ProviderService;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.Validate;

import java.util.List;

@RequiredArgsConstructor
public class AgentProviderAdapterFactory {

    @NonNull
    private final ProviderAttachmentSupport attachmentSupport;

    public AgentProviderAdapter create(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey,
            @NonNull ProviderService providerService,
            String agentSystemPromptAppend
    ) {
        return create(
                providerName,
                modelId,
                baseUrl,
                apiKey,
                providerService,
                agentSystemPromptAppend,
                LocalAgentToolCatalog.definitions()
        );
    }

    public AgentProviderAdapter create(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey,
            @NonNull ProviderService providerService,
            String agentSystemPromptAppend,
            @NonNull List<AgentToolDefinition> toolDefinitions
    ) {
        Validate.notBlank(providerName, "providerName should not be blank");
        List<AgentToolDefinition> immutableDefinitions = List.copyOf(toolDefinitions);
        boolean requiresMcpTools = immutableDefinitions.stream().anyMatch(tool -> tool.source() == AgentToolSource.MCP);
        boolean anthropicToolPath = supportsAnthropicToolAdapter(providerName, modelId, baseUrl)
                || supportsCopilotAnthropicToolAdapter(providerName, modelId, baseUrl, apiKey);
        if (immutableDefinitions.size() > 128 && !anthropicToolPath) {
            throw new IllegalStateException("OpenAI-compatible Agent Mode supports at most 128 tools.");
        }

        if (supportsAnthropicToolAdapter(providerName, modelId, baseUrl)) {
            return new AnthropicToolAgentAdapter(
                    modelId,
                    baseUrl,
                    apiKey,
                    agentSystemPromptAppend,
                    attachmentSupport,
                    immutableDefinitions
            );
        }

        AgentProviderAdapter providerServiceAdapter = new ProviderServiceAgentAdapter(providerService, agentSystemPromptAppend);

        if (shouldUseCodexCliOnly(providerName)) {
            if (requiresMcpTools) {
                throw unsupportedMcpPath(providerName);
            }
            return providerServiceAdapter;
        }

        if (supportsCopilotAnthropicToolAdapter(providerName, modelId, baseUrl, apiKey)) {
            AgentProviderAdapter copilotAnthropicToolAdapter = AnthropicToolAgentAdapter.forCopilot(
                    modelId,
                    baseUrl,
                    apiKey,
                    agentSystemPromptAppend,
                    attachmentSupport,
                    immutableDefinitions
            );
            return requiresMcpTools
                    ? copilotAnthropicToolAdapter
                    : new OpenAiCompatibleFallbackAgentAdapter(providerName, copilotAnthropicToolAdapter, providerServiceAdapter);
        }

        if (supportsOpenAiCompatibleToolAdapter(providerName, modelId, baseUrl, apiKey)) {
            AgentProviderAdapter openAiToolAdapter = new OpenAiToolAgentAdapter(
                    providerName,
                    modelId,
                    baseUrl,
                    apiKey,
                    agentSystemPromptAppend,
                    attachmentSupport,
                    immutableDefinitions
            );
            if (!requiresMcpTools && shouldUseProviderFallbackWrapper(providerName)) {
                return new OpenAiCompatibleFallbackAgentAdapter(providerName, openAiToolAdapter, providerServiceAdapter);
            }
            return openAiToolAdapter;
        }

        if (requiresMcpTools) {
            throw unsupportedMcpPath(providerName);
        }
        return providerServiceAdapter;
    }

    private IllegalStateException unsupportedMcpPath(String providerName) {
        return new IllegalStateException("%s cannot advertise MCP tools for the selected model."
                .formatted(StringUtils.defaultIfBlank(providerName, "This provider")));
    }

    private boolean supportsOpenAiCompatibleToolAdapter(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        if (StringUtils.isBlank(modelId) || StringUtils.isBlank(baseUrl)) {
            return false;
        }

        if (requiresOAuthBearerToken(providerName) && StringUtils.isBlank(apiKey)) {
            return false;
        }

        return !Strings.CI.equals(providerName, "Anthropic");
    }

    private boolean supportsCopilotAnthropicToolAdapter(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey
    ) {
        return Strings.CI.equals(providerName, "GitHub Copilot")
                && StringUtils.isNotBlank(modelId)
                && StringUtils.isNotBlank(baseUrl)
                && StringUtils.isNotBlank(apiKey)
                && Strings.CI.startsWith(modelId.trim(), "claude-");
    }

    private boolean shouldUseCodexCliOnly(String providerName) {
        return Strings.CI.equals(providerName, "OpenAI Codex");
    }

    private boolean shouldUseProviderFallbackWrapper(String providerName) {
        return Strings.CI.equals(providerName, "Google AI")
                || Strings.CI.equals(providerName, "GitHub Copilot")
                || Strings.CI.equals(providerName, "LM Studio")
                || Strings.CI.equals(providerName, "Ollama");
    }

    private boolean requiresOAuthBearerToken(String providerName) {
        return Strings.CI.equals(providerName, "OpenAI Codex")
                || Strings.CI.equals(providerName, "GitHub Copilot");
    }

    private boolean supportsAnthropicToolAdapter(String providerName, String modelId, String baseUrl) {
        return Strings.CI.equals(providerName, "Anthropic")
                && StringUtils.isNotBlank(modelId)
                && StringUtils.isNotBlank(baseUrl);
    }
}
