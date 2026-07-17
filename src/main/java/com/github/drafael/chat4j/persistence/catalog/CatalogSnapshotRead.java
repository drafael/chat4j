package com.github.drafael.chat4j.persistence.catalog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static java.util.Collections.unmodifiableMap;

/** Coherent settings-and-payload image for one catalog group. */
public final class CatalogSnapshotRead {

    private final Map<SnapshotSlot, CatalogPayload> payloads;
    private final String updatedAt;

    CatalogSnapshotRead(Map<SnapshotSlot, CatalogPayload> payloads, String updatedAt) {
        this.payloads = unmodifiableMap(new LinkedHashMap<>(payloads));
        this.updatedAt = updatedAt;
    }

    public Optional<CatalogPayload> payload(SnapshotSlot slot) {
        return Optional.ofNullable(payloads.get(slot));
    }

    public Optional<String> updatedAt() {
        return Optional.ofNullable(updatedAt);
    }

    @Override
    public String toString() {
        return "CatalogSnapshotRead[payloadCount=%d, updatedAtPresent=%s]"
                .formatted(payloads.size(), updatedAt != null);
    }
}
