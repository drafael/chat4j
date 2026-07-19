package com.github.drafael.chat4j.settings;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class ProviderAuthLifecycle {

    private final Map<Object, Runnable> cancellationByResource = new IdentityHashMap<>();
    private long generation = 1L;
    private boolean active = true;

    synchronized long currentGeneration() {
        return generation;
    }

    synchronized boolean isCurrent(long expectedGeneration) {
        return active && generation == expectedGeneration;
    }

    synchronized void activate() {
        generation++;
        active = true;
    }

    void deactivate() {
        List<Runnable> cancellations;
        synchronized (this) {
            generation++;
            active = false;
            cancellations = new ArrayList<>(cancellationByResource.values());
            cancellationByResource.clear();
        }
        cancellations.forEach(ProviderAuthLifecycle::cancelQuietly);
    }

    boolean register(long expectedGeneration, Object resource, Runnable cancellation) {
        boolean registered;
        synchronized (this) {
            registered = active && generation == expectedGeneration;
            if (registered) {
                cancellationByResource.put(resource, cancellation);
            }
        }
        if (!registered) {
            cancelQuietly(cancellation);
        }
        return registered;
    }

    synchronized void unregister(Object resource) {
        cancellationByResource.remove(resource);
    }

    private static void cancelQuietly(Runnable cancellation) {
        try {
            cancellation.run();
        } catch (RuntimeException ignored) {
            // Best-effort lifecycle cleanup must continue for the remaining resources.
        }
    }
}
