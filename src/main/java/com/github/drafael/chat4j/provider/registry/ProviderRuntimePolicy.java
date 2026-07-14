package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static java.util.Collections.emptyMap;

@Slf4j
final class ProviderRuntimePolicy {

    record RuntimeConfig(boolean enabled, String baseUrl) {
    }

    private static final Duration AUTH_STATUS_CACHE_TTL = Duration.ofSeconds(15);

    private final CopilotAuthResolver copilotAuthResolver;
    private final CodexAuthResolver codexAuthResolver;
    private final ConcurrentHashMap<String, AuthStatusSnapshot> authStatusByProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> authRefreshInFlightByProvider = new ConcurrentHashMap<>();
    private final Object authStatusLock = new Object();
    private final Map<String, Long> authStatusGenerationByProvider = new HashMap<>();
    private volatile Map<String, RuntimeConfig> runtimeConfigByProvider = emptyMap();

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
        runtimeConfigByProvider = runtimeConfig == null ? emptyMap() : Map.copyOf(runtimeConfig);
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
        AuthStatusSnapshot cached = authStatusByProvider.get(providerName);
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
        AuthStatusSnapshot cached = authStatusByProvider.get(providerName);
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
        return refreshAuthStatus(providerDefinition, checkedAt, () -> copilotAuthResolver.resolveStatus().authorized());
    }

    private boolean refreshCodexAuthStatus(ProviderDefinition providerDefinition, Instant checkedAt) {
        return refreshAuthStatus(providerDefinition, checkedAt, () -> codexAuthResolver.resolveStatus().authorized());
    }

    private boolean refreshAuthStatus(
            ProviderDefinition providerDefinition,
            Instant checkedAt,
            BooleanSupplier resolveAuthorized
    ) {
        String providerName = providerDefinition.name();
        long generation = currentAuthStatusGeneration(providerName);
        boolean authorized = resolveAuthorized.getAsBoolean();
        synchronized (authStatusLock) {
            if (currentAuthStatusGenerationLocked(providerName) != generation) {
                return false;
            }
            authStatusByProvider.put(providerName, new AuthStatusSnapshot(authorized, checkedAt));
            return authorized;
        }
    }

    private long currentAuthStatusGeneration(String providerName) {
        synchronized (authStatusLock) {
            return currentAuthStatusGenerationLocked(providerName);
        }
    }

    private long currentAuthStatusGenerationLocked(String providerName) {
        return authStatusGenerationByProvider.getOrDefault(providerName, 0L);
    }

    void invalidateAuthStatus(String providerName) {
        synchronized (authStatusLock) {
            authStatusGenerationByProvider.merge(providerName, 1L, Long::sum);
            authStatusByProvider.remove(providerName);
            authRefreshInFlightByProvider.remove(providerName);
        }
    }

    private void triggerCopilotAuthStatusRefresh(ProviderDefinition providerDefinition) {
        triggerAuthStatusRefresh(
                providerDefinition,
                () -> refreshCopilotAuthStatus(providerDefinition, Instant.now())
        );
    }

    private void triggerCodexAuthStatusRefresh(ProviderDefinition providerDefinition) {
        triggerAuthStatusRefresh(
                providerDefinition,
                () -> refreshCodexAuthStatus(providerDefinition, Instant.now())
        );
    }

    private void triggerAuthStatusRefresh(ProviderDefinition providerDefinition, Runnable refresh) {
        AtomicBoolean inFlight = authRefreshInFlightByProvider
                .computeIfAbsent(providerDefinition.name(), ignored -> new AtomicBoolean());
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                refresh.run();
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
        String configuredBaseUrl = runtimeConfig == null
                || runtimeConfig.baseUrl() == null
                || runtimeConfig.baseUrl().isBlank()
                ? providerDefinition.baseUrl()
                : runtimeConfig.baseUrl().trim();
        return providerDefinition.descriptor().normalizeBaseUrl(configuredBaseUrl);
    }

    private record AuthStatusSnapshot(boolean authorized, Instant checkedAt) {
    }
}
