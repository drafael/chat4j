package com.github.drafael.chat4j.provider.support;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import javax.swing.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Slf4j
public final class LocalServiceHealth {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(350);
    private static final Duration CACHE_TTL = Duration.ofSeconds(2);
    private static final ConcurrentHashMap<String, HealthSnapshot> SNAPSHOT_BY_BASE_URL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<Boolean>> REFRESH_BY_BASE_URL = new ConcurrentHashMap<>();

    private LocalServiceHealth() {
    }

    public static boolean isReachable(String baseUrl) {
        if (StringUtils.isBlank(baseUrl) || Thread.currentThread().isInterrupted()) {
            return false;
        }

        Instant now = Instant.now();
        HealthSnapshot cached = SNAPSHOT_BY_BASE_URL.get(baseUrl);
        if (cached != null && now.isBefore(cached.checkedAt().plus(CACHE_TTL))) {
            return cached.reachable();
        }

        if (SwingUtilities.isEventDispatchThread()) {
            triggerRefresh(baseUrl);
            return cached != null && cached.reachable();
        }

        try {
            return refreshAsync(baseUrl).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            log.debug("Health refresh failed for {}: {}", baseUrl, ExceptionUtils.getMessage(e.getCause()));
            return false;
        }
    }

    public static boolean lastKnownReachable(String baseUrl) {
        HealthSnapshot cached = StringUtils.isBlank(baseUrl) ? null : SNAPSHOT_BY_BASE_URL.get(baseUrl);
        return cached != null && cached.reachable();
    }

    public static boolean isReachableNonBlocking(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return false;
        }

        Instant now = Instant.now();
        HealthSnapshot cached = SNAPSHOT_BY_BASE_URL.get(baseUrl);
        if (cached != null && now.isBefore(cached.checkedAt().plus(CACHE_TTL))) {
            return cached.reachable();
        }

        triggerRefresh(baseUrl);
        return cached != null && cached.reachable();
    }

    private static void triggerRefresh(String baseUrl) {
        refreshAsync(baseUrl);
    }

    private static CompletableFuture<Boolean> refreshAsync(String baseUrl) {
        CompletableFuture<Boolean> current = REFRESH_BY_BASE_URL.get(baseUrl);
        if (current != null) {
            return current;
        }

        var created = new CompletableFuture<Boolean>();
        CompletableFuture<Boolean> existing = REFRESH_BY_BASE_URL.putIfAbsent(baseUrl, created);
        if (existing != null) {
            return existing;
        }

        try {
            Thread.startVirtualThread(() -> {
                try {
                    boolean reachable = probe(baseUrl);
                    SNAPSHOT_BY_BASE_URL.put(baseUrl, new HealthSnapshot(reachable, Instant.now()));
                    created.complete(reachable);
                } catch (RuntimeException e) {
                    created.completeExceptionally(e);
                } catch (Error e) {
                    created.completeExceptionally(e);
                    throw e;
                } finally {
                    REFRESH_BY_BASE_URL.remove(baseUrl, created);
                }
            });
        } catch (RuntimeException e) {
            REFRESH_BY_BASE_URL.remove(baseUrl, created);
            created.completeExceptionally(e);
        } catch (Error e) {
            REFRESH_BY_BASE_URL.remove(baseUrl, created);
            created.completeExceptionally(e);
            throw e;
        }
        return created;
    }

    private static boolean probe(String baseUrl) {
        return modelEndpoints(baseUrl).stream()
                .takeWhile(endpoint -> !Thread.currentThread().isInterrupted())
                .anyMatch(LocalServiceHealth::isEndpointReachable);
    }

    private static boolean isEndpointReachable(String endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = HttpClientHolder.INSTANCE.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );
            return response.statusCode() < 500;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Endpoint reachability check interrupted for {}", endpoint);
            return false;
        } catch (Exception e) {
            log.debug("Endpoint reachability check failed for {}: {}", endpoint, ExceptionUtils.getMessage(e));
            return false;
        }
    }

    private static List<String> modelEndpoints(String baseUrl) {
        String primaryEndpoint = modelsEndpoint(baseUrl);
        String alternateBaseUrl = baseUrl.replaceFirst("(?i)://localhost", "://127.0.0.1");
        if (alternateBaseUrl.equals(baseUrl)) {
            return List.of(primaryEndpoint);
        }

        return List.of(primaryEndpoint, modelsEndpoint(alternateBaseUrl));
    }

    private static String modelsEndpoint(String baseUrl) {
        return baseUrl.endsWith("/")
                ? "%smodels".formatted(baseUrl)
                : "%s/models".formatted(baseUrl);
    }

    private static final class HttpClientHolder {
        private static final HttpClient INSTANCE = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    private record HealthSnapshot(boolean reachable, Instant checkedAt) {
    }
}
