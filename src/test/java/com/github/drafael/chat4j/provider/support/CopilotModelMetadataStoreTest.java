package com.github.drafael.chat4j.provider.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Collections.emptyList;

class CopilotModelMetadataStoreTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Metadata updates preserve previously known endpoints when new snapshot omits them")
    void update_whenNewSnapshotOmitsEndpoints_preservesExistingMetadata() throws Exception {
        var subject = new CopilotModelMetadataStore(directory("preserve"));

        storeMetadata(
                subject,
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("claude-sonnet-4.6", List.of("/chat/completions")))
        );
        storeMetadata(
                subject,
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("claude-sonnet-4.6", emptyList()))
        );

        assertThat(subject.supportedEndpoints("https://api.githubcopilot.com", "claude-sonnet-4.6"))
                .containsExactly("/chat/completions");
    }

    @Test
    @DisplayName("A metadata symlink is never followed during persistence")
    void update_whenMetadataFileIsSymlink_preservesExternalTarget() throws Exception {
        Path cacheDirectory = directory("symlink");
        Path external = Files.createTempFile(tempDir, "copilot-external", ".json");
        Files.writeString(external, "external");
        createSymlinkOrSkip(cacheDirectory.resolve("github-copilot-model-metadata.json"), external);
        var subject = new CopilotModelMetadataStore(cacheDirectory);

        storeMetadata(
                subject,
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("model", List.of("/responses")))
        );

        assertThat(external).hasContent("external");
    }

    @Test
    @DisplayName("Persisted metadata is restored by a new store instance")
    void supportedEndpoints_whenStoreIsRecreated_loadsPersistedMetadata() throws Exception {
        Path cacheDirectory = directory("reload");
        var writer = new CopilotModelMetadataStore(cacheDirectory);
        storeMetadata(
                writer,
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("model", List.of("/responses")))
        );

        var subject = new CopilotModelMetadataStore(cacheDirectory);

        assertThat(subject.supportedEndpoints("https://api.githubcopilot.com", "model"))
                .containsExactly("/responses");
    }

    @Test
    @DisplayName("Malformed UTF-8 metadata is ignored")
    void supportedEndpoints_whenCacheContainsMalformedUtf8_returnsEmptyList() throws Exception {
        Path cacheDirectory = directory("malformed");
        Files.write(
                cacheDirectory.resolve("github-copilot-model-metadata.json"),
                new byte[]{(byte) 0xc3, 0x28}
        );
        var subject = new CopilotModelMetadataStore(cacheDirectory);

        assertThat(subject.supportedEndpoints("https://api.githubcopilot.com", "model")).isEmpty();
    }

    @Test
    @DisplayName("Valid metadata at exactly eight MiB is accepted")
    void supportedEndpoints_whenCacheIsExactlyReadLimit_loadsMetadata() throws Exception {
        Path cacheDirectory = directory("exact-limit");
        String content = """
                {
                  "catalogsByBaseUrl": {
                    "https://api.githubcopilot.com": {
                      "models": {
                        "model": ["/responses"]
                      }
                    }
                  }
                }
                """;
        int paddingBytes = (8 * 1024 * 1024) - content.getBytes(StandardCharsets.UTF_8).length;
        Files.writeString(
                cacheDirectory.resolve("github-copilot-model-metadata.json"),
                "%s%s".formatted(content, " ".repeat(paddingBytes)),
                StandardCharsets.UTF_8
        );
        var subject = new CopilotModelMetadataStore(cacheDirectory);

        assertThat(subject.supportedEndpoints("https://api.githubcopilot.com", "model"))
                .containsExactly("/responses");
    }

    @Test
    @DisplayName("Metadata larger than eight MiB is ignored")
    void supportedEndpoints_whenCacheExceedsReadLimit_returnsEmptyList() throws Exception {
        Path cacheDirectory = directory("oversized");
        Files.write(
                cacheDirectory.resolve("github-copilot-model-metadata.json"),
                new byte[8 * 1024 * 1024 + 1]
        );
        var subject = new CopilotModelMetadataStore(cacheDirectory);

        assertThat(subject.supportedEndpoints("https://api.githubcopilot.com", "model")).isEmpty();
    }

    @Test
    @DisplayName("Clearing metadata removes in-memory and persisted endpoint state")
    void clear_whenMetadataExists_removesMemoryAndFile() throws Exception {
        Path cacheDirectory = directory("clear");
        Path cacheFile = cacheDirectory.resolve("github-copilot-model-metadata.json");
        var subject = new CopilotModelMetadataStore(cacheDirectory);
        storeMetadata(
                subject,
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("model", List.of("/responses")))
        );

        boolean cleared = subject.clear();

        assertThat(cleared).isTrue();
        assertThat(subject.supportedEndpoints("https://api.githubcopilot.com", "model")).isEmpty();
        assertThat(cacheFile).doesNotExist();
    }

    @Test
    @DisplayName("Metadata fetched before an auth clear cannot repopulate the store")
    void updateIfGenerationCurrent_whenAuthClearAdvancedGeneration_rejectsStaleMetadata() throws Exception {
        var subject = new CopilotModelMetadataStore(directory("stale-generation"));
        long staleGeneration = subject.currentGeneration();
        assertThat(subject.clear()).isTrue();

        boolean updated = subject.updateIfGenerationCurrent(
                staleGeneration,
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("stale-model", List.of("/responses")))
        );

        assertThat(updated).isFalse();
        assertThat(subject.supportedEndpoints("https://api.githubcopilot.com", "stale-model")).isEmpty();
    }

    @Test
    @DisplayName("Deletion failure still clears current-process metadata")
    void clear_whenMetadataPathBecomesUnsafe_returnsFalseAndKeepsMemoryEmpty() throws Exception {
        Path cacheDirectory = directory("clear-failure");
        Path cacheFile = cacheDirectory.resolve("github-copilot-model-metadata.json");
        var subject = new CopilotModelMetadataStore(cacheDirectory);
        storeMetadata(
                subject,
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("model", List.of("/responses")))
        );
        Files.delete(cacheFile);
        Files.createDirectory(cacheFile);

        boolean cleared = subject.clear();

        assertThat(cleared).isFalse();
        assertThat(subject.supportedEndpoints("https://api.githubcopilot.com", "model")).isEmpty();
    }

    @Test
    @DisplayName("Concurrent metadata updates for the same base URL keep all model endpoint entries")
    void update_whenConcurrentUpdatesTargetSameBaseUrl_keepsAllModelMetadata() throws Exception {
        var subject = new CopilotModelMetadataStore(directory("concurrent"));
        var baseUrl = "https://api.githubcopilot.com";

        storeMetadata(
                subject,
                baseUrl,
                List.of(new CopilotModelMetadataStore.ModelMetadata("seed", List.of("/responses")))
        );

        int workers = 12;
        int rounds = 25;
        var pool = Executors.newFixedThreadPool(workers);

        try {
            IntStream.range(0, rounds).forEach(round -> {
                var startGate = new CountDownLatch(1);
                var futures = IntStream.range(0, workers)
                        .mapToObj(worker -> pool.submit(() -> {
                            startGate.await();
                            String modelId = "round-%d-model-%d".formatted(round, worker);
                            String endpoint = worker % 2 == 0 ? "/responses" : "/chat/completions";
                            storeMetadata(
                                    subject,
                                    baseUrl,
                                    List.of(new CopilotModelMetadataStore.ModelMetadata(modelId, List.of(endpoint)))
                            );
                            return null;
                        }))
                        .toList();

                startGate.countDown();
                futures.forEach(future -> {
                    try {
                        future.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            });
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        IntStream.range(0, rounds).forEach(round -> IntStream.range(0, workers).forEach(worker -> {
            String modelId = "round-%d-model-%d".formatted(round, worker);
            String expectedEndpoint = worker % 2 == 0 ? "/responses" : "/chat/completions";
            assertThat(subject.supportedEndpoints(baseUrl, modelId)).containsExactly(expectedEndpoint);
        }));
    }

    private static void storeMetadata(
            CopilotModelMetadataStore store,
            String baseUrl,
            List<CopilotModelMetadataStore.ModelMetadata> metadata
    ) {
        assertThat(store.updateIfGenerationCurrent(store.currentGeneration(), baseUrl, metadata)).isTrue();
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectory(tempDir.resolve(name));
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("Symbolic links are unavailable: %s".formatted(e.getMessage()));
        }
    }
}
