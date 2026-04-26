package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.capability.auth.impl.CliOAuthRunner;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

@Slf4j
final class ProviderRuntimePolicy {

    record RuntimeConfig(boolean enabled, String baseUrl) {
    }

    private static final Duration AUTH_STATUS_CACHE_TTL = Duration.ofSeconds(15);

    private final CliOAuthRunner cliOAuthRunner;
    private final CopilotAuthResolver copilotAuthResolver;
    private final CodexAuthResolver codexAuthResolver;
    private final ConcurrentHashMap<String, CliOauthStatusSnapshot> cliOauthStatusByProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopilotAuthStatusSnapshot> copilotAuthStatusByProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CodexAuthStatusSnapshot> codexAuthStatusByProvider = new ConcurrentHashMap<>();
    private volatile Map<String, RuntimeConfig> runtimeConfigByProvider = Map.of();

    ProviderRuntimePolicy() {
        this(new CliOAuthRunner(), new CopilotAuthResolver(), new CodexAuthResolver());
    }

    ProviderRuntimePolicy(CliOAuthRunner cliOAuthRunner, CopilotAuthResolver copilotAuthResolver) {
        this(cliOAuthRunner, copilotAuthResolver, new CodexAuthResolver());
    }

    ProviderRuntimePolicy(CliOAuthRunner cliOAuthRunner, CopilotAuthResolver copilotAuthResolver, CodexAuthResolver codexAuthResolver) {
        this.cliOAuthRunner = cliOAuthRunner;
        this.copilotAuthResolver = copilotAuthResolver;
        this.codexAuthResolver = codexAuthResolver;
    }

    void applyRuntimeConfig(Map<String, RuntimeConfig> runtimeConfig) {
        runtimeConfigByProvider = runtimeConfig == null ? Map.of() : Map.copyOf(runtimeConfig);
    }

    boolean isEnabled(ProviderDefinition providerDefinition) {
        RuntimeConfig runtimeConfig = runtimeConfigByProvider.get(providerDefinition.name());
        return runtimeConfig == null || runtimeConfig.enabled();
    }

    boolean hasRequiredCredentials(ProviderDefinition providerDefinition) {
        return switch (providerDefinition.descriptor().authType()) {
            case CLI_OAUTH -> hasCliOauthCredentials(providerDefinition);
            case COPILOT_OAUTH -> hasCopilotAuthCredentials(providerDefinition);
            case CODEX_OAUTH -> hasCodexAuthCredentials(providerDefinition);
            case ENV_VAR -> CredentialResolver.hasRequiredCredentials(providerDefinition.envVar());
        };
    }

    private boolean hasCliOauthCredentials(ProviderDefinition providerDefinition) {
        String providerName = providerDefinition.name();
        CliOauthStatusSnapshot cached = cliOauthStatusByProvider.get(providerName);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.checkedAt().plus(AUTH_STATUS_CACHE_TTL))) {
            return cached.authorized();
        }

        boolean authorized = cliOAuthRunner.checkStatus(providerDefinition.descriptor().oauthCliSpec()).authorized();
        cliOauthStatusByProvider.put(providerName, new CliOauthStatusSnapshot(authorized, now));
        return authorized;
    }

    private boolean hasCopilotAuthCredentials(ProviderDefinition providerDefinition) {
        String providerName = providerDefinition.name();
        CopilotAuthStatusSnapshot cached = copilotAuthStatusByProvider.get(providerName);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.checkedAt().plus(AUTH_STATUS_CACHE_TTL))) {
            return cached.authorized();
        }

        boolean authorized = copilotAuthResolver.resolveStatus().authorized();
        copilotAuthStatusByProvider.put(providerName, new CopilotAuthStatusSnapshot(authorized, now));
        return authorized;
    }

    private boolean hasCodexAuthCredentials(ProviderDefinition providerDefinition) {
        String providerName = providerDefinition.name();
        CodexAuthStatusSnapshot cached = codexAuthStatusByProvider.get(providerName);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.checkedAt().plus(AUTH_STATUS_CACHE_TTL))) {
            return cached.authorized();
        }

        boolean authorized = codexAuthResolver.resolveStatus().authorized();
        codexAuthStatusByProvider.put(providerName, new CodexAuthStatusSnapshot(authorized, now));
        return authorized;
    }

    /**
     * Pre-checks auth-dependent providers in parallel so that subsequent
     * {@link #hasRequiredCredentials} calls hit the warm cache instead of
     * performing expensive checks sequentially.
     */
    void warmOAuthStatusCache(List<ProviderDefinition> providers) {
        List<ProviderDefinition> oauthProviders = providers.stream()
                .filter(this::isEnabled)
                .filter(p -> p.descriptor().authType() == AuthType.CLI_OAUTH
                        || p.descriptor().authType() == AuthType.COPILOT_OAUTH
                        || p.descriptor().authType() == AuthType.CODEX_OAUTH)
                .toList();

        List<CompletableFuture<Void>> futures = oauthProviders.stream()
                .map(p -> CompletableFuture.runAsync(() -> hasRequiredCredentials(p)))
                .toList();
        futures.forEach(CompletableFuture::join);

        long authorizedCount = oauthProviders.stream()
                .filter(this::hasRequiredCredentials)
                .count();
        log.debug("Warmed OAuth status cache: providers={} authorized={}", oauthProviders.size(), authorizedCount);
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

    private record CopilotAuthStatusSnapshot(boolean authorized, Instant checkedAt) {
    }

    private record CodexAuthStatusSnapshot(boolean authorized, Instant checkedAt) {
    }
}
