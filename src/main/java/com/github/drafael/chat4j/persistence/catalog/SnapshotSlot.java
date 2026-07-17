package com.github.drafael.chat4j.persistence.catalog;

import java.util.Objects;
import java.util.regex.Pattern;
import org.apache.commons.lang3.Validate;

/** Schema-owned identity of exactly one settings pointer and filename namespace. */
public final class SnapshotSlot {

    private static final Pattern PREFIX = Pattern.compile("[a-z0-9-]+-");

    private final String referenceKey;
    private final String filenamePrefix;

    SnapshotSlot(String referenceKey, String filenamePrefix) {
        this.referenceKey = Validate.notBlank(referenceKey, "referenceKey should not be blank");
        Validate.isTrue(PREFIX.matcher(filenamePrefix).matches(), "filenamePrefix is invalid");
        this.filenamePrefix = filenamePrefix;
    }

    public String referenceKey() {
        return referenceKey;
    }

    public String filenamePrefix() {
        return filenamePrefix;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof SnapshotSlot slot
                && referenceKey.equals(slot.referenceKey)
                && filenamePrefix.equals(slot.filenamePrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(referenceKey, filenamePrefix);
    }

    @Override
    public String toString() {
        return "SnapshotSlot[referenceKey=%s, filenamePrefix=%s]".formatted(referenceKey, filenamePrefix);
    }
}
