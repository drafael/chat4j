package com.github.drafael.chat4j.provider.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CodexLocalModelCacheTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Codex local cache parser reads model slugs and removes duplicates")
    void readSnapshot_whenLocalCacheContainsSlugs_returnsDistinctModels() throws Exception {
        Path codexDirectory = tempDir.resolve(".codex");
        Files.createDirectories(codexDirectory);
        Files.writeString(codexDirectory.resolve("models_cache.json"), """
                {
                  "models": [
                    {"slug": "local-codex-new"},
                    {"slug": "local-codex-old"},
                    {"slug": "local-codex-new"},
                    {"slug": "conflicting-model"},
                    {"slug": "conflicting-model", "visibility": "hide"},
                    {"slug": "hidden-internal-model", "visibility": "hide"},
                    {"slug": "gpt-5.4-mini", "visibility": "hide"}
                  ]
                }
                """);

        assertThat(CodexLocalModelCache.readSnapshot(tempDir).models())
                .contains("local-codex-new", "local-codex-old")
                .doesNotContain("conflicting-model", "hidden-internal-model", "gpt-5.4-mini")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Missing Codex local cache uses the built-in model list successfully")
    void readSnapshot_whenLocalCacheIsMissing_returnsSuccessfulBuiltinSnapshot() {
        CodexLocalModelCache.Snapshot snapshot = CodexLocalModelCache.readSnapshot(tempDir);

        assertThat(snapshot.loadedSuccessfully()).isTrue();
        assertThat(snapshot.models()).contains("gpt-5.4");
    }

    @Test
    @DisplayName("Codex local cache visibility remains valid regardless of fetched timestamp")
    void readSnapshot_whenFetchedTimestampIsOld_stillUsesCurrentFileVisibility() throws Exception {
        Path codexDirectory = tempDir.resolve(".codex");
        Files.createDirectories(codexDirectory);
        Files.writeString(codexDirectory.resolve("models_cache.json"), """
                {
                  "fetched_at": "2000-01-01T00:00:00Z",
                  "models": [{"slug": "still-visible-model"}]
                }
                """);

        CodexLocalModelCache.Snapshot snapshot = CodexLocalModelCache.readSnapshot(tempDir);

        assertThat(snapshot.loadedSuccessfully()).isTrue();
        assertThat(snapshot.models()).contains("still-visible-model");
    }

    @Test
    @DisplayName("Hidden Codex models remain hidden when merging local and remote catalogs")
    void merge_whenSnapshotContainsConflictingVisibility_excludesHiddenModel() {
        var snapshot = new CodexLocalModelCache.Snapshot(
                List.of("conflicting-model", "visible-local-model"),
                List.of("conflicting-model", "hidden-remote-model")
        );

        assertThat(CodexLocalModelCache.merge(
                List.of("conflicting-model", "hidden-remote-model", "visible-remote-model"),
                snapshot
        ))
                .contains("visible-local-model", "visible-remote-model")
                .doesNotContain("conflicting-model", "hidden-remote-model");
    }

    @Test
    @DisplayName("Codex local cache parser ignores slug fields outside the models array")
    void readSnapshot_whenSlugAppearsOutsideModelsArray_ignoresUnrelatedSlug() throws Exception {
        Path codexDirectory = tempDir.resolve(".codex");
        Files.createDirectories(codexDirectory);
        Files.writeString(codexDirectory.resolve("models_cache.json"), """
                {"metadata": {"slug": "not-a-model"}}
                """);

        assertThat(CodexLocalModelCache.readSnapshot(tempDir).models()).doesNotContain("not-a-model");
    }

    @Test
    @DisplayName("Codex local cache parser ignores truncated JSON")
    void readSnapshot_whenJsonIsMalformed_ignoresPartialSlug() throws Exception {
        Path codexDirectory = tempDir.resolve(".codex");
        Files.createDirectories(codexDirectory);
        Files.writeString(codexDirectory.resolve("models_cache.json"), """
                {"models": [{"slug": "partial-model"}
                """);

        CodexLocalModelCache.Snapshot snapshot = CodexLocalModelCache.readSnapshot(tempDir);
        assertThat(snapshot.models()).doesNotContain("partial-model");
        assertThat(snapshot.loadedSuccessfully()).isFalse();
    }
}
