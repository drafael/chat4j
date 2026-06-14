package com.github.drafael.chat4j.persistence.model;

import com.github.drafael.chat4j.persistence.db.StoragePaths;
import com.github.drafael.chat4j.provider.support.CodexLocalModelCache;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

    public boolean shouldRefresh(String providerName) {
        return shouldRefresh(providerName, defaultRefreshTtl);
    }

    public boolean shouldRefresh(String providerName, Duration ttl) {
        if (StringUtils.isBlank(providerName)) {
            return false;
        }

        CacheEntry entry = getOrLoadEntry(providerName);
        if (entry.models().isEmpty()) {
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

    public boolean tryMarkRefreshInFlight(String providerName) {
        if (StringUtils.isBlank(providerName)) {
            return false;
        }

        AtomicBoolean marked = new AtomicBoolean(false);
        cacheByProvider.compute(
            providerName,
            (name, existing) -> {
            CacheEntry entry = existing == null ? loadEntry(name) : existing;
            if (entry.refreshInFlight()) {
                return entry;
            }

            marked.set(true);
            return entry.withRefreshInFlight(true);
        });

        if (marked.get()) {
            metrics.refreshInFlightAccepted.incrementAndGet();
        } else {
            metrics.refreshInFlightRejected.incrementAndGet();
        }

        return marked.get();
    }

    public void clearRefreshInFlight(String providerName) {
        if (StringUtils.isBlank(providerName)) {
            return;
        }

        cacheByProvider.computeIfPresent(providerName, (name, entry) -> entry.withRefreshInFlight(false));
    }

    public void update(String providerName, List<String> models) {
        if (StringUtils.isBlank(providerName)) {
            return;
        }

        List<String> sanitizedModels = sanitizeModels(providerName, models);
        Instant fetchedAt = Instant.now(clock);
        cacheByProvider.put(providerName, new CacheEntry(sanitizedModels, fetchedAt, false));
        modelCache.writeCache(providerName, fetchedAt, sanitizedModels);
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

    private CacheEntry loadEntry(String providerName) {
        return modelCache.readCacheEntry(providerName)
                .map(snapshot -> {
                    metrics.diskReadsWithData.incrementAndGet();
                    return new CacheEntry(
                        List.copyOf(sanitizeModels(providerName, snapshot.models())),
                        snapshot.fetchedAt(),
                        false
                    );
                })
                .orElseGet(() -> {
                    metrics.diskReadsEmpty.incrementAndGet();
                    return new CacheEntry(emptyList(), Instant.EPOCH, false);
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

    private record CacheEntry(List<String> models, Instant fetchedAt, boolean refreshInFlight) {
        private CacheEntry withRefreshInFlight(boolean value) {
            return new CacheEntry(models, fetchedAt, value);
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

        private void reset() {
            memoryHits.set(0L);
            memoryMisses.set(0L);
            diskReadsWithData.set(0L);
            diskReadsEmpty.set(0L);
            refreshAllowed.set(0L);
            refreshBlocked.set(0L);
            refreshInFlightAccepted.set(0L);
            refreshInFlightRejected.set(0L);
            updates.set(0L);
            primeRequests.set(0L);
        }
    }
}
