package com.github.drafael.chat4j.provider.registry;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.capability.models.ModelCatalogClient;
import com.github.drafael.chat4j.provider.core.ProviderModule;
import com.github.drafael.chat4j.provider.support.BaseUrlNormalizer;
import com.github.drafael.chat4j.provider.support.CodexAuthResolver;
import com.github.drafael.chat4j.provider.support.CopilotAuthResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderRuntimePolicyTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Copilot auth providers are unavailable when resolver reports unauthorized")
    void hasRequiredCredentials_whenCopilotAuthResolverIsUnauthorized_returnsFalse() {
        CopilotAuthResolver resolver = new CopilotAuthResolver(
                tempDir.resolve("copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthResolver.CopilotAuthStatus resolveStatus() {
                return CopilotAuthResolver.CopilotAuthStatus.unauthorized("not authorized");
            }
        };

        var subject = new ProviderRuntimePolicy(resolver, codexResolver());
        var providerDefinition = copilotAuthProvider("GitHub Copilot");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isFalse();
    }

    @Test
    @DisplayName("Copilot auth providers are available when resolver reports authorized")
    void hasRequiredCredentials_whenCopilotAuthResolverIsAuthorized_returnsTrue() {
        CopilotAuthResolver resolver = new CopilotAuthResolver(
                tempDir.resolve("copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthResolver.CopilotAuthStatus resolveStatus() {
                return CopilotAuthResolver.CopilotAuthStatus.authorized("ok", "Chat4J OAuth");
            }
        };

        var subject = new ProviderRuntimePolicy(resolver, codexResolver());
        var providerDefinition = copilotAuthProvider("GitHub Copilot");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isTrue();
    }

    @Test
    @DisplayName("Slow auth checks cache their completion time rather than their start time")
    void hasRequiredCredentials_whenResolutionExceedsTtl_keepsFreshCompletedResultCached() {
        var checks = new AtomicInteger();
        var clock = new MutableClock(Instant.parse("2026-07-19T10:00:00Z"), ZoneOffset.UTC);
        CopilotAuthResolver resolver = new CopilotAuthResolver(
                tempDir.resolve("slow-copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthResolver.CopilotAuthStatus resolveStatus() {
                checks.incrementAndGet();
                clock.advance(Duration.ofSeconds(16));
                return CopilotAuthResolver.CopilotAuthStatus.authorized("ok", "Chat4J OAuth");
            }
        };
        var subject = new ProviderRuntimePolicy(resolver, codexResolver(), clock);
        var providerDefinition = copilotAuthProvider("GitHub Copilot");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isTrue();
        assertThat(subject.hasRequiredCredentials(providerDefinition)).isTrue();

        assertThat(checks).hasValue(1);
    }

    @Test
    @DisplayName("Codex auth providers are unavailable when resolver reports unauthorized")
    void hasRequiredCredentials_whenCodexAuthResolverIsUnauthorized_returnsFalse() {
        CodexAuthResolver resolver = new CodexAuthResolver(
                tempDir.resolve("codex-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CodexAuthResolver.CodexAuthStatus resolveStatus() {
                return CodexAuthResolver.CodexAuthStatus.unauthorized("not authorized");
            }
        };

        var subject = new ProviderRuntimePolicy(copilotResolver(), resolver);
        var providerDefinition = codexAuthProvider("OpenAI Codex");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isFalse();
    }

    @Test
    @DisplayName("Codex auth providers are available when resolver reports authorized")
    void hasRequiredCredentials_whenCodexAuthResolverIsAuthorized_returnsTrue() {
        CodexAuthResolver resolver = new CodexAuthResolver(
                tempDir.resolve("codex-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CodexAuthResolver.CodexAuthStatus resolveStatus() {
                return CodexAuthResolver.CodexAuthStatus.authorized("ok", "Chat4J OAuth");
            }
        };

        var subject = new ProviderRuntimePolicy(copilotResolver(), resolver);
        var providerDefinition = codexAuthProvider("OpenAI Codex");

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isTrue();
    }

    @Test
    @DisplayName("Repeated EDT warm-up requests share one in-flight OAuth check")
    void warmOAuthStatusCache_whenCalledRepeatedlyOnEdt_doesNotStartDuplicateChecks() throws Exception {
        var checks = new AtomicInteger();
        var checkStarted = new CountDownLatch(1);
        var releaseCheck = new CountDownLatch(1);
        var checkFinished = new CountDownLatch(1);
        var refreshPublished = new CountDownLatch(1);
        CopilotAuthResolver resolver = new CopilotAuthResolver(
                tempDir.resolve("warm-cache-copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthResolver.CopilotAuthStatus resolveStatus() {
                checks.incrementAndGet();
                checkStarted.countDown();
                try {
                    releaseCheck.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    checkFinished.countDown();
                }
                return CopilotAuthResolver.CopilotAuthStatus.unauthorized("not authorized");
            }
        };
        var subject = new ProviderRuntimePolicy(resolver, codexResolver());
        subject.setAuthStatusRefreshListener(refreshPublished::countDown);
        List<ProviderDefinition> providers = List.of(copilotAuthProvider("GitHub Copilot"));

        try {
            SwingUtilities.invokeAndWait(() -> {
                subject.warmOAuthStatusCache(providers);
                subject.warmOAuthStatusCache(providers);
            });
            assertThat(checkStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(checks).hasValue(1);
        } finally {
            releaseCheck.countDown();
            assertThat(checkFinished.await(2, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(refreshPublished.await(2, TimeUnit.SECONDS)).isTrue();

        SwingUtilities.invokeAndWait(() -> subject.warmOAuthStatusCache(providers));
        assertThat(checks).hasValue(1);
    }

    @Test
    @DisplayName("Invalidating OAuth status observes newly changed credentials immediately")
    void invalidateAuthStatus_whenCredentialsChange_discardsCachedStatus() {
        var authorized = new AtomicBoolean();
        CopilotAuthResolver resolver = new CopilotAuthResolver(
                tempDir.resolve("copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthResolver.CopilotAuthStatus resolveStatus() {
                return authorized.get()
                        ? CopilotAuthResolver.CopilotAuthStatus.authorized("ok", "Chat4J OAuth")
                        : CopilotAuthResolver.CopilotAuthStatus.unauthorized("not authorized");
            }
        };
        var subject = new ProviderRuntimePolicy(resolver, codexResolver());
        var providerDefinition = copilotAuthProvider("GitHub Copilot");
        assertThat(subject.hasRequiredCredentials(providerDefinition)).isFalse();

        authorized.set(true);
        assertThat(subject.hasRequiredCredentials(providerDefinition)).isFalse();
        subject.invalidateAuthStatus(providerDefinition.name());

        assertThat(subject.hasRequiredCredentials(providerDefinition)).isTrue();
    }

    @Test
    @DisplayName("OAuth checks started before invalidation cannot restore stale status")
    void invalidateAuthStatus_whenOlderCheckCompletesLater_rejectsStaleStatus() throws Exception {
        var authorized = new AtomicBoolean();
        var firstCheckStarted = new CountDownLatch(1);
        var releaseFirstCheck = new CountDownLatch(1);
        CopilotAuthResolver resolver = new CopilotAuthResolver(
                tempDir.resolve("copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthResolver.CopilotAuthStatus resolveStatus() {
                boolean result = authorized.get();
                if (firstCheckStarted.getCount() > 0) {
                    firstCheckStarted.countDown();
                    try {
                        releaseFirstCheck.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return result
                        ? CopilotAuthResolver.CopilotAuthStatus.authorized("ok", "Chat4J OAuth")
                        : CopilotAuthResolver.CopilotAuthStatus.unauthorized("not authorized");
            }
        };
        var subject = new ProviderRuntimePolicy(resolver, codexResolver());
        var providerDefinition = copilotAuthProvider("GitHub Copilot");
        var staleResult = new AtomicBoolean(true);
        Thread staleCheck = Thread.startVirtualThread(() -> staleResult.set(subject.hasRequiredCredentials(providerDefinition)));

        try {
            assertThat(firstCheckStarted.await(2, TimeUnit.SECONDS)).isTrue();
            authorized.set(true);
            subject.invalidateAuthStatus(providerDefinition.name());
        } finally {
            releaseFirstCheck.countDown();
            staleCheck.join(2_000);
        }

        assertThat(staleCheck.isAlive()).isFalse();
        assertThat(staleResult).isFalse();
        assertThat(subject.hasRequiredCredentials(providerDefinition)).isTrue();
    }

    @Test
    @DisplayName("Invalidating an EDT auth refresh allows the new generation to start immediately")
    void invalidateAuthStatus_whenEdtRefreshIsInFlight_startsReplacementRefresh() throws Exception {
        var authorized = new AtomicBoolean();
        var checks = new AtomicInteger();
        var firstCheckStarted = new CountDownLatch(1);
        var secondCheckStarted = new CountDownLatch(1);
        var releaseFirstCheck = new CountDownLatch(1);
        var releaseSecondCheck = new CountDownLatch(1);
        var checksFinished = new CountDownLatch(2);
        CopilotAuthResolver resolver = new CopilotAuthResolver(
                tempDir.resolve("copilot-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CopilotAuthResolver.CopilotAuthStatus resolveStatus() {
                int check = checks.incrementAndGet();
                boolean result = authorized.get();
                CountDownLatch started = check == 1 ? firstCheckStarted : secondCheckStarted;
                CountDownLatch release = check == 1 ? releaseFirstCheck : releaseSecondCheck;
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    checksFinished.countDown();
                }
                return result
                        ? CopilotAuthResolver.CopilotAuthStatus.authorized("ok", "Chat4J OAuth")
                        : CopilotAuthResolver.CopilotAuthStatus.unauthorized("not authorized");
            }
        };
        var subject = new ProviderRuntimePolicy(resolver, codexResolver());
        var providerDefinition = copilotAuthProvider("GitHub Copilot");

        try {
            SwingUtilities.invokeAndWait(() -> assertThat(subject.hasRequiredCredentials(providerDefinition)).isFalse());
            assertThat(firstCheckStarted.await(2, TimeUnit.SECONDS)).isTrue();

            authorized.set(true);
            subject.invalidateAuthStatus(providerDefinition.name());
            SwingUtilities.invokeAndWait(() -> assertThat(subject.hasRequiredCredentials(providerDefinition)).isFalse());

            assertThat(secondCheckStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(checks).hasValue(2);
        } finally {
            releaseFirstCheck.countDown();
            releaseSecondCheck.countDown();
            assertThat(checksFinished.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("Invalidating one OAuth provider does not discard another provider's in-flight status")
    void invalidateAuthStatus_whenDifferentProviderCheckIsInFlight_keepsIndependentStatus() throws Exception {
        var checks = new AtomicInteger();
        var checkStarted = new CountDownLatch(1);
        var releaseCheck = new CountDownLatch(1);
        CodexAuthResolver codexResolver = new CodexAuthResolver(
                tempDir.resolve("codex-home"),
                emptyMap(),
                HttpClient.newHttpClient()
        ) {
            @Override
            public CodexAuthResolver.CodexAuthStatus resolveStatus() {
                checks.incrementAndGet();
                checkStarted.countDown();
                try {
                    releaseCheck.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return CodexAuthResolver.CodexAuthStatus.authorized("ok", "Chat4J OAuth");
            }
        };
        var subject = new ProviderRuntimePolicy(copilotResolver(), codexResolver);
        var codexProvider = codexAuthProvider("OpenAI Codex");
        Thread codexCheck = Thread.startVirtualThread(() -> subject.hasRequiredCredentials(codexProvider));

        try {
            assertThat(checkStarted.await(2, TimeUnit.SECONDS)).isTrue();
            subject.invalidateAuthStatus("GitHub Copilot");
        } finally {
            releaseCheck.countDown();
            codexCheck.join(2_000);
        }

        assertThat(codexCheck.isAlive()).isFalse();
        assertThat(subject.hasRequiredCredentials(codexProvider)).isTrue();
        assertThat(checks).hasValue(1);
    }

    @Test
    @DisplayName("Effective base URLs use the provider's canonical runtime normalization")
    void effectiveBaseUrl_whenAnthropicOverrideHasV1Suffix_returnsRootUrl() {
        var subject = new ProviderRuntimePolicy(copilotResolver(), codexResolver());
        var descriptor = new ProviderDescriptor(
                "Anthropic",
                AuthType.ENV_VAR,
                "ANTHROPIC_API_KEY",
                null,
                "https://api.anthropic.com",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                BaseUrlNormalizer::normalizeAnthropicBaseUrl
        );
        ProviderDefinition providerDefinition = providerDefinition(descriptor);
        subject.applyRuntimeConfig(Map.of(
                "Anthropic",
                new ProviderRuntimePolicy.RuntimeConfig(true, "https://proxy.example.com/v1/")
        ));

        assertThat(subject.effectiveBaseUrl(providerDefinition)).isEqualTo("https://proxy.example.com");
    }

    @Test
    @DisplayName("Effective base URL trims runtime override whitespace")
    void effectiveBaseUrl_whenRuntimeBaseUrlHasWhitespace_returnsTrimmedValue() {
        var subject = new ProviderRuntimePolicy(copilotResolver(), codexResolver());
        ProviderDefinition providerDefinition = envVarProvider("Ollama", "http://localhost:11434/v1");
        subject.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRuntimePolicy.RuntimeConfig(true, "  http://127.0.0.1:11434/v1  ")
        ));

        String baseUrl = subject.effectiveBaseUrl(providerDefinition);

        assertThat(baseUrl).isEqualTo("http://127.0.0.1:11434/v1");
    }

    @Test
    @DisplayName("Effective base URL falls back to provider default for blank runtime override")
    void effectiveBaseUrl_whenRuntimeBaseUrlIsBlank_returnsProviderDefault() {
        var subject = new ProviderRuntimePolicy(copilotResolver(), codexResolver());
        ProviderDefinition providerDefinition = envVarProvider("Ollama", "http://localhost:11434/v1");
        subject.applyRuntimeConfig(Map.of(
                "Ollama",
                new ProviderRuntimePolicy.RuntimeConfig(true, "   ")
        ));

        String baseUrl = subject.effectiveBaseUrl(providerDefinition);

        assertThat(baseUrl).isEqualTo("http://localhost:11434/v1");
    }

    private CopilotAuthResolver copilotResolver() {
        return new CopilotAuthResolver(tempDir.resolve("default-copilot-home"), emptyMap(), HttpClient.newHttpClient());
    }

    private CodexAuthResolver codexResolver() {
        return new CodexAuthResolver(tempDir.resolve("default-codex-home"), emptyMap(), HttpClient.newHttpClient());
    }

    private ProviderDefinition copilotAuthProvider(String name) {
        var descriptor = new ProviderDescriptor(
                name,
                AuthType.COPILOT_OAUTH,
                null,
                null,
                "https://api.githubcopilot.com",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity()
        );

        ProviderModule module = new ProviderModule() {
            @Override
            public ProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ChatCompletionClient chatCompletionClient() {
                throw new UnsupportedOperationException("Not required in this test");
            }

            @Override
            public ModelCatalogClient modelCatalogClient() {
                throw new UnsupportedOperationException("Not required in this test");
            }
        };

        return new ProviderDefinition(descriptor, module);
    }

    private ProviderDefinition envVarProvider(String name, String baseUrl) {
        var descriptor = new ProviderDescriptor(
                name,
                AuthType.ENV_VAR,
                "API_KEY",
                null,
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity()
        );

        return providerDefinition(descriptor);
    }

    private ProviderDefinition codexAuthProvider(String name) {
        var descriptor = new ProviderDescriptor(
                name,
                AuthType.CODEX_OAUTH,
                null,
                null,
                "https://api.openai.com/v1",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                UnaryOperator.identity()
        );

        return providerDefinition(descriptor);
    }

    private ProviderDefinition providerDefinition(ProviderDescriptor descriptor) {
        ProviderModule module = new ProviderModule() {
            @Override
            public ProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ChatCompletionClient chatCompletionClient() {
                throw new UnsupportedOperationException("Not required in this test");
            }

            @Override
            public ModelCatalogClient modelCatalogClient() {
                throw new UnsupportedOperationException("Not required in this test");
            }
        };

        return new ProviderDefinition(descriptor, module);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
