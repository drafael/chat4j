package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotModelMetadataStoreTest {

    @Test
    @DisplayName("Metadata updates preserve previously known endpoints when new snapshot omits them")
    void update_whenNewSnapshotOmitsEndpoints_preservesExistingMetadata() throws Exception {
        var subject = new CopilotModelMetadataStore(Files.createTempDirectory("copilot-model-metadata-store"));

        subject.update(
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("claude-sonnet-4.6", List.of("/chat/completions")))
        );
        subject.update(
                "https://api.githubcopilot.com",
                List.of(new CopilotModelMetadataStore.ModelMetadata("claude-sonnet-4.6", List.of()))
        );

        assertThat(subject.supportedEndpoints("https://api.githubcopilot.com", "claude-sonnet-4.6"))
                .containsExactly("/chat/completions");
    }

    @Test
    @DisplayName("Concurrent metadata updates for the same base URL keep all model endpoint entries")
    void update_whenConcurrentUpdatesTargetSameBaseUrl_keepsAllModelMetadata() throws Exception {
        var subject = new CopilotModelMetadataStore(Files.createTempDirectory("copilot-model-metadata-store"));
        var baseUrl = "https://api.githubcopilot.com";

        subject.update(baseUrl, List.of(new CopilotModelMetadataStore.ModelMetadata("seed", List.of("/responses"))));

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
                            subject.update(baseUrl, List.of(new CopilotModelMetadataStore.ModelMetadata(modelId, List.of(endpoint))));
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
}
