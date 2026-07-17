package com.github.drafael.chat4j.persistence;

import com.github.drafael.chat4j.persistence.catalog.CatalogSnapshotStore;
import com.github.drafael.chat4j.persistence.catalog.ObsoleteSpeechCatalogSettingsCleanup;
import com.github.drafael.chat4j.persistence.migration.LegacyCacheCleanupService;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Performs non-fatal cache storage preparation before cache consumers start. */
@RequiredArgsConstructor
public final class CacheStorageInitializer {

    @NonNull
    private final StoragePaths storagePaths;
    @NonNull
    private final SettingsRepository settings;

    public CacheStorage initialize() {
        CacheRootHandle root = CacheRootHandle.from(storagePaths);
        root.availableRoot();
        new LegacyCacheCleanupService(storagePaths).cleanup();
        new ObsoleteSpeechCatalogSettingsCleanup(settings).cleanup();
        CatalogSnapshotStore snapshots = CatalogSnapshotStore.shared(root, settings);
        snapshots.cleanupOrphans();
        snapshots.preloadActiveCatalogs();
        return new CacheStorage(root, snapshots);
    }

    public record CacheStorage(CacheRootHandle root, CatalogSnapshotStore snapshots) {
    }
}
