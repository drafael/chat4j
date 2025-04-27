package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.capability.auth.impl.CliOAuthRunner;
import com.github.drafael.chat4j.provider.support.CredentialResolver;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ProviderRuntimePolicy {

    record RuntimeConfig(boolean enabled, String baseUrl) {
    }

    private static final CliOAuthRunner CLI_OAUTH_RUNNER = new CliOAuthRunner();
    private static final Duration CLI_OAUTH_STATUS_CACHE_TTL = Duration.ofSeconds(15);

    private final ConcurrentHashMap<String, CliOauthStatusSnapshot> cliOauthStatusByProvider = new ConcurrentHashMap<>();
    private volatile Map<String, RuntimeConfig> runtimeConfigByProvider = Map.of();

    void applyRuntimeConfig(Map<String, RuntimeConfig> runtimeConfig) {
        runtimeConfigByProvider = runtimeConfig == null ? Map.of() : Map.copyOf(runtimeConfig);
    }

    boolean isEnabled(ProviderDefinition providerDefinition) {
        RuntimeConfig runtimeConfig = runtimeConfigByProvider.get(providerDefinition.name());
        return runtimeConfig == null || runtimeConfig.enabled();
    }

    boolean hasRequiredCredentials(ProviderDefinition providerDefinition) {
        return providerDefinition.descriptor().authType() == AuthType.CLI_OAUTH
                ? hasCliOauthCredentials(providerDefinition)
                : CredentialResolver.hasRequiredCredentials(providerDefinition.envVar());
    }

    private boolean hasCliOauthCredentials(ProviderDefinition providerDefinition) {
        String providerName = providerDefinition.name();
        CliOauthStatusSnapshot cached = cliOauthStatusByProvider.get(providerName);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.checkedAt().plus(CLI_OAUTH_STATUS_CACHE_TTL))) {
            return cached.authorized();
        }

        boolean authorized = CLI_OAUTH_RUNNER.checkStatus(providerDefinition.descriptor().oauthCliSpec()).authorized();
        cliOauthStatusByProvider.put(providerName, new CliOauthStatusSnapshot(authorized, now));
        return authorized;
    }

    String effectiveBaseUrl(ProviderDefinition providerDefinition) {
        RuntimeConfig runtimeConfig = runtimeConfigByProvider.get(providerDefinition.name());
        if (runtimeConfig == null || runtimeConfig.baseUrl() == null || runtimeConfig.baseUrl().isBlank()) {
            return providerDefinition.baseUrl();
        }
        return runtimeConfig.baseUrl().trim();
    }

    private record CliOauthStatusSnapshot(boolean authorized, Instant checkedAt) {
    }
}
