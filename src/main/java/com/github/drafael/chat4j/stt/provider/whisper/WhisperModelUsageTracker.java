package com.github.drafael.chat4j.stt.provider.whisper;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.commons.lang3.StringUtils;

public class WhisperModelUsageTracker {

    private final ConcurrentMap<String, Integer> leasesByModelId = new ConcurrentHashMap<>();

    public Lease acquire(String modelId) {
        String normalized = StringUtils.trimToEmpty(modelId);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        leasesByModelId.merge(normalized, 1, Integer::sum);
        return new Lease(normalized);
    }

    public boolean inUse(String modelId) {
        Integer leaseCount = leasesByModelId.get(StringUtils.trimToEmpty(modelId));
        return leaseCount != null && leaseCount > 0;
    }

    public final class Lease implements AutoCloseable {
        private final String modelId;
        private boolean closed;

        private Lease(String modelId) {
            this.modelId = modelId;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            leasesByModelId.computeIfPresent(
                    modelId,
                    (ignored, leaseCount) -> leaseCount <= 1 ? null : leaseCount - 1
            );
        }

        @Override
        public String toString() {
            return "WhisperModelUsageTracker.Lease[modelId=%s, closed=%s]".formatted(Objects.toString(modelId, ""), closed);
        }
    }
}
