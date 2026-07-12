package com.github.drafael.chat4j.persistence.model;

import com.github.drafael.chat4j.persistence.StoragePaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderModelCacheServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Priming from disk loads cached provider models into memory")
    void primeFromDisk_whenProviderHasCachedModels_loadsModelsIntoMemory() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("gpt-4.1", "gpt-4o"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));

        subject.primeFromDisk(List.of("OpenAI"));

        assertThat(subject.getModels("OpenAI")).containsExactly("gpt-4.1", "gpt-4o");
    }

    @Test
    @DisplayName("Stale entries are refreshed when fetched time exceeds TTL")
    void shouldRefresh_whenEntryIsOlderThanTtl_returnsTrue() {
        var cache = new InMemoryModelCache();
        cache.put("Anthropic", Instant.parse("2026-04-10T00:00:00Z"), List.of("claude-sonnet-4"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T13:00:00Z"), Duration.ofHours(12));
        subject.primeFromDisk(List.of("Anthropic"));

        boolean refreshRequired = subject.shouldRefresh("Anthropic", Duration.ofHours(6));

        assertThat(refreshRequired).isTrue();
    }

    @Test
    @DisplayName("Invalidating a fresh disk cache keeps models visible but forces refresh")
    void shouldRefresh_whenProviderInvalidatedAfterFreshDiskLoad_returnsTrueAndKeepsModels() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("gpt-4.1", "gpt-4o"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        assertThat(subject.getModels("OpenAI")).containsExactly("gpt-4.1", "gpt-4o");
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isFalse();

        subject.invalidate("OpenAI");

        assertThat(subject.getModels("OpenAI")).containsExactly("gpt-4.1", "gpt-4o");
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isTrue();
        assertThat(cache.lastWriteProvider).isEqualTo("OpenAI");
        assertThat(cache.lastWriteTimestamp).isEqualTo(Instant.EPOCH);

        var restarted = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        assertThat(restarted.isInvalidated("OpenAI")).isTrue();
        assertThat(restarted.getModels("OpenAI")).isEmpty();
        assertThat(restarted.shouldRefresh("OpenAI", Duration.ofHours(12))).isTrue();

        subject.update("OpenAI", List.of("gpt-4.2"));

        assertThat(subject.getModels("OpenAI")).containsExactly("gpt-4.2");
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isFalse();
    }

    @Test
    @DisplayName("In-flight refreshes started before invalidation cannot make old models fresh")
    void update_whenRefreshStartedBeforeInvalidation_finishesAsStaleAndAllowsNewRefresh() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("old-visible-model"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        ProviderModelCacheService.RefreshAttempt oldRefresh = subject.tryBeginRefresh("OpenAI").orElseThrow();

        subject.invalidate("OpenAI");

        assertThat(subject.isInvalidated("OpenAI")).isTrue();
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isTrue();
        assertThat(subject.update(oldRefresh, List.of("old-account-model"))).isFalse();
        assertThat(subject.isInvalidated("OpenAI")).isTrue();
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isTrue();
        assertThat(subject.getModels("OpenAI")).containsExactly("old-visible-model");
        assertThat(cache.entries.get("OpenAI").models()).isEmpty();
        assertThat(cache.entries.get("OpenAI").fetchedAt()).isEqualTo(Instant.EPOCH);

        ProviderModelCacheService.RefreshAttempt newRefresh = subject.tryBeginRefresh("OpenAI").orElseThrow();

        assertThat(subject.update(newRefresh, List.of("new-account-model"))).isTrue();
        assertThat(subject.isInvalidated("OpenAI")).isFalse();
        assertThat(subject.getModels("OpenAI")).containsExactly("new-account-model");
        assertThat(subject.shouldRefresh("OpenAI", Duration.ofHours(12))).isFalse();
    }

    @Test
    @DisplayName("Empty model cache snapshots round-trip as empty cache entries")
    void readCacheEntry_whenCacheContainsOnlyTimestamp_returnsEmptyModels() {
        var cache = new ProviderModelCache(StoragePaths.ofConfigHome(tempDir));

        cache.writeCache("OpenAI", Instant.EPOCH, emptyList());

        assertThat(cache.readCacheEntry("OpenAI"))
                .hasValueSatisfying(snapshot -> {
                    assertThat(snapshot.fetchedAt()).isEqualTo(Instant.EPOCH);
                    assertThat(snapshot.models()).isEmpty();
                });
    }

    @Test
    @DisplayName("Only one refresh for the same provider can be in-flight")
    void tryBeginRefresh_whenCalledTwice_onlyFirstCallSucceeds() {
        var cache = new InMemoryModelCache();
        cache.put("Mistral", Instant.parse("2026-04-10T10:00:00Z"), List.of("mistral-large-latest"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        var first = subject.tryBeginRefresh("Mistral");
        var second = subject.tryBeginRefresh("Mistral");

        assertThat(first).isPresent();
        assertThat(second).isEmpty();

        subject.clearRefreshInFlight(first.orElseThrow());

        assertThat(subject.tryBeginRefresh("Mistral")).isPresent();
    }

    @Test
    @DisplayName("OpenAI Codex cached models are merged with local Codex CLI cache")
    void getModels_whenProviderIsOpenAiCodex_mergesLocalCodexCache() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        Path tempHome = Files.createTempDirectory("chat4j-codex-model-cache");
        System.setProperty("user.home", tempHome.toString());

        try {
            Path codexDir = tempHome.resolve(".codex");
            Files.createDirectories(codexDir);
            Files.writeString(codexDir.resolve("models_cache.json"), """
                    {
                      "models": [
                        {"slug": "gpt-5.5"},
                        {"slug": "gpt-5.4"}
                      ]
                    }
                    """);

            var cache = new InMemoryModelCache();
            cache.put("OpenAI Codex", Instant.parse("2026-04-10T10:00:00Z"), List.of("gpt-5.3-codex"));
            var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T11:00:00Z"), Duration.ofHours(12));

            assertThat(subject.getModels("OpenAI Codex"))
                    .contains("gpt-5.5", "gpt-5.4", "gpt-5.3-codex");
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @DisplayName("Updating provider models persists sanitized models to storage")
    void update_whenModelsAreProvided_writesSanitizedSnapshotToCacheStore() {
        var cache = new InMemoryModelCache();
        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T12:00:00Z"), Duration.ofHours(12));

        subject.update("OpenRouter", List.of("", "meta-llama-3", "meta-llama-3"));

        assertThat(subject.getModels("OpenRouter")).containsExactly("meta-llama-3");
        assertThat(cache.lastWriteProvider).isEqualTo("OpenRouter");
        assertThat(cache.lastWriteTimestamp).isEqualTo(Instant.parse("2026-04-10T12:00:00Z"));
    }

    @Test
    @DisplayName("Metrics snapshot exposes memory and disk cache activity")
    void metricsSnapshot_whenCacheIsUsed_reportsHitsMissesAndUpdates() {
        var cache = new InMemoryModelCache();
        cache.put("OpenAI", Instant.parse("2026-04-10T10:00:00Z"), List.of("gpt-4.1"));

        var subject = new ProviderModelCacheService(cache, fixedClock("2026-04-10T10:30:00Z"), Duration.ofHours(12));

        subject.primeFromDisk(List.of("OpenAI"));
        subject.getModels("OpenAI");
        subject.update("OpenAI", List.of("gpt-4.1", "gpt-4o"));

        ProviderModelCacheService.MetricsSnapshot metrics = subject.metricsSnapshot();
        assertThat(metrics.primeRequests()).isEqualTo(1L);
        assertThat(metrics.memoryHits()).isGreaterThanOrEqualTo(1L);
        assertThat(metrics.memoryMisses()).isGreaterThanOrEqualTo(1L);
        assertThat(metrics.diskReadsWithData()).isGreaterThanOrEqualTo(1L);
        assertThat(metrics.updates()).isEqualTo(1L);
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static class InMemoryModelCache extends ProviderModelCache {

        private final Map<String, CacheSnapshot> entries = new HashMap<>();
        private String lastWriteProvider;
        private Instant lastWriteTimestamp;

        private InMemoryModelCache() {
            super(StoragePaths.defaultPaths());
        }

        @Override
        public Optional<CacheSnapshot> readCacheEntry(String providerName) {
            return Optional.ofNullable(entries.get(providerName));
        }

        @Override
        public boolean writeCache(String providerName, Instant fetchedAt, List<String> models) {
            List<String> storedModels = models == null ? emptyList() : List.copyOf(models);
            entries.put(providerName, new CacheSnapshot(fetchedAt, storedModels));
            lastWriteProvider = providerName;
            lastWriteTimestamp = fetchedAt;
            return true;
        }

        private void put(String providerName, Instant fetchedAt, List<String> models) {
            entries.put(providerName, new CacheSnapshot(fetchedAt, List.copyOf(models)));
        }
    }
}
