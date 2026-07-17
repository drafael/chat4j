package com.github.drafael.chat4j.persistence.model;

import com.github.drafael.chat4j.provider.support.BaseUrlNormalizer;
import com.github.drafael.chat4j.provider.support.CodexLocalModelCache;
import com.github.drafael.chat4j.provider.support.ModelOrdering;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

@Slf4j
public class ProviderModelCacheService {
    private static final Duration DEFAULT_REFRESH_TTL = Duration.ofHours(12);
    private static final Duration EMPTY_CATALOG_REFRESH_TTL = Duration.ofMinutes(5);
    private static final Duration FAILED_REFRESH_RETRY_DELAY = Duration.ofMinutes(5);
    private static final String METRICS_LOG_PROPERTY = "chat4j.modelCache.metrics";
    private static final String CODEX_PROVIDER_NAME = "OpenAI Codex";

    private final ProviderModelCache modelCache;
    private final Clock clock;
    private final Duration defaultRefreshTtl;
    private final boolean metricsLoggingEnabled;
    private final Supplier<CodexLocalModelCache.Snapshot> codexLocalModelsSupplier;
    private final ConcurrentHashMap<String, CacheEntry> cacheByProvider = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> refreshRetryAfterByProvider = new ConcurrentHashMap<>();
    private final CacheMetrics metrics = new CacheMetrics();
    private final AtomicLong codexLocalRefreshCounter = new AtomicLong();
    private long codexLocalPublishedRefreshId;
    private long refreshAttemptCounter;
    private long scopeVersionCounter;
    private volatile CodexLocalModelCache.Snapshot codexLocalModels;

    public ProviderModelCacheService(ProviderModelCache modelCache) {
        this(modelCache, Clock.systemUTC(), DEFAULT_REFRESH_TTL, Boolean.getBoolean(METRICS_LOG_PROPERTY), CodexLocalModelCache::readSnapshot);
    }

    ProviderModelCacheService(ProviderModelCache modelCache, Clock clock, Duration defaultRefreshTtl) {
        this(modelCache, clock, defaultRefreshTtl, false, CodexLocalModelCache::readSnapshot);
    }

    ProviderModelCacheService(
            ProviderModelCache modelCache,
            Clock clock,
            Duration defaultRefreshTtl,
            boolean metricsLoggingEnabled,
            Supplier<CodexLocalModelCache.Snapshot> codexLocalModelsSupplier
    ) {
        this.modelCache = modelCache;
        this.clock = clock;
        this.defaultRefreshTtl = defaultRefreshTtl;
        this.metricsLoggingEnabled = metricsLoggingEnabled;
        this.codexLocalModelsSupplier = codexLocalModelsSupplier;
        this.codexLocalModels = CodexLocalModelCache.builtinSnapshot();
    }

    public void primeFromDisk(List<String> providerNames) {
        if (ObjectUtils.isEmpty(providerNames)) {
            return;
        }

        providerNames.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .forEach(providerName -> {
                    metrics.primeRequests.incrementAndGet();
                    getOrLoadEntry(providerName);
                });
    }

    public List<String> getModels(String providerName) {
        if (StringUtils.isBlank(providerName)) {
            return emptyList();
        }

        List<String> models = getOrLoadEntry(providerName).models();
        return CODEX_PROVIDER_NAME.equals(providerName)
                ? CodexLocalModelCache.merge(models, codexLocalModels)
                : models;
    }

    public List<String> modelsWithLocalOverlay(String providerName, List<String> models) {
        return CODEX_PROVIDER_NAME.equals(providerName)
                ? CodexLocalModelCache.merge(models, codexLocalModels)
                : sanitizeModels(providerName, models);
    }

    public List<String> refreshCodexLocalModels() {
        long refreshId = codexLocalRefreshCounter.incrementAndGet();
        CodexLocalModelCache.Snapshot refreshedModels = codexLocalModelsSupplier.get();
        if (!refreshedModels.loadedSuccessfully()) {
            return getModels(CODEX_PROVIDER_NAME);
        }
        synchronized (this) {
            if (refreshId > codexLocalPublishedRefreshId) {
                codexLocalModels = refreshedModels;
                codexLocalPublishedRefreshId = refreshId;
            }
        }
        return getModels(CODEX_PROVIDER_NAME);
    }

    public synchronized long nextScopeVersion() {
        return ++scopeVersionCounter;
    }

    public synchronized void cancelScopeVersion(long scopeVersion) {
        if (scopeVersionCounter == scopeVersion) {
            scopeVersionCounter++;
        }
    }

    public synchronized boolean isScopeVersionCurrent(long scopeVersion) {
        return scopeVersionCounter == scopeVersion;
    }

    public synchronized boolean runIfScopeVersionCurrent(long scopeVersion, Runnable action) {
        if (scopeVersionCounter != scopeVersion) {
            return false;
        }
        action.run();
        return true;
    }

    public synchronized void synchronizeScope(String providerName, String scope, long scopeVersion) {
        if (StringUtils.isBlank(providerName)) {
            return;
        }

        scopeVersionCounter = Math.max(scopeVersionCounter, scopeVersion);
        if (scopeVersion < scopeVersionCounter) {
            return;
        }

        String normalizedScope = normalizeScope(scope);
        CacheEntry entry = getOrLoadEntry(providerName);
        if (Objects.equals(entry.scope(), normalizedScope)) {
            return;
        }
        if (entry.scope() == null
                && entry.models().isEmpty()
                && Instant.EPOCH.equals(entry.fetchedAt())
                && !entry.invalidated()) {
            cacheByProvider.put(providerName, new CacheEntry(
                    emptyList(),
                    Instant.EPOCH,
                    0L,
                    false,
                    normalizedScope
            ));
            return;
        }

        CacheEntry invalidated = new CacheEntry(emptyList(), Instant.EPOCH, 0L, true, normalizedScope);
        refreshRetryAfterByProvider.remove(providerName);
        cacheByProvider.put(providerName, invalidated);
        if (!modelCache.writeCache(providerName, Instant.EPOCH, normalizedScope, emptyList())) {
            removeStaleCache(providerName, "scope change");
        }
    }

    public boolean isInvalidated(String providerName) {
        return StringUtils.isNotBlank(providerName) && getOrLoadEntry(providerName).invalidated();
    }

    public boolean shouldRefresh(String providerName) {
        return shouldRefresh(providerName, defaultRefreshTtl);
    }

    public boolean shouldRefresh(String providerName, Duration ttl) {
        if (StringUtils.isBlank(providerName)) {
            return false;
        }

        CacheEntry entry = getOrLoadEntry(providerName);
        Instant now = Instant.now(clock);
        Instant refreshRetryAfter = refreshRetryAfterByProvider.get(providerName);
        if (refreshRetryAfter != null && now.isBefore(refreshRetryAfter)) {
            metrics.refreshBlocked.incrementAndGet();
            return false;
        }
        refreshRetryAfterByProvider.remove(providerName, refreshRetryAfter);

        if (entry.invalidated()
                || (entry.models().isEmpty() && Instant.EPOCH.equals(entry.fetchedAt()))) {
            metrics.refreshAllowed.incrementAndGet();
            return true;
        }

        Duration effectiveTtl = ttl == null ? defaultRefreshTtl : ttl;
        Duration refreshTtl = entry.models().isEmpty() && effectiveTtl.compareTo(EMPTY_CATALOG_REFRESH_TTL) > 0
                ? EMPTY_CATALOG_REFRESH_TTL
                : effectiveTtl;
        boolean shouldRefresh = entry.fetchedAt().isAfter(now)
                || !now.isBefore(entry.fetchedAt().plus(refreshTtl));
        if (shouldRefresh) {
            metrics.refreshAllowed.incrementAndGet();
        } else {
            metrics.refreshBlocked.incrementAndGet();
        }
        return shouldRefresh;
    }

    public synchronized Optional<RefreshAttempt> tryBeginRefreshIfNeeded(
            String providerName,
            String scope,
            Duration ttl
    ) {
        return shouldRefresh(providerName, ttl)
                ? tryBeginRefresh(providerName, scope)
                : Optional.empty();
    }

    synchronized Optional<RefreshAttempt> tryBeginRefresh(String providerName, String scope) {
        if (StringUtils.isBlank(providerName)) {
            return Optional.empty();
        }

        CacheEntry entry = getOrLoadEntry(providerName);
        String normalizedScope = normalizeScope(scope);
        if (!Objects.equals(normalizeScope(entry.scope()), normalizedScope)) {
            return Optional.empty();
        }
        if (entry.refreshInFlight()) {
            metrics.refreshInFlightRejected.incrementAndGet();
            return Optional.empty();
        }

        long attemptId = ++refreshAttemptCounter;
        cacheByProvider.put(providerName, entry.withRefreshAttempt(attemptId));
        metrics.refreshInFlightAccepted.incrementAndGet();
        return Optional.of(new RefreshAttempt(providerName, attemptId));
    }

    public synchronized void clearRefreshInFlight(RefreshAttempt attempt) {
        if (attempt == null || StringUtils.isBlank(attempt.providerName())) {
            return;
        }

        cacheByProvider.computeIfPresent(attempt.providerName(), (name, entry) ->
                entry.refreshAttemptId() == attempt.attemptId() ? entry.withRefreshAttempt(0L) : entry);
    }

    public synchronized void invalidate(String providerName) {
        if (StringUtils.isBlank(providerName)) {
            return;
        }

        CacheEntry entry = getOrLoadEntry(providerName);
        CacheEntry invalidated = new CacheEntry(entry.models(), Instant.EPOCH, 0L, true, entry.scope());
        refreshRetryAfterByProvider.remove(providerName);
        cacheByProvider.put(providerName, invalidated);
        if (!modelCache.writeCache(providerName, Instant.EPOCH, entry.scope(), emptyList())) {
            removeStaleCache(providerName, "invalidation");
        }
    }

    public synchronized boolean update(RefreshAttempt attempt, List<String> models) {
        if (attempt == null || StringUtils.isBlank(attempt.providerName())) {
            return false;
        }

        CacheEntry entry = getOrLoadEntry(attempt.providerName());
        if (entry.refreshAttemptId() != attempt.attemptId()) {
            return false;
        }
        if (Thread.currentThread().isInterrupted()) {
            deferRefresh(attempt.providerName(), entry);
            return false;
        }
        List<String> sanitizedModels = sanitizeModels(attempt.providerName(), models);
        if (sanitizedModels.isEmpty() && !entry.models().isEmpty()) {
            deferRefresh(attempt.providerName(), entry);
            return false;
        }

        updateSanitized(attempt.providerName(), sanitizedModels);
        return true;
    }

    public synchronized void recordRefreshFailure(RefreshAttempt attempt) {
        if (attempt == null || StringUtils.isBlank(attempt.providerName())) {
            return;
        }

        CacheEntry entry = getOrLoadEntry(attempt.providerName());
        if (entry.refreshAttemptId() == attempt.attemptId()) {
            deferRefresh(attempt.providerName(), entry);
        }
    }

    private void deferRefresh(String providerName, CacheEntry entry) {
        cacheByProvider.put(providerName, entry.withRefreshAttempt(0L));
        refreshRetryAfterByProvider.put(providerName, Instant.now(clock).plus(FAILED_REFRESH_RETRY_DELAY));
    }

    private void updateSanitized(String providerName, List<String> sanitizedModels) {
        Instant fetchedAt = Instant.now(clock);
        CacheEntry current = getOrLoadEntry(providerName);
        cacheByProvider.put(providerName, new CacheEntry(sanitizedModels, fetchedAt, 0L, false, current.scope()));
        if (!modelCache.writeCache(providerName, fetchedAt, current.scope(), sanitizedModels)) {
            removeStaleCache(providerName, "refresh");
        }
        refreshRetryAfterByProvider.remove(providerName);
        metrics.updates.incrementAndGet();

        if (metricsLoggingEnabled) {
            log.info("[model-cache] updated provider={} size={} metrics={}",
                    providerName, sanitizedModels.size(), metricsSnapshot());
        }
    }

    private void removeStaleCache(String providerName, String operation) {
        if (!modelCache.deleteCache(providerName)) {
            log.warn("Model cache {} for provider {} could not be persisted or removed; stale disk data may survive restart.",
                    operation, providerName);
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
                    boolean invalidated = snapshot.fetchedAt().equals(Instant.EPOCH) && snapshot.models().isEmpty();
                    return new CacheEntry(
                        List.copyOf(sanitizeModels(providerName, snapshot.models())),
                        snapshot.fetchedAt(),
                        0L,
                        invalidated,
                        snapshot.scope() == null ? null : normalizeScope(snapshot.scope())
                    );
                })
                .orElseGet(() -> {
                    metrics.diskReadsEmpty.incrementAndGet();
                    return new CacheEntry(emptyList(), Instant.EPOCH, 0L, false, null);
                });
    }

    private static String normalizeScope(String scope) {
        return BaseUrlNormalizer.normalize(scope, "");
    }

    private static List<String> sanitizeModels(String providerName, List<String> models) {
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

    public record RefreshAttempt(String providerName, long attemptId) {
    }

    private record CacheEntry(
            List<String> models,
            Instant fetchedAt,
            long refreshAttemptId,
            boolean invalidated,
            String scope
    ) {
        private boolean refreshInFlight() {
            return refreshAttemptId != 0L;
        }

        private CacheEntry withRefreshAttempt(long attemptId) {
            return new CacheEntry(models, fetchedAt, attemptId, invalidated, scope);
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
