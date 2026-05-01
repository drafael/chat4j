package com.github.drafael.chat4j.provider.core;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.auth.CredentialStrategy;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotModelMetadataStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static java.util.Collections.emptyList;

@Slf4j
public class ProviderFacade {

    private final CredentialStrategy credentialStrategy;
    private final CopilotAuthResolver copilotAuthResolver;
    private final CodexAuthResolver codexAuthResolver;
    private final CopilotModelMetadataStore copilotModelMetadataStore;

    public ProviderFacade(CredentialStrategy credentialStrategy) {
        this(
                credentialStrategy,
                new CopilotAuthResolver(),
                new CodexAuthResolver(),
                new CopilotModelMetadataStore()
        );
    }

    public ProviderFacade(CredentialStrategy credentialStrategy, CopilotModelMetadataStore copilotModelMetadataStore) {
        this(
                credentialStrategy,
                new CopilotAuthResolver(),
                new CodexAuthResolver(),
                copilotModelMetadataStore
        );
    }

    ProviderFacade(
            CredentialStrategy credentialStrategy,
            CopilotAuthResolver copilotAuthResolver
    ) {
        this(
                credentialStrategy,
                copilotAuthResolver,
                new CodexAuthResolver(),
                new CopilotModelMetadataStore()
        );
    }

    ProviderFacade(
            CredentialStrategy credentialStrategy,
            CopilotAuthResolver copilotAuthResolver,
            CopilotModelMetadataStore copilotModelMetadataStore
    ) {
        this(
                credentialStrategy,
                copilotAuthResolver,
                new CodexAuthResolver(),
                copilotModelMetadataStore
        );
    }

    ProviderFacade(
            CredentialStrategy credentialStrategy,
            CopilotAuthResolver copilotAuthResolver,
            CodexAuthResolver codexAuthResolver,
            CopilotModelMetadataStore copilotModelMetadataStore
    ) {
        this.credentialStrategy = credentialStrategy;
        this.copilotAuthResolver = copilotAuthResolver;
        this.codexAuthResolver = codexAuthResolver;
        this.copilotModelMetadataStore = copilotModelMetadataStore;
    }

    public ProviderRuntime resolveRuntime(
        ProviderDescriptor descriptor,
        String envVarExpression,
        String configuredBaseUrl,
        String selectedModel
    ) {
        String effectiveEnvVar = envVarExpression == null ? descriptor.credentialEnvVar() : envVarExpression;
        String baseUrl = descriptor.normalizeBaseUrl(
                configuredBaseUrl == null || configuredBaseUrl.isBlank()
                        ? descriptor.defaultBaseUrl()
                        : configuredBaseUrl
        );

        String selectedEnvVar = descriptor.authType() == AuthType.ENV_VAR
                ? credentialStrategy.resolveCredentialEnvVar(effectiveEnvVar)
                : null;

        String apiKey = switch (descriptor.authType()) {
            case COPILOT_OAUTH -> resolveCopilotAuthApiKey(selectedModel);
            case CODEX_OAUTH -> resolveCodexAuthApiKey(selectedModel);
            case ENV_VAR -> credentialStrategy.resolveApiKey(effectiveEnvVar, descriptor.fallbackApiKey());
        };

        log.debug("Resolved provider runtime: provider={} authType={} selectedModel={} baseUrl={} credentialReady={}",
                descriptor.name(),
                descriptor.authType(),
                StringUtils.defaultIfBlank(selectedModel, "none"),
                baseUrl,
                StringUtils.isNotBlank(apiKey));

        return new ProviderRuntime(
            descriptor,
            selectedEnvVar,
            baseUrl,
            apiKey,
            selectedModel,
            resolveSelectedModelSupportedEndpoints(descriptor, baseUrl, selectedModel)
        );
    }

    private List<String> resolveSelectedModelSupportedEndpoints(
            ProviderDescriptor descriptor,
            String baseUrl,
            String selectedModel
    ) {
        if (descriptor.authType() != AuthType.COPILOT_OAUTH || StringUtils.isBlank(selectedModel)) {
            return emptyList();
        }

        return copilotModelMetadataStore.supportedEndpoints(baseUrl, selectedModel);
    }

    private String resolveCopilotAuthApiKey(String selectedModel) {
        String token = selectedModel == null
                ? copilotAuthResolver.resolveBearerTokenOrNull()
                : copilotAuthResolver.resolveBearerToken();

        return selectedModel == null
                ? nullableToEmpty(token)
                : token;
    }

    private String resolveCodexAuthApiKey(String selectedModel) {
        String token = selectedModel == null
                ? codexAuthResolver.resolveBearerTokenOrNull()
                : codexAuthResolver.resolveBearerToken();

        return selectedModel == null
                ? nullableToEmpty(token)
                : token;
    }

    private String nullableToEmpty(String value) {
        return value == null ? "" : value;
    }
}
