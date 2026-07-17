package com.github.drafael.chat4j.persistence;

import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CacheRootHandleTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("An absent cache root is created as a normal directory")
    void availableRoot_whenRootIsAbsent_createsDirectory() {
        var subject = CacheRootHandle.of(tempDir.resolve("cache"));

        assertThat(subject.availableRoot()).contains(subject.path());
        assertThat(subject.path()).isDirectory();
    }

    @Test
    @DisplayName("A cache root symlink is rejected")
    void availableRoot_whenRootIsSymlink_returnsUnavailable() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Path link = tempDir.resolve("cache");
        createSymlinkOrSkip(link, target);
        var subject = CacheRootHandle.of(link);

        assertThat(subject.availableRoot()).isEmpty();
    }

    @Test
    @DisplayName("Ancestor aliases converge to the same root identity")
    void of_whenAncestorIsSymlink_returnsSameHandle() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Path alias = tempDir.resolve("alias");
        createSymlinkOrSkip(alias, target);

        var canonical = CacheRootHandle.of(target.resolve("config").resolve("cache"));
        var throughAlias = CacheRootHandle.of(alias.resolve("config").resolve("cache"));

        assertThat(throughAlias).isSameAs(canonical);
    }

    @Test
    @DisplayName("Settings aliases share one file identity and snapshot store")
    void canonicalPath_whenSettingsAncestorIsAliased_convergesRepositoryAndStoreIdentity() throws Exception {
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Path alias = tempDir.resolve("alias");
        createSymlinkOrSkip(alias, target);
        var canonicalSettings = new SettingsRepository(target.resolve("config").resolve("chat4j.properties"));
        var aliasedSettings = new SettingsRepository(alias.resolve("config").resolve("chat4j.properties"));

        assertThat(aliasedSettings.settingsFileIdentity()).isEqualTo(canonicalSettings.settingsFileIdentity());
        assertThat(CatalogSnapshotStore.forSettings(aliasedSettings))
                .isSameAs(CatalogSnapshotStore.forSettings(canonicalSettings));
    }

    @Test
    @DisplayName("A substituted cache directory is rejected after root identity is established")
    void availableRoot_whenEstablishedDirectoryIsReplaced_returnsUnavailable() throws Exception {
        var subject = CacheRootHandle.of(tempDir.resolve("cache"));
        Path established = subject.availableRoot().orElseThrow();
        Files.move(established, tempDir.resolve("original-cache"));
        Path replacement = Files.createDirectory(established);

        assertThat(subject.availableRoot()).isEmpty();
        assertThat(subject.directChild("snapshot.json")).isEmpty();
        assertThat(replacement).isDirectory();
    }

    @Test
    @DisplayName("Only portable basenames are accepted")
    void isBasename_whenValueContainsUnsafePathSyntax_rejectsValue() {
        assertThat(CacheRootHandle.isBasename("snapshot.json")).isTrue();
        assertThat(CacheRootHandle.isBasename("../snapshot.json")).isFalse();
        assertThat(CacheRootHandle.isBasename("folder/snapshot.json")).isFalse();
        assertThat(CacheRootHandle.isBasename("folder\\snapshot.json")).isFalse();
        assertThat(CacheRootHandle.isBasename("\u0000")).isFalse();
    }

    private static void createSymlinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.abort("Symbolic links are unavailable: %s".formatted(e.getMessage()));
        }
    }
}
