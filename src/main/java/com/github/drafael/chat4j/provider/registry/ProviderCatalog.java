package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ModelFetcher;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderFactory;
import com.github.drafael.chat4j.provider.capability.auth.impl.EnvVarCredentialStrategy;
import com.github.drafael.chat4j.provider.core.ProviderFacade;
import com.github.drafael.chat4j.provider.core.ProviderModule;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.modules.AnthropicModule;
import com.github.drafael.chat4j.provider.modules.OpenAiCompatibleModule;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import com.github.drafael.chat4j.provider.support.PerplexityModelIds;
import java.util.List;
import lombok.NonNull;

final class ProviderCatalog {

    private static final String GOOGLE_AI_ENV_VARS = "GEMINI_API_KEY|GOOGLEAI_API_KEY";

    private final ProviderFacade providerFacade;
    private final CopilotModelMetadataStore copilotModelMetadataStore;
    private final List<ProviderDefinition> providers;

    ProviderCatalog(
            @NonNull CopilotAuthResolver copilotAuthResolver,
            @NonNull CodexAuthResolver codexAuthResolver,
            @NonNull CopilotModelMetadataStore copilotModelMetadataStore
    ) {
        this.copilotModelMetadataStore = copilotModelMetadataStore;
        providerFacade = new ProviderFacade(
                new EnvVarCredentialStrategy(),
                copilotAuthResolver,
                codexAuthResolver,
                copilotModelMetadataStore
        );
        List<ProviderModule> modules = List.of(
                new AnthropicModule("Anthropic", "ANTHROPIC_API_KEY", "https://api.anthropic.com"),
                new OpenAiCompatibleModule(
                        "Google AI",
                        AuthType.ENV_VAR,
                        GOOGLE_AI_ENV_VARS,
                        null,
                        "https://generativelanguage.googleapis.com/v1beta/openai",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "OpenAI Codex",
                        AuthType.CODEX_OAUTH,
                        null,
                        null,
                        "https://api.openai.com/v1",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "GitHub Copilot",
                        AuthType.COPILOT_OAUTH,
                        null,
                        null,
                        "https://api.githubcopilot.com",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "OpenAI",
                        AuthType.ENV_VAR,
                        "OPENAI_API_KEY",
                        null,
                        "https://api.openai.com/v1",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "Perplexity",
                        AuthType.ENV_VAR,
                        "PERPLEXITY_API_KEY",
                        null,
                        "https://api.perplexity.ai",
                        copilotModelMetadataStore,
                        PerplexityModelIds.SONAR_MODELS,
                        ProviderCapabilities.chatModelsAndNativeWebSearch()
                ),
                new OpenAiCompatibleModule(
                        "OpenRouter",
                        AuthType.ENV_VAR,
                        "OPENROUTER_API_KEY",
                        null,
                        "https://openrouter.ai/api/v1",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "Groq",
                        AuthType.ENV_VAR,
                        "GROQ_API_KEY",
                        null,
                        "https://api.groq.com/openai/v1",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "DeepSeek",
                        AuthType.ENV_VAR,
                        "DEEPSEEK_API_KEY",
                        null,
                        "https://api.deepseek.com",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "Mistral",
                        AuthType.ENV_VAR,
                        "MISTRAL_API_KEY",
                        null,
                        "https://api.mistral.ai/v1",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "xAI",
                        AuthType.ENV_VAR,
                        "XAI_API_KEY",
                        null,
                        "https://api.x.ai/v1",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "LM Studio",
                        AuthType.ENV_VAR,
                        null,
                        "lmstudio",
                        "http://localhost:1234/v1",
                        copilotModelMetadataStore
                ),
                new OpenAiCompatibleModule(
                        "Ollama",
                        AuthType.ENV_VAR,
                        null,
                        "ollama",
                        "http://localhost:11434/v1",
                        copilotModelMetadataStore
                )
        );
        providers = modules.stream()
                .map(module -> new ProviderDefinition(module.descriptor(), module))
                .toList();
    }

    List<ProviderDefinition> allProviders() {
        return providers;
    }

    ProviderFactory createFactory(String providerName, String envVar, String baseUrl) {
        ProviderDefinition providerDefinition = findRequiredProvider(providerName);
        return model -> providerDefinition.module().createService(
                resolveRuntime(providerDefinition, envVar, baseUrl, model));
    }

    ModelFetcher createFetcher(String providerName, String envVar, String baseUrl) {
        ProviderDefinition providerDefinition = findRequiredProvider(providerName);
        return () -> {
            long metadataGeneration = copilotModelMetadataStore.currentGeneration();
            ProviderRuntime runtime = resolveRuntime(providerDefinition, envVar, baseUrl, null);
            return providerDefinition.module()
                    .createModelFetcher(runtime, metadataGeneration)
                    .fetchModels();
        };
    }

    private ProviderRuntime resolveRuntime(
            ProviderDefinition providerDefinition,
            String envVar,
            String baseUrl,
            String model
    ) {
        return providerFacade.resolveRuntime(
                providerDefinition.descriptor(),
                envVar,
                baseUrl,
                model
        );
    }

    private ProviderDefinition findRequiredProvider(String providerName) {
        return providers.stream()
                .filter(providerDefinition -> providerDefinition.name().equals(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: %s".formatted(providerName)));
    }
}
