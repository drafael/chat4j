package com.github.drafael.chat4j.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpManagerDiscoveryIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("An empty cursor is present and therefore requests the next discovery page")
    void openRun_whenNextCursorIsEmpty_requestsSecondPage() throws Exception {
        var mode = new AtomicReference<>(Mode.EMPTY_CURSOR);
        var listRequests = new AtomicInteger();
        var initializeRequests = new AtomicInteger();
        HttpServer server = server(mode, listRequests, initializeRequests);
        McpManager subject = manager(server);
        try {
            try (McpRunSession run = subject.openRun(() -> false)) {
                assertThat(run.tools()).hasSize(2);
            }
            assertThat(listRequests).hasValue(2);
        } finally {
            try {
                subject.close();
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    @DisplayName("An unsupported negotiated protocol fails once without listing or reinitializing")
    void openRun_whenServerSelectsUnsupportedProtocol_rejectsWithoutBackgroundWork() throws Exception {
        var mode = new AtomicReference<>(Mode.UNSUPPORTED_PROTOCOL);
        var listRequests = new AtomicInteger();
        var initializeRequests = new AtomicInteger();
        HttpServer server = server(mode, listRequests, initializeRequests);
        McpManager subject = manager(server);
        try {
            assertThatThrownBy(() -> subject.openRun(() -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Could not initialize");
            assertThat(initializeRequests).hasValue(1);
            assertThat(listRequests).hasValue(0);
        } finally {
            try {
                subject.close();
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    @DisplayName("A repeated cursor poisons the client and the next run creates a new connection")
    void openRun_whenCursorRepeats_poisonsAndDoesNotReuseClient() throws Exception {
        var mode = new AtomicReference<>(Mode.REPEATED_CURSOR);
        var listRequests = new AtomicInteger();
        var initializeRequests = new AtomicInteger();
        HttpServer server = server(mode, listRequests, initializeRequests);
        McpManager subject = manager(server);
        try {
            assertThatThrownBy(() -> subject.openRun(() -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Could not list tools");
            mode.set(Mode.SINGLE_PAGE);

            try (McpRunSession run = subject.openRun(() -> false)) {
                assertThat(run.tools()).hasSize(1);
            }
            assertThat(initializeRequests).hasValue(2);
        } finally {
            try {
                subject.close();
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    @DisplayName("The page limit is checked before issuing a sixty-fifth list request")
    void openRun_whenCursorNeverTerminates_stopsBeforeNextPageBeyondLimit() throws Exception {
        var mode = new AtomicReference<>(Mode.UNBOUNDED_CURSOR);
        var listRequests = new AtomicInteger();
        var initializeRequests = new AtomicInteger();
        HttpServer server = server(mode, listRequests, initializeRequests);
        McpManager subject = manager(server);
        try {
            assertThatThrownBy(() -> subject.openRun(() -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Could not list tools");
            assertThat(listRequests).hasValue(64);
        } finally {
            try {
                subject.close();
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    @DisplayName("All discovery pages share one absolute operation deadline")
    void listTools_whenFirstPageConsumesDeadline_timesOutSecondPageAgainstOriginalDeadline() throws Exception {
        var clock = new AtomicLong();
        var releaseSecondPage = new CountDownLatch(1);
        var listRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
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
            if ("tools/list".equals(method) && listRequests.getAndIncrement() == 1) {
                clock.set(Duration.ofSeconds(1).toNanos() + 1);
                try {
                    releaseSecondPage.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            Object id = JSON.convertValue(request.path("id"), Object.class);
            Map<String, Object> result;
            if ("initialize".equals(method)) {
                result = Map.of(
                        "protocolVersion", "2025-06-18",
                        "capabilities", Map.of("tools", emptyMap()),
                        "serverInfo", Map.of("name", "deadline-test", "version", "1")
                );
            } else {
                clock.set(Duration.ofSeconds(1).toNanos() - 1);
                result = Map.of(
                        "tools", List.of(Map.of(
                                "name", "first",
                                "inputSchema", Map.of("type", "object", "properties", emptyMap())
                        )),
                        "nextCursor", "next"
                );
            }
            byte[] response = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        McpClientSession subject = null;
        server.start();
        try {
            StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
            var configured = new McpServerConfiguration(
                    UUID.randomUUID().toString(),
                    "Deadline server",
                    "deadline_server",
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
            subject = McpClientSession.connect(
                    configured,
                    new McpSecretVault(new ApiTokenVault(storagePaths)),
                    emptyMap(),
                    storagePaths.appConfigDirectory(),
                    () -> false,
                    ignored -> { },
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    clock::get
            );
            McpClientSession connected = subject;
            assertThatThrownBy(() -> connected.listTools(() -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Could not list tools");
            assertThat(listRequests).hasValue(2);
        } finally {
            releaseSecondPage.countDown();
            try {
                if (subject != null) {
                    subject.close();
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    @DisplayName("Discovery rejects exact configured secrets in tool names and schemas")
    void openRun_whenToolDefinitionContainsTransportSecret_rejectsCatalog() throws Exception {
        var mode = new AtomicReference<>(Mode.SECRET_SCHEMA);
        var listRequests = new AtomicInteger();
        var initializeRequests = new AtomicInteger();
        HttpServer server = server(mode, listRequests, initializeRequests);
        try {
            StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
            var subject = new McpManager(
                    new McpConfigurationRepository(storagePaths.mcpFile()),
                    new McpSecretVault(new ApiTokenVault(storagePaths)),
                    emptyMap(),
                    storagePaths.appConfigDirectory()
            );
            try {
                String rowId = UUID.randomUUID().toString();
                var configured = new McpServerConfiguration(
                        UUID.randomUUID().toString(),
                        "Secret server",
                        "secret_server",
                        true,
                        false,
                        McpTransportType.STREAMABLE_HTTP,
                        "http://127.0.0.1:%d/mcp".formatted(server.getAddress().getPort()),
                        "",
                        emptyList(),
                        List.of(new McpSecretReference(rowId, "Authorization", "")),
                        emptyList(),
                        false,
                        emptySet()
                );
                subject.saveAndApply(new McpConfigurationDraft(
                        new McpConfiguration(1, List.of(configured)),
                        Map.of(rowId, "schema-secret".toCharArray())
                )).join();
                assertThatThrownBy(() -> subject.openRun(() -> false))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Could not list tools")
                        .hasMessageNotContaining("schema-secret");
            } finally {
                subject.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Discovery rejects schemas that declare an unsupported JSON Schema dialect")
    void openRun_whenSchemaDeclaresUnsupportedDialect_rejectsCatalog() throws Exception {
        var mode = new AtomicReference<>(Mode.UNSUPPORTED_DIALECT);
        var listRequests = new AtomicInteger();
        var initializeRequests = new AtomicInteger();
        HttpServer server = server(mode, listRequests, initializeRequests);
        McpManager subject = manager(server);
        try {
            assertThatThrownBy(() -> subject.openRun(() -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Could not list tools");
        } finally {
            try {
                subject.close();
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    @DisplayName("Cumulative discovery description bounds reject a catalog before retaining it")
    void openRun_whenDescriptionsExceedCumulativeBound_rejectsCatalog() throws Exception {
        var mode = new AtomicReference<>(Mode.OVERSIZED_DESCRIPTION);
        var listRequests = new AtomicInteger();
        var initializeRequests = new AtomicInteger();
        HttpServer server = server(mode, listRequests, initializeRequests);
        McpManager subject = manager(server);
        try {
            assertThatThrownBy(() -> subject.openRun(() -> false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Could not list tools");
            assertThat(listRequests).hasValue(1);
        } finally {
            try {
                subject.close();
            } finally {
                server.stop(0);
            }
        }
    }

    private McpManager manager(HttpServer server) {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        var configured = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Discovery server",
                "discovery_server",
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
        try {
            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(configured))
            )).join();
            return subject;
        } catch (RuntimeException | Error e) {
            try {
                subject.close();
            } catch (RuntimeException | Error cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            } finally {
                server.stop(0);
            }
            throw e;
        }
    }

    private HttpServer server(
            AtomicReference<Mode> mode,
            AtomicInteger listRequests,
            AtomicInteger initializeRequests
    ) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
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
                            "protocolVersion", mode.get() == Mode.UNSUPPORTED_PROTOCOL
                                    ? "2024-11-05"
                                    : "2025-06-18",
                            "capabilities", Map.of("tools", emptyMap()),
                            "serverInfo", Map.of("name", "discovery-test", "version", "1")
                    );
                }
                case "tools/list" -> listResult(mode.get(), request, listRequests.getAndIncrement());
                default -> emptyMap();
            };
            byte[] response = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private Map<String, Object> listResult(Mode mode, JsonNode request, int page) {
        String description = mode == Mode.OVERSIZED_DESCRIPTION ? "x".repeat(512 * 1024 + 1) : "Tool";
        Map<String, Object> schema = switch (mode) {
            case UNSUPPORTED_DIALECT -> Map.of(
                    "$schema", "http://json-schema.org/draft-07/schema#",
                    "type", "object",
                    "properties", emptyMap()
            );
            case SECRET_SCHEMA -> Map.of(
                    "type", "object",
                    "properties", Map.of("schema-secret", Map.of("type", "string"))
            );
            default -> Map.of("type", "object", "properties", emptyMap());
        };
        Map<String, Object> tool = Map.of(
                "name", mode == Mode.SECRET_SCHEMA ? "schema-secret" : "tool_%d".formatted(page),
                "description", description,
                "inputSchema", schema
        );
        if (mode == Mode.SINGLE_PAGE || mode == Mode.OVERSIZED_DESCRIPTION
                || mode == Mode.UNSUPPORTED_DIALECT || mode == Mode.SECRET_SCHEMA
                || mode == Mode.EMPTY_CURSOR && request.path("params").has("cursor")) {
            return Map.of("tools", List.of(tool));
        }
        String cursor = switch (mode) {
            case EMPTY_CURSOR, REPEATED_CURSOR -> "";
            case UNBOUNDED_CURSOR -> "cursor_%d".formatted(page);
            default -> throw new IllegalStateException();
        };
        return Map.of("tools", List.of(tool), "nextCursor", cursor);
    }

    private enum Mode {
        EMPTY_CURSOR,
        REPEATED_CURSOR,
        UNBOUNDED_CURSOR,
        OVERSIZED_DESCRIPTION,
        SINGLE_PAGE,
        UNSUPPORTED_PROTOCOL,
        UNSUPPORTED_DIALECT,
        SECRET_SCHEMA
    }
}
