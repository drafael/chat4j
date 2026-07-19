package com.github.drafael.chat4j.chat.model;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.persistence.model.ProviderModelCache;
import com.github.drafael.chat4j.persistence.model.ProviderModelCacheService;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry.ProviderDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSelectorPopupRefreshPolicyTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Perplexity popup models ignore stale cache and use static Sonar list")
    void initialModels_whenProviderIsPerplexity_returnsStaticSonarModels() {
        List<String> models = ModelSelectorPopup.initialModels(
                "Perplexity",
                List.of("sonar-pro", "sonar"),
                List.of("sonar", "sonar-pro"),
                false
        );

        assertThat(models).containsExactly(
                "sonar",
                "sonar-pro",
                "sonar-reasoning-pro",
                "sonar-deep-research"
        );
    }

    @Test
    @DisplayName("Invalidated popup models use seeds instead of cached account models")
    void initialModels_whenProviderIsInvalidated_returnsSeedModels() {
        List<String> models = ModelSelectorPopup.initialModels(
                "OpenAI",
                List.of("old-account-model"),
                List.of("seed-b", "seed-a"),
                true
        );

        assertThat(models).containsExactly("seed-b", "seed-a");
    }

    @Test
    @DisplayName("A scope change during popup model lookup rejects models from the previous endpoint")
    void initialModels_whenScopeChangesDuringLookup_returnsSeedModels() throws Exception {
        var cacheService = new BlockingModelCacheService(tempDir.resolve("scope-race"));
        var provider = new ProviderDef(
                "OpenAI",
                "OPENAI_API_KEY",
                "https://old.example.com/v1",
                List.of("seed-model"),
                ProviderCapabilities.chatAndModels(),
                model -> null,
                List::of
        );
        cacheService.synchronizeScope(provider.name(), provider.baseUrl(), cacheService.nextScopeVersion());
        ProviderModelCacheService.RefreshAttempt refreshAttempt = cacheService.tryBeginRefreshIfNeeded(
                provider.name(),
                provider.baseUrl(),
                Duration.ZERO
        ).orElseThrow();
        assertThat(cacheService.update(refreshAttempt, List.of("old-endpoint-model"))).isTrue();

        var models = new AtomicReference<List<String>>();
        var workerError = new AtomicReference<Throwable>();
        cacheService.blockNextLookup();
        Thread worker = Thread.startVirtualThread(() -> {
            try {
                models.set(ModelSelectorPopup.initialModels(provider, cacheService));
            } catch (Throwable t) {
                workerError.set(t);
            }
        });
        try {
            assertThat(cacheService.awaitLookupStarted()).isTrue();
            cacheService.synchronizeScope(
                    provider.name(),
                    "https://new.example.com/v1",
                    cacheService.nextScopeVersion()
            );
            cacheService.releaseLookup();
            worker.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(worker.isAlive()).isFalse();
            assertThat(workerError.get()).isNull();
            assertThat(models.get()).containsExactly("seed-model");
        } finally {
            cacheService.releaseLookup();
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(2));
        }
    }

    @Test
    @DisplayName("GitHub Copilot uses default provider refresh cadence")
    void refreshTtl_whenProviderIsGitHubCopilot_returnsDefaultRefreshTtl() {
        Duration ttl = ModelSelectorPopup.refreshTtl("GitHub Copilot");

        assertThat(ttl).isEqualTo(Duration.ofHours(12));
    }

    @Test
    @DisplayName("Local providers refresh every five minutes")
    void refreshTtl_whenProviderIsLocal_returnsFiveMinuteRefreshTtl() {
        Duration ttl = ModelSelectorPopup.refreshTtl("Ollama");

        assertThat(ttl).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("Local providers without a base URL remain unavailable")
    void isProviderSelectable_whenLocalBaseUrlIsBlank_returnsFalse() {
        assertThat(ModelSelectorPopup.isProviderSelectable("Ollama", " ")).isFalse();
    }

    @Test
    @DisplayName("Cloud provider selection does not depend on local health state")
    void isProviderSelectable_whenProviderIsCloud_returnsTrue() {
        assertThat(ModelSelectorPopup.isProviderSelectable("OpenAI", null)).isTrue();
    }

    @Test
    @DisplayName("Cloud providers without special policy keep default refresh cadence")
    void refreshTtl_whenProviderHasNoSpecialPolicy_returnsDefaultRefreshTtl() {
        Duration ttl = ModelSelectorPopup.refreshTtl("OpenAI");

        assertThat(ttl).isEqualTo(Duration.ofHours(12));
    }

    private static final class BlockingModelCacheService extends ProviderModelCacheService {
        private final AtomicBoolean blockNextLookup = new AtomicBoolean();
        private final CountDownLatch lookupStarted = new CountDownLatch(1);
        private final CountDownLatch lookupReleased = new CountDownLatch(1);

        private BlockingModelCacheService(Path configHome) {
            super(new ProviderModelCache(StoragePaths.ofConfigHome(configHome)));
        }

        @Override
        public Optional<List<String>> findUsableModels(String providerName, String scope) {
            if (blockNextLookup.compareAndSet(true, false)) {
                lookupStarted.countDown();
                try {
                    if (!lookupReleased.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release model lookup");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Model lookup was interrupted", e);
                }
            }
            return super.findUsableModels(providerName, scope);
        }

        private void blockNextLookup() {
            blockNextLookup.set(true);
        }

        private boolean awaitLookupStarted() throws InterruptedException {
            return lookupStarted.await(2, TimeUnit.SECONDS);
        }

        private void releaseLookup() {
            lookupReleased.countDown();
        }
    }
}
