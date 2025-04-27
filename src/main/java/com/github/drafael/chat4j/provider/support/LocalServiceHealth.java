package com.github.drafael.chat4j.provider.support;

import javax.swing.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalServiceHealth {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(350);
    private static final Duration SOCKET_TIMEOUT = Duration.ofMillis(120);
    private static final Duration CACHE_TTL = Duration.ofSeconds(2);
    private static final ConcurrentHashMap<String, HealthSnapshot> SNAPSHOT_BY_BASE_URL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> REFRESH_IN_FLIGHT_BY_BASE_URL = new ConcurrentHashMap<>();

    private LocalServiceHealth() {
    }

    public static boolean isReachable(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }

        Instant now = Instant.now();
        HealthSnapshot cached = SNAPSHOT_BY_BASE_URL.get(baseUrl);
        if (cached != null && now.isBefore(cached.checkedAt().plus(CACHE_TTL))) {
            return cached.reachable();
        }

        if (SwingUtilities.isEventDispatchThread()) {
            boolean fastReachable = probeFast(baseUrl);
            if (fastReachable) {
                SNAPSHOT_BY_BASE_URL.put(baseUrl, new HealthSnapshot(true, now));
                triggerRefresh(baseUrl);
                return true;
            }

            if (cached != null) {
                triggerRefresh(baseUrl);
                return cached.reachable();
            }

            triggerRefresh(baseUrl);
            return false;
        }

        boolean reachable = probe(baseUrl);
        SNAPSHOT_BY_BASE_URL.put(baseUrl, new HealthSnapshot(reachable, now));
        return reachable;
    }

    public static boolean isReachableNonBlocking(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }

        Instant now = Instant.now();
        HealthSnapshot cached = SNAPSHOT_BY_BASE_URL.get(baseUrl);
        if (cached != null && now.isBefore(cached.checkedAt().plus(CACHE_TTL))) {
            return cached.reachable();
        }

        triggerRefresh(baseUrl);
        return cached == null || cached.reachable();
    }

    private static boolean probeFast(String baseUrl) {
        return modelEndpoints(baseUrl).stream().anyMatch(LocalServiceHealth::isSocketReachable);
    }

    private static void triggerRefresh(String baseUrl) {
        AtomicBoolean inFlight = REFRESH_IN_FLIGHT_BY_BASE_URL.computeIfAbsent(baseUrl, ignored -> new AtomicBoolean());
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                boolean reachable = probe(baseUrl);
                SNAPSHOT_BY_BASE_URL.put(baseUrl, new HealthSnapshot(reachable, Instant.now()));
            } finally {
                inFlight.set(false);
            }
        });
    }

    private static boolean probe(String baseUrl) {
        return modelEndpoints(baseUrl).stream().anyMatch(LocalServiceHealth::isEndpointReachable);
    }

    private static boolean isSocketReachable(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }

            int port = uri.getPort();
            if (port <= 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), (int) SOCKET_TIMEOUT.toMillis());
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isEndpointReachable(String endpoint) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(REQUEST_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
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
                ? baseUrl + "models"
                : baseUrl + "/models";
    }

    private record HealthSnapshot(boolean reachable, Instant checkedAt) {
    }
}
