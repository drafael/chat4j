package com.github.drafael.chat4j.stt.provider.whisper;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.StringUtils;

public class WhisperModelUsageTracker {

    private final Map<String, AtomicInteger> leasesByModelId = new ConcurrentHashMap<>();

    public Lease acquire(String modelId) {
        String normalized = StringUtils.trimToEmpty(modelId);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        leasesByModelId.computeIfAbsent(normalized, ignored -> new AtomicInteger()).incrementAndGet();
        return new Lease(normalized);
    }

    public boolean inUse(String modelId) {
        AtomicInteger counter = leasesByModelId.get(StringUtils.trimToEmpty(modelId));
        return counter != null && counter.get() > 0;
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
            AtomicInteger counter = leasesByModelId.get(modelId);
            if (counter != null && counter.decrementAndGet() <= 0) {
                leasesByModelId.remove(modelId, counter);
            }
        }

        @Override
        public String toString() {
            return "WhisperModelUsageTracker.Lease[modelId=%s, closed=%s]".formatted(Objects.toString(modelId, ""), closed);
        }
    }
}
