package com.github.drafael.chat4j.persistence;

import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class CacheStorageInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Startup cleanup preserves current references and selections while removing obsolete cache data")
    void initialize_whenLegacyInlineAndOrphanDataExist_appliesSafeStartupOrder() throws Exception {
        var storagePaths = StoragePaths.ofConfigHome(tempDir);
        var settings = new SettingsRepository(storagePaths);
        Path legacyRoot = Files.createDirectories(storagePaths.legacyModelsCacheDirectory());
        Files.writeString(legacyRoot.resolve("provider.txt"), "legacy");
        Files.writeString(legacyRoot.resolve("keep.bin"), "keep");
        Path cacheRoot = Files.createDirectories(storagePaths.cacheDirectory());
        String activeReference = "tts-elevenlabs-models-11111111111111111111111111111111.json";
        String orphanReference = "stt-deepgram-models-22222222222222222222222222222222.json";
        Files.writeString(cacheRoot.resolve(activeReference), "[]");
        Files.writeString(cacheRoot.resolve(orphanReference), "[]");
        settings.put("chat4j.tts.catalog.elevenlabs.models", "obsolete-inline-json");
        settings.put("chat4j.tts.catalog.elevenlabs.updatedAt", "2026-07-15T00:00:00Z");
        settings.put("chat4j.tts.catalog.elevenlabs.modelsFile", activeReference);
        settings.put("chat4j.tts.elevenlabs.model.id", "selected-model");
        var subject = new CacheStorageInitializer(storagePaths, settings);

        CacheStorageInitializer.CacheStorage initialized = subject.initialize();

        assertThat(initialized.root()).isSameAs(CacheRootHandle.from(storagePaths));
        assertThat(initialized.snapshots()).isSameAs(CatalogSnapshotStore.shared(initialized.root(), settings));
        assertThat(legacyRoot.resolve("provider.txt")).doesNotExist();
        assertThat(legacyRoot.resolve("keep.bin")).hasContent("keep");
        assertThat(settings.get("chat4j.tts.catalog.elevenlabs.models")).isEmpty();
        assertThat(settings.get("chat4j.tts.catalog.elevenlabs.updatedAt")).isEmpty();
        assertThat(settings.get("chat4j.tts.catalog.elevenlabs.modelsFile")).contains(activeReference);
        assertThat(settings.get("chat4j.tts.elevenlabs.model.id")).contains("selected-model");
        assertThat(cacheRoot.resolve(activeReference)).isRegularFile();
        assertThat(cacheRoot.resolve(orphanReference)).doesNotExist();
    }
}
