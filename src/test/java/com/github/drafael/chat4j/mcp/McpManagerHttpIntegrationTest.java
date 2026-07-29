package com.github.drafael.chat4j.mcp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;

class McpManagerHttpIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEADER_SECRET = "Bearer transport-secret";

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("An enabled Streamable HTTP server is initialized, listed, and invoked")
    void openRun_whenHttpServerIsEnabled_discoversAndInvokesTool() throws Exception {
        AtomicInteger toolCalls = new AtomicInteger();
        HttpServer server = createServer(toolCalls);
        server.start();
        try {
            StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
            var subject = new McpManager(
                    new McpConfigurationRepository(storagePaths.mcpFile()),
                    new McpSecretVault(new ApiTokenVault(storagePaths)),
                    emptyMap(),
                    storagePaths.appConfigDirectory()
            );
            try {
                String endpoint = "http://127.0.0.1:%d/mcp//tools?mode=test"
                        .formatted(server.getAddress().getPort());
                String prefixRowId = UUID.randomUUID().toString();
                String rowId = UUID.randomUUID().toString();
                var configured = new McpServerConfiguration(
                        UUID.randomUUID().toString(),
                        "Test server",
                        "test_server",
                        true,
                        true,
                        McpTransportType.STREAMABLE_HTTP,
                        endpoint,
                        "",
                        emptyList(),
                        List.of(
                                new McpSecretReference(prefixRowId, "X-Prefix", ""),
                                new McpSecretReference(rowId, "Authorization", "")
                        ),
                        emptyList(),
                        false,
                        emptySet()
                );
                Logger logger = (Logger) LoggerFactory.getLogger(McpManager.class);
                Logger sdkLogger = (Logger) LoggerFactory.getLogger("io.modelcontextprotocol");
                Logger schemaLogger = (Logger) LoggerFactory.getLogger("com.networknt.schema");
                var appender = new ListAppender<ILoggingEvent>();
                appender.start();
                logger.addAppender(appender);
                sdkLogger.addAppender(appender);
                schemaLogger.addAppender(appender);
                try {
                    subject.saveAndApply(new McpConfigurationDraft(
                            new McpConfiguration(1, List.of(configured)),
                            Map.of(
                                    prefixRowId, "Bearer".toCharArray(),
                                    rowId, HEADER_SECRET.toCharArray()
                            )
                    )).join();
                    try (McpRunSession run = subject.openRun(() -> false)) {
                        String alias = run.tools().getFirst().name();
                        assertThat(run.redactForDisplay(alias, HEADER_SECRET)).isEqualTo("****");
                        var request = new ToolInvocationRequest("call-1", alias, "{\"value\":\"ok\"}");

                        var result = run.invoke(alias, Map.of("value", "ok"), request, () -> false);
                        var errorRequest = new ToolInvocationRequest("call-2", alias, "{\"error\":true}");
                        var errorResult = run.invoke(alias, Map.of("error", true), errorRequest, () -> false);
                        var emptyErrorRequest = new ToolInvocationRequest("call-3", alias, "{\"emptyError\":true}");
                        var emptyErrorResult = run.invoke(
                                alias,
                                Map.of("emptyError", true),
                                emptyErrorRequest,
                                () -> false
                        );
                        var invalidRequest = new ToolInvocationRequest(
                                "call-4",
                                alias,
                                "{\"invalidStructured\":true}"
                        );
                        var invalidResult = run.invoke(
                                alias,
                                Map.of("invalidStructured", true),
                                invalidRequest,
                                () -> false
                        );

                        assertThat(run.tools()).singleElement().satisfies(tool -> {
                            assertThat(tool.name()).startsWith("mcp_test_server_echo_");
                            assertThat(tool.inputSchema()).containsEntry("type", "object");
                            assertThat(tool.description()).contains("****").doesNotContain("transport-secret");
                        });
                        assertThat(result.success()).isTrue();
                        assertThat(result.output())
                                .contains(
                                        "Text:\nline one\n\t😀 echoed ****",
                                        "x".repeat(40_000),
                                        "Structured JSON:",
                                        "\"value\":\"ok\""
                                )
                                .doesNotContain("transport-secret");
                        assertThat(errorResult.success()).isFalse();
                        assertThat(errorResult.error()).contains("server error", "Structured JSON", "\"value\":42");
                        assertThat(emptyErrorResult.error()).isEqualTo("MCP tool reported an error.");
                        assertThat(invalidResult.error()).isEqualTo("MCP tool returned an invalid result.");
                        assertThat(toolCalls).hasValue(4);
                        assertThat(appender.list)
                                .extracting(ILoggingEvent::getFormattedMessage)
                                .allSatisfy(message -> assertThat(message)
                                        .doesNotContain("transport-secret", "Bearer"));
                    }
                } finally {
                    try {
                        logger.detachAppender(appender);
                    } finally {
                        try {
                            sdkLogger.detachAppender(appender);
                        } finally {
                            try {
                                schemaLogger.detachAppender(appender);
                            } finally {
                                appender.stop();
                            }
                        }
                    }
                }
            } finally {
                subject.close();
            }
        } finally {
            server.stop(0);
        }
    }

    private HttpServer createServer(AtomicInteger toolCalls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp//tools", exchange -> {
            if (!HEADER_SECRET.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = JSON.readTree(requestBody);
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
                        "serverInfo", Map.of("name", "test", "version", "1")
                );
                case "tools/list" -> Map.of("tools", List.of(Map.of(
                        "name", "echo",
                        "description", "Echo a value using %s".formatted(HEADER_SECRET),
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("value", Map.of("type", "string")),
                                "required", List.of("value")
                        ),
                        "outputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("value", Map.of("type", "string")),
                                "required", List.of("value")
                        )
                )));
                case "tools/call" -> {
                    toolCalls.incrementAndGet();
                    boolean error = request.path("params").path("arguments").path("error").asBoolean(false);
                    boolean emptyError = request.path("params").path("arguments").path("emptyError")
                            .asBoolean(false);
                    boolean invalidStructured = request.path("params").path("arguments").path("invalidStructured")
                            .asBoolean(false);
                    if (emptyError) {
                        yield Map.of("content", emptyList(), "isError", true);
                    }
                    if (invalidStructured) {
                        yield Map.of(
                                "content", List.of(Map.of("type", "text", "text", "invalid")),
                                "structuredContent", Map.of("value", 42),
                                "isError", false
                        );
                    }
                    yield error
                            ? Map.of(
                                    "content", List.of(Map.of("type", "text", "text", "server error")),
                                    "structuredContent", Map.of("value", 42),
                                    "isError", true
                            )
                            : Map.of(
                                    "content", List.of(
                                            Map.of(
                                                    "type",
                                                    "text",
                                                    "text",
                                                    "line one\n\t😀 echoed %s".formatted(HEADER_SECRET)
                                            ),
                                            Map.of("type", "text", "text", "x".repeat(40_000))
                                    ),
                                    "structuredContent", Map.of("value", "ok"),
                                    "isError", false
                            );
                }
                default -> throw new IllegalStateException("Unexpected method: %s".formatted(method));
            };
            byte[] response = JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }
}
