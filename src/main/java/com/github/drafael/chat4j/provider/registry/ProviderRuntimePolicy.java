package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
final class ProviderRuntimePolicy {

    record RuntimeConfig(boolean enabled, String baseUrl) {
    }

    private static final Duration AUTH_STATUS_CACHE_TTL = Duration.ofSeconds(15);

    private final CopilotAuthResolver copilotAuthResolver;
    private final CodexAuthResolver codexAuthResolver;
    private final ConcurrentHashMap<String, CopilotAuthStatusSnapshot> copilotAuthStatusByProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> copilotAuthRefreshInFlightByProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CodexAuthStatusSnapshot> codexAuthStatusByProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> codexAuthRefreshInFlightByProvider = new ConcurrentHashMap<>();
    private volatile Map<String, RuntimeConfig> runtimeConfigByProvider = Map.of();

    ProviderRuntimePolicy() {
        this(new CopilotAuthResolver(), new CodexAuthResolver());
    }

    ProviderRuntimePolicy(CopilotAuthResolver copilotAuthResolver) {
        this(copilotAuthResolver, new CodexAuthResolver());
    }

    ProviderRuntimePolicy(CopilotAuthResolver copilotAuthResolver, CodexAuthResolver codexAuthResolver) {
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
            case COPILOT_OAUTH -> hasCopilotAuthCredentials(providerDefinition);
            case CODEX_OAUTH -> hasCodexAuthCredentials(providerDefinition);
            case ENV_VAR -> CredentialResolver.hasRequiredCredentials(providerDefinition.envVar());
        };
    }

    private boolean hasCopilotAuthCredentials(ProviderDefinition providerDefinition) {
        String providerName = providerDefinition.name();
        CopilotAuthStatusSnapshot cached = copilotAuthStatusByProvider.get(providerName);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.checkedAt().plus(AUTH_STATUS_CACHE_TTL))) {
            return cached.authorized();
        }

        if (SwingUtilities.isEventDispatchThread()) {
            triggerCopilotAuthStatusRefresh(providerDefinition);
            return cached != null && cached.authorized();
        }

        return refreshCopilotAuthStatus(providerDefinition, now);
    }

    private boolean hasCodexAuthCredentials(ProviderDefinition providerDefinition) {
        String providerName = providerDefinition.name();
        CodexAuthStatusSnapshot cached = codexAuthStatusByProvider.get(providerName);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.checkedAt().plus(AUTH_STATUS_CACHE_TTL))) {
            return cached.authorized();
        }

        if (SwingUtilities.isEventDispatchThread()) {
            triggerCodexAuthStatusRefresh(providerDefinition);
            return cached != null && cached.authorized();
        }

        return refreshCodexAuthStatus(providerDefinition, now);
    }

    private boolean refreshCopilotAuthStatus(ProviderDefinition providerDefinition, Instant checkedAt) {
        boolean authorized = copilotAuthResolver.resolveStatus().authorized();
        copilotAuthStatusByProvider.put(providerDefinition.name(), new CopilotAuthStatusSnapshot(authorized, checkedAt));
        return authorized;
    }

    private boolean refreshCodexAuthStatus(ProviderDefinition providerDefinition, Instant checkedAt) {
        boolean authorized = codexAuthResolver.resolveStatus().authorized();
        codexAuthStatusByProvider.put(providerDefinition.name(), new CodexAuthStatusSnapshot(authorized, checkedAt));
        return authorized;
    }

    private void triggerCopilotAuthStatusRefresh(ProviderDefinition providerDefinition) {
        AtomicBoolean inFlight = copilotAuthRefreshInFlightByProvider
                .computeIfAbsent(providerDefinition.name(), ignored -> new AtomicBoolean());
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                refreshCopilotAuthStatus(providerDefinition, Instant.now());
            } finally {
                inFlight.set(false);
            }
        });
    }

    private void triggerCodexAuthStatusRefresh(ProviderDefinition providerDefinition) {
        AtomicBoolean inFlight = codexAuthRefreshInFlightByProvider
                .computeIfAbsent(providerDefinition.name(), ignored -> new AtomicBoolean());
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                refreshCodexAuthStatus(providerDefinition, Instant.now());
            } finally {
                inFlight.set(false);
            }
        });
    }

    /**
     * Pre-checks auth-dependent providers in parallel so that subsequent
     * {@link #hasRequiredCredentials} calls hit the warm cache instead of
     * performing expensive checks sequentially.
     */
    void warmOAuthStatusCache(List<ProviderDefinition> providers) {
        List<ProviderDefinition> oauthProviders = providers.stream()
                .filter(this::isEnabled)
                .filter(p -> p.descriptor().authType() == AuthType.COPILOT_OAUTH
                        || p.descriptor().authType() == AuthType.CODEX_OAUTH)
                .toList();

        List<CompletableFuture<Void>> futures = oauthProviders.stream()
                .map(p -> CompletableFuture.runAsync(() -> hasRequiredCredentials(p)))
                .toList();
        if (SwingUtilities.isEventDispatchThread()) {
            log.debug("Scheduled OAuth status cache warm-up from EDT: providers={}", oauthProviders.size());
            return;
        }

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

    private record CopilotAuthStatusSnapshot(boolean authorized, Instant checkedAt) {
    }

    private record CodexAuthStatusSnapshot(boolean authorized, Instant checkedAt) {
    }
}
