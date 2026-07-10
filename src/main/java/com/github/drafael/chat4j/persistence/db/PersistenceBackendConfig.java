package com.github.drafael.chat4j.persistence.db;

import java.util.Optional;
import lombok.NonNull;

public final class PersistenceBackendConfig {

    public static final StorageBackend DEFAULT_BACKEND = StorageBackend.SQLITE;

    private final StorageBackend activeBackend;
    private final StorageBackend pendingBackend;

    public PersistenceBackendConfig(@NonNull StorageBackend activeBackend, StorageBackend pendingBackend) {
        this.activeBackend = activeBackend;
        this.pendingBackend = pendingBackend;
    }

    public StorageBackend activeBackend() {
        return activeBackend;
    }

    public Optional<StorageBackend> pendingBackend() {
        return Optional.ofNullable(pendingBackend);
    }

    public Optional<StorageBackend> pendingMigrationTarget() {
        return pendingBackend().filter(backend -> backend != activeBackend);
    }
}
