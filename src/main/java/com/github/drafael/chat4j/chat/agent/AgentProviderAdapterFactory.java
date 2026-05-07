package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.ProviderService;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.Validate;

public class AgentProviderAdapterFactory {

    public AgentProviderAdapter create(
            String providerName,
            String modelId,
            String baseUrl,
            String apiKey,
            @NonNull ProviderService providerService,
            String agentSystemPromptAppend
    ) {
        Validate.notBlank(providerName, "providerName should not be blank");

        if (supportsAnthropicToolAdapter(providerName, modelId, baseUrl)) {
            return new AnthropicToolAgentAdapter(modelId, baseUrl, apiKey, agentSystemPromptAppend);
        }

        AgentProviderAdapter providerServiceAdapter = new ProviderServiceAgentAdapter(providerService, agentSystemPromptAppend);

        if (shouldUseCodexCliOnly(providerName)) {
            return providerServiceAdapter;
        }

        if (supportsCopilotAnthropicToolAdapter(providerName, modelId, baseUrl, apiKey)) {
            AgentProviderAdapter copilotAnthropicToolAdapter = AnthropicToolAgentAdapter.forCopilot(
                    modelId,
                    baseUrl,
                    apiKey,
                    agentSystemPromptAppend
            );
            return new OpenAiCompatibleFallbackAgentAdapter(providerName, copilotAnthropicToolAdapter, providerServiceAdapter);
        }

        if (supportsOpenAiCompatibleToolAdapter(providerName, modelId, baseUrl, apiKey)) {
            AgentProviderAdapter openAiToolAdapter = new OpenAiToolAgentAdapter(
                    providerName,
                    modelId,
                    baseUrl,
                    apiKey,
                    agentSystemPromptAppend
            );
            if (shouldUseProviderFallbackWrapper(providerName)) {
                return new OpenAiCompatibleFallbackAgentAdapter(providerName, openAiToolAdapter, providerServiceAdapter);
            }
            return openAiToolAdapter;
        }

        return providerServiceAdapter;
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
