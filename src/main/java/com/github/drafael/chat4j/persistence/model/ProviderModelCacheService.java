package com.github.drafael.chat4j.persistence.model;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.CodexLocalModelCache;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

@Slf4j
public class ProviderModelCacheService {
    private static final Duration DEFAULT_REFRESH_TTL = Duration.ofHours(12);
    private static final String METRICS_LOG_PROPERTY = "chat4j.modelCache.metrics";

    private final ProviderModelCache modelCache;
    private final Clock clock;
    private final Duration defaultRefreshTtl;
    private final boolean metricsLoggingEnabled;
    private final ConcurrentHashMap<String, CacheEntry> cacheByProvider = new ConcurrentHashMap<>();
    private final Set<String> invalidatedProviders = ConcurrentHashMap.newKeySet();
    private final CacheMetrics metrics = new CacheMetrics();

    public ProviderModelCacheService(ProviderModelCache modelCache) {
        this(modelCache, Clock.systemUTC(), DEFAULT_REFRESH_TTL, Boolean.getBoolean(METRICS_LOG_PROPERTY));
    }

    ProviderModelCacheService(ProviderModelCache modelCache, Clock clock, Duration defaultRefreshTtl) {
        this(modelCache, clock, defaultRefreshTtl, false);
    }

    ProviderModelCacheService(ProviderModelCache modelCache, Clock clock, Duration defaultRefreshTtl, boolean metricsLoggingEnabled) {
        this.modelCache = modelCache;
        this.clock = clock;
        this.defaultRefreshTtl = defaultRefreshTtl;
        this.metricsLoggingEnabled = metricsLoggingEnabled;
    }

    public static ProviderModelCacheService createDefault() {
        return new ProviderModelCacheService(new ProviderModelCache(StoragePaths.defaultPaths()));
    }

    public void primeFromDisk(List<String> providerNames) {
        if (ObjectUtils.isEmpty(providerNames)) {
            return;
        }

        providerNames.stream()
                .filter(Objects::nonNull)
                .distinct()
                .forEach(providerName -> {
                    metrics.primeRequests.incrementAndGet();
                    getOrLoadEntry(providerName);
                });
    }

    public List<String> getModels(String providerName) {
        return StringUtils.isBlank(providerName)
            ? emptyList()
            : getOrLoadEntry(providerName).models();
    }

    public boolean isInvalidated(String providerName) {
        return StringUtils.isNotBlank(providerName)
                && (invalidatedProviders.contains(providerName) || persistedInvalidationLoaded(providerName));
    }

    public boolean shouldRefresh(String providerName) {
        return shouldRefresh(providerName, defaultRefreshTtl);
    }

    public boolean shouldRefresh(String providerName, Duration ttl) {
        if (StringUtils.isBlank(providerName)) {
            return false;
        }

        CacheEntry entry = getOrLoadEntry(providerName);
        if (entry.models().isEmpty() || invalidatedProviders.contains(providerName)) {
            metrics.refreshAllowed.incrementAndGet();
            return true;
        }

        Duration effectiveTtl = ttl == null ? defaultRefreshTtl : ttl;
        Instant expiresAt = entry.fetchedAt().plus(effectiveTtl);
        boolean shouldRefresh = expiresAt.isBefore(Instant.now(clock));
        if (shouldRefresh) {
            metrics.refreshAllowed.incrementAndGet();
        } else {
            metrics.refreshBlocked.incrementAndGet();
        }
        return shouldRefresh;
    }

    public synchronized Optional<RefreshAttempt> tryBeginRefresh(String providerName) {
        if (StringUtils.isBlank(providerName)) {
            return Optional.empty();
        }

        CacheEntry entry = getOrLoadEntry(providerName);
        if (entry.refreshInFlight()) {
            metrics.refreshInFlightRejected.incrementAndGet();
            return Optional.empty();
        }

        cacheByProvider.put(providerName, entry.withRefreshInFlight(true));
        metrics.refreshInFlightAccepted.incrementAndGet();
        return Optional.of(new RefreshAttempt(providerName, entry.generation()));
    }

    public synchronized void clearRefreshInFlight(RefreshAttempt attempt) {
        if (attempt == null || StringUtils.isBlank(attempt.providerName())) {
            return;
        }

        cacheByProvider.computeIfPresent(attempt.providerName(), (name, entry) ->
                entry.generation() == attempt.generation() ? entry.withRefreshInFlight(false) : entry);
    }

    public synchronized void invalidate(String providerName) {
        if (StringUtils.isBlank(providerName)) {
            return;
        }

        CacheEntry entry = getOrLoadEntry(providerName);
        CacheEntry invalidated = new CacheEntry(entry.models(), Instant.EPOCH, false, entry.generation() + 1, true);
        invalidatedProviders.add(providerName);
        cacheByProvider.put(providerName, invalidated);
        if (!modelCache.writeCache(providerName, Instant.EPOCH, emptyList())) {
            log.warn("Model cache invalidation for provider {} could not be persisted; stale disk cache may survive restart.", providerName);
        }
    }

    public synchronized void update(String providerName, List<String> models) {
        if (StringUtils.isBlank(providerName)) {
            return;
        }

        CacheEntry entry = getOrLoadEntry(providerName);
        updateFresh(providerName, models, entry.generation());
    }

    public synchronized boolean update(RefreshAttempt attempt, List<String> models) {
        if (attempt == null || StringUtils.isBlank(attempt.providerName())) {
            return false;
        }

        CacheEntry entry = getOrLoadEntry(attempt.providerName());
        if (entry.generation() != attempt.generation()) {
            return false;
        }

        updateFresh(attempt.providerName(), models, attempt.generation());
        return true;
    }

    private void updateFresh(String providerName, List<String> models, long generation) {
        List<String> sanitizedModels = sanitizeModels(providerName, models);
        Instant fetchedAt = Instant.now(clock);
        cacheByProvider.put(providerName, new CacheEntry(sanitizedModels, fetchedAt, false, generation, false));
        modelCache.writeCache(providerName, fetchedAt, sanitizedModels);
        invalidatedProviders.remove(providerName);
        metrics.updates.incrementAndGet();

        if (metricsLoggingEnabled) {
            log.info("[model-cache] updated provider={} size={} metrics={}",
                    providerName, sanitizedModels.size(), metricsSnapshot());
        }
    }

    public MetricsSnapshot metricsSnapshot() {
        return new MetricsSnapshot(
            metrics.memoryHits.get(),
            metrics.memoryMisses.get(),
            metrics.diskReadsWithData.get(),
            metrics.diskReadsEmpty.get(),
            metrics.refreshAllowed.get(),
            metrics.refreshBlocked.get(),
            metrics.refreshInFlightAccepted.get(),
            metrics.refreshInFlightRejected.get(),
            metrics.updates.get(),
            metrics.primeRequests.get());
    }

    public void logMetricsSnapshot(String reason) {
        if (!metricsLoggingEnabled) {
            return;
        }

        log.info("[model-cache] snapshot({}) {}", reason, metricsSnapshot());
    }

    private CacheEntry getOrLoadEntry(String providerName) {
        CacheEntry cached = cacheByProvider.get(providerName);
        if (cached != null) {
            metrics.memoryHits.incrementAndGet();
            return cached;
        }

        metrics.memoryMisses.incrementAndGet();
        CacheEntry loaded = loadEntry(providerName);
        CacheEntry existing = cacheByProvider.putIfAbsent(providerName, loaded);
        if (existing != null) {
            metrics.memoryHits.incrementAndGet();
            return existing;
        }

        return loaded;
    }

    private boolean persistedInvalidationLoaded(String providerName) {
        CacheEntry entry = getOrLoadEntry(providerName);
        return entry.invalidated();
    }

    private CacheEntry loadEntry(String providerName) {
        return modelCache.readCacheEntry(providerName)
                .map(snapshot -> {
                    metrics.diskReadsWithData.incrementAndGet();
                    boolean invalidated = snapshot.fetchedAt().equals(Instant.EPOCH) && snapshot.models().isEmpty();
                    if (invalidated) {
                        invalidatedProviders.add(providerName);
                    }
                    return new CacheEntry(
                        List.copyOf(sanitizeModels(providerName, snapshot.models())),
                        snapshot.fetchedAt(),
                        false,
                        0L,
                        invalidated
                    );
                })
                .orElseGet(() -> {
                    metrics.diskReadsEmpty.incrementAndGet();
                    return new CacheEntry(emptyList(), Instant.EPOCH, false, 0L, false);
                });
    }

    private static List<String> sanitizeModels(String providerName, List<String> models) {
        if ("OpenAI Codex".equals(providerName)) {
            return List.copyOf(CodexLocalModelCache.mergeIfCodexProvider(providerName, models));
        }
        return ObjectUtils.isEmpty(models)
            ? emptyList()
            : List.copyOf(ModelOrdering.sanitizeAndSortByProvider(providerName, models));
    }

    public record MetricsSnapshot(
        long memoryHits,
        long memoryMisses,
        long diskReadsWithData,
        long diskReadsEmpty,
        long refreshAllowed,
        long refreshBlocked,
        long refreshInFlightAccepted,
        long refreshInFlightRejected,
        long updates,
        long primeRequests
    ) {
    }

    public record RefreshAttempt(String providerName, long generation) {
    }

    private record CacheEntry(List<String> models, Instant fetchedAt, boolean refreshInFlight, long generation, boolean invalidated) {
        private CacheEntry withRefreshInFlight(boolean value) {
            return new CacheEntry(models, fetchedAt, value, generation, invalidated);
        }
    }

    private static final class CacheMetrics {
        private final AtomicLong memoryHits = new AtomicLong();
        private final AtomicLong memoryMisses = new AtomicLong();
        private final AtomicLong diskReadsWithData = new AtomicLong();
        private final AtomicLong diskReadsEmpty = new AtomicLong();
        private final AtomicLong refreshAllowed = new AtomicLong();
        private final AtomicLong refreshBlocked = new AtomicLong();
        private final AtomicLong refreshInFlightAccepted = new AtomicLong();
        private final AtomicLong refreshInFlightRejected = new AtomicLong();
        private final AtomicLong updates = new AtomicLong();
        private final AtomicLong primeRequests = new AtomicLong();
    }
}
