package com.github.drafael.chat4j.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.chat.agent.ToolInvocationRequest;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;

class McpClientSessionHttpCloseIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Session close awaits a successful DELETE exactly once")
    void close_whenDeleteReturnsSuccess_awaitsSingleDelete() throws Exception {
        runCloseScenario(204, false);
    }

    @Test
    @DisplayName("Session close observes a rejected DELETE and still hard-closes exactly once")
    void close_whenDeleteReturnsServerError_settlesSingleDelete() throws Exception {
        runCloseScenario(500, false);
    }

    @Test
    @DisplayName("Session close bounds a nonresponsive DELETE before hard close")
    void close_whenDeleteDoesNotRespond_cancelsSingleDeleteWithinCloseBudget() throws Exception {
        runCloseScenario(204, true);
    }

    @Test
    @DisplayName("Clean out-of-band SSE EOF poisons the session without reconnect or tool replay")
    void openRun_whenOutOfBandSseEnds_poisonsSessionWithoutReconnect() throws Exception {
        var releaseSse = new CountDownLatch(1);
        var deleteReceived = new CountDownLatch(1);
        var getRequests = new AtomicInteger();
        var initializeRequests = new AtomicInteger();
        var toolCalls = new AtomicInteger();
        var serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverExecutor);
            server.createContext("/mcp", exchange -> {
                if ("GET".equals(exchange.getRequestMethod())) {
                    getRequests.incrementAndGet();
                    try {
                        releaseSse.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] body = "event: message\ndata: \n\n".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                    return;
                }
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    deleteReceived.countDown();
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }
                JsonNode request = JSON.readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                if ("notifications/initialized".equals(method)) {
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                    return;
                }
                Object id = JSON.convertValue(request.path("id"), Object.class);
                Map<String, Object> result = switch (method) {
                    case "initialize" -> {
                        initializeRequests.incrementAndGet();
                        yield Map.of(
                                "protocolVersion", "2025-06-18",
                                "capabilities", Map.of("tools", emptyMap()),
                                "serverInfo", Map.of("name", "sse-test", "version", "1")
                        );
                    }
                    case "tools/list" -> Map.of("tools", List.of(Map.of(
                                "name", "echo",
                                "inputSchema", Map.of("type", "object", "properties", emptyMap())
                        )));
                    case "tools/call" -> {
                        toolCalls.incrementAndGet();
                        yield Map.of("content", emptyList(), "isError", false);
                    }
                    default -> emptyMap();
                };
                byte[] response = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if ("initialize".equals(method)) {
                    exchange.getResponseHeaders().set("Mcp-Session-Id", "sse-session");
                }
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            try {
                StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
                var manager = new McpManager(
                        new McpConfigurationRepository(storagePaths.mcpFile()),
                        new McpSecretVault(new ApiTokenVault(storagePaths)),
                        emptyMap(),
                        storagePaths.appConfigDirectory()
                );
                try {
                    var configured = new McpServerConfiguration(
                            UUID.randomUUID().toString(), "SSE", "sse_server", true, false,
                            McpTransportType.STREAMABLE_HTTP,
                            "http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()),
                            "", emptyList(), emptyList(), emptyList(), false, emptySet()
                    );
                    manager.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                            new McpConfiguration(1, List.of(configured))
                    )).join();
                    try (McpRunSession run = manager.openRun(() -> false)) {
                        releaseSse.countDown();
                        assertThat(deleteReceived.await(5, TimeUnit.SECONDS)).isTrue();
                        String alias = run.tools().getFirst().name();
                        var request = new ToolInvocationRequest("call", alias, "{}");

                        assertThat(run.invoke(alias, emptyMap(), request, () -> false).success()).isFalse();
                        assertThat(getRequests).hasValue(1);
                        assertThat(initializeRequests).hasValue(1);
                        assertThat(toolCalls).hasValue(0);
                    }
                } finally {
                    releaseSse.countDown();
                    manager.close();
                }
            } finally {
                server.stop(0);
            }
        } finally {
            serverExecutor.close();
        }
    }

    private void runCloseScenario(int deleteStatus, boolean blockDelete) throws Exception {
        var deleteRequests = new AtomicInteger();
        var deleteReceived = new CountDownLatch(1);
        var releaseDelete = new CountDownLatch(1);
        HttpServer server = server(deleteStatus, blockDelete, deleteRequests, deleteReceived, releaseDelete);
        try {
            StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
            var manager = new McpManager(
                    new McpConfigurationRepository(storagePaths.mcpFile()),
                    new McpSecretVault(new ApiTokenVault(storagePaths)),
                    emptyMap(),
                    storagePaths.appConfigDirectory()
            );
            CompletableFuture<Void> close = null;
            McpRunSession run = null;
            try {
                var configured = new McpServerConfiguration(
                        UUID.randomUUID().toString(),
                        "HTTP close server",
                        "http_close_server",
                        true,
                        false,
                        McpTransportType.STREAMABLE_HTTP,
                        "http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()),
                        "",
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        false,
                        emptySet()
                );
                manager.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                        new McpConfiguration(1, List.of(configured))
                )).join();
                run = manager.openRun(() -> false);
                McpRunSession activeRun = run;
                close = CompletableFuture.runAsync(activeRun::close);
                assertThat(deleteReceived.await(5, TimeUnit.SECONDS)).isTrue();
                close.get(3, TimeUnit.SECONDS);
                assertThat(deleteRequests).hasValue(1);
            } finally {
                releaseDelete.countDown();
                try {
                    if (close != null) {
                        close.get(5, TimeUnit.SECONDS);
                    } else if (run != null) {
                        run.close();
                    }
                } finally {
                    manager.close();
                }
            }
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(
            int deleteStatus,
            boolean blockDelete,
            AtomicInteger deleteRequests,
            CountDownLatch deleteReceived,
            CountDownLatch releaseDelete
    ) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleteRequests.incrementAndGet();
                deleteReceived.countDown();
                if (blockDelete) {
                    try {
                        releaseDelete.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.sendResponseHeaders(deleteStatus, -1);
                exchange.close();
                return;
            }
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            Object id = JSON.convertValue(request.path("id"), Object.class);
            Map<String, Object> result = switch (method) {
                case "initialize" -> Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", emptyMap()),
                        "serverInfo", Map.of("name", "close-test", "version", "1")
                );
                case "tools/list" -> Map.of("tools", emptyList());
                default -> emptyMap();
            };
            byte[] response = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", "session-one");
            }
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }
}
