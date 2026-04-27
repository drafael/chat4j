package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.provider.api.ProviderService;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
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
        if (supportsOpenAiCompatibleToolAdapter(providerName, modelId, baseUrl, apiKey)) {
            AgentProviderAdapter openAiToolAdapter = new OpenAiToolAgentAdapter(
                    providerName,
                    modelId,
                    baseUrl,
                    apiKey,
                    agentSystemPromptAppend
            );
            if (StringUtils.equalsIgnoreCase(providerName, "OpenAI Codex")) {
                return new CodexFallbackAgentAdapter(openAiToolAdapter, providerServiceAdapter);
            }
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

        return !StringUtils.equalsIgnoreCase(providerName, "Anthropic");
    }

    private boolean shouldUseProviderFallbackWrapper(String providerName) {
        return StringUtils.equalsIgnoreCase(providerName, "Google AI")
                || StringUtils.equalsIgnoreCase(providerName, "GitHub Copilot")
                || StringUtils.equalsIgnoreCase(providerName, "LM Studio")
                || StringUtils.equalsIgnoreCase(providerName, "Ollama");
    }

    private boolean requiresOAuthBearerToken(String providerName) {
        return StringUtils.equalsIgnoreCase(providerName, "OpenAI Codex")
                || StringUtils.equalsIgnoreCase(providerName, "GitHub Copilot");
    }

    private boolean supportsAnthropicToolAdapter(String providerName, String modelId, String baseUrl) {
        return StringUtils.equalsIgnoreCase(providerName, "Anthropic")
                && StringUtils.isNotBlank(modelId)
                && StringUtils.isNotBlank(baseUrl);
    }
}
