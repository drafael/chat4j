package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ModelFetcher;
import com.github.drafael.chat4j.provider.api.OAuthCliSpec;
import com.github.drafael.chat4j.provider.api.ProviderFactory;
import com.github.drafael.chat4j.provider.capability.auth.impl.EnvVarCredentialStrategy;
import com.github.drafael.chat4j.provider.core.ProviderFacade;
import com.github.drafael.chat4j.provider.core.ProviderModule;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.modules.AnthropicModule;
import com.github.drafael.chat4j.provider.modules.OpenAiCompatibleModule;

import java.util.ArrayList;
import java.util.List;

final class ProviderCatalog {

    private static final String GOOGLE_AI_ENV_VARS = "GEMINI_API_KEY|GOOGLEAI_API_KEY|GOOGLE_AI_API_KEY";

    private static final OAuthCliSpec CODEX_OAUTH = new OAuthCliSpec(
            List.of("codex", "login", "status"),
            List.of("codex", "login"),
            List.of("codex", "logout"),
            List.of());

    private static final OAuthCliSpec COPILOT_OAUTH = new OAuthCliSpec(
            List.of("gh", "auth", "status"),
            List.of("gh", "auth", "login"),
            List.of("gh", "auth", "logout"),
            List.of("gh", "auth", "token"));

    private static final ProviderFacade PROVIDER_FACADE = new ProviderFacade(new EnvVarCredentialStrategy());

    private static final List<ProviderDefinition> PROVIDERS = new ArrayList<>();

    static {
        List<ProviderModule> modules = List.of(
                new AnthropicModule("Anthropic", "ANTHROPIC_API_KEY", "https://api.anthropic.com"),
                new OpenAiCompatibleModule("Google AI", GOOGLE_AI_ENV_VARS, null, "https://generativelanguage.googleapis.com/v1beta/openai"),
                new OpenAiCompatibleModule(
                        "OpenAI Codex",
                        AuthType.CLI_OAUTH,
                        null,
                        null,
                        CODEX_OAUTH,
                        "https://api.openai.com/v1"
                ),
                new OpenAiCompatibleModule(
                        "GitHub Copilot",
                        AuthType.CLI_OAUTH,
                        null,
                        null,
                        COPILOT_OAUTH,
                        "https://api.githubcopilot.com"
                ),
                new OpenAiCompatibleModule("OpenAI", "OPENAI_API_KEY", null, "https://api.openai.com/v1"),
                new OpenAiCompatibleModule("OpenRouter", "OPENROUTER_API_KEY", null, "https://openrouter.ai/api/v1"),
                new OpenAiCompatibleModule("Groq", "GROQ_API_KEY", null, "https://api.groq.com/openai/v1"),
                new OpenAiCompatibleModule("DeepSeek", "DEEPSEEK_API_KEY", null, "https://api.deepseek.com"),
                new OpenAiCompatibleModule("Mistral", "MISTRAL_API_KEY", null, "https://api.mistral.ai/v1"),
                new OpenAiCompatibleModule("xAI", "XAI_API_KEY", null, "https://api.x.ai/v1"),
                new OpenAiCompatibleModule("LM Studio", null, "lmstudio", "http://localhost:1234/v1"),
                new OpenAiCompatibleModule("Ollama", null, "ollama", "http://localhost:11434/v1"));

        modules.stream()
                .map(module -> new ProviderDefinition(module.descriptor(), module))
                .forEach(PROVIDERS::add);
    }

    List<ProviderDefinition> allProviders() {
        return List.copyOf(PROVIDERS);
    }

    ProviderFactory createFactory(String providerName, String envVar, String baseUrl) {
        ProviderDefinition providerDefinition = findRequiredProvider(providerName);
        return model -> providerDefinition.module().createService(
                resolveRuntime(providerDefinition, envVar, baseUrl, model));
    }

    ModelFetcher createFetcher(String providerName,
                               String envVar,
                               String baseUrl,
                               List<String> seedModels
    ) {
        ProviderDefinition providerDefinition = findRequiredProvider(providerName);
        return () -> providerDefinition.module().createModelFetcher(
                resolveRuntime(providerDefinition, envVar, baseUrl, null)
        ).fetchModels();
    }

    private ProviderRuntime resolveRuntime(ProviderDefinition providerDefinition,
                                           String envVar,
                                           String baseUrl,
                                           String model
    ) {
        return PROVIDER_FACADE.resolveRuntime(
                providerDefinition.descriptor(),
                envVar,
                baseUrl,
                model
        );
    }

    private ProviderDefinition findRequiredProvider(String providerName) {
        return PROVIDERS.stream()
                .filter(providerDefinition -> providerDefinition.name().equals(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + providerName));
    }
}
