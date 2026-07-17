package com.github.drafael.chat4j.persistence.catalog;

import java.util.List;
import org.apache.commons.lang3.Validate;

/** Correlated catalog pointers sharing one updated-at setting. */
public final class CatalogGroup {

    private final List<SnapshotSlot> slots;
    private final String updatedAtKey;

    CatalogGroup(List<SnapshotSlot> slots, String updatedAtKey) {
        this.slots = List.copyOf(slots);
        Validate.isTrue(!this.slots.isEmpty(), "slots should not be empty");
        this.updatedAtKey = Validate.notBlank(updatedAtKey, "updatedAtKey should not be blank");
    }

    public List<SnapshotSlot> slots() {
        return slots;
    }

    public String updatedAtKey() {
        return updatedAtKey;
    }

    @Override
    public String toString() {
        return "CatalogGroup[slots=%s, updatedAtKey=%s]".formatted(slots, updatedAtKey);
    }
}
