package com.github.drafael.chat4j.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.drafael.chat4j.chat.agent.AgentToolResultLimiter;
import com.github.drafael.chat4j.chat.agent.McpInvocationPermit;
import com.github.drafael.chat4j.chat.agent.ToolInvocationRequest;
import com.github.drafael.chat4j.chat.agent.ToolInvocationResult;
import com.github.drafael.chat4j.chat.render.BoundedUtf8;
import com.github.drafael.chat4j.provider.support.ApiTokenLookup;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import com.github.drafael.chat4j.provider.support.ProcessCommandSupport;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import reactor.core.publisher.Mono;

import static java.lang.Math.min;
import static java.util.Arrays.fill;

final class McpClientSession implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final McpJsonMapper MCP_JSON = new JacksonMcpJsonMapper(JSON);
    private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_TOOLS = 512;
    private static final int MAX_PAGES = 64;
    private static final long MAX_DESCRIPTION_BYTES = 512L * 1024;
    private static final long MAX_SCHEMA_BYTES = 4L * 1024 * 1024;
    private static final long MAX_CATALOG_BYTES = 5L * 1024 * 1024;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

    private final McpServerConfiguration server;
    private final McpAsyncClient client;
    private final Runnable hardClose;
    private final BooleanSupplier transportHealthy;
    private final Duration requestTimeout;
    private final LongSupplier nanoTime;
    private final List<String> secretValues;
    private final AtomicBoolean poisoned = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    private McpClientSession(
            McpServerConfiguration server,
            McpAsyncClient client,
            Runnable hardClose,
            BooleanSupplier transportHealthy,
            Duration requestTimeout,
            LongSupplier nanoTime,
            List<String> secretValues
    ) {
        this.server = server;
        this.client = client;
        this.hardClose = hardClose;
        this.transportHealthy = transportHealthy;
        this.requestTimeout = requestTimeout;
        this.nanoTime = nanoTime;
        this.secretValues = new CopyOnWriteArrayList<>(secretValues.stream()
                .filter(StringUtils::isNotEmpty)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList());
    }

    static McpClientSession connect(
            McpServerConfiguration server,
            McpSecretVault secretVault,
            Map<String, String> subprocessEnvironment,
            Path configurationDirectory,
            BooleanSupplier cancelled,
            Consumer<McpClientSession> candidateReady
    ) {
        return connect(
                server,
                secretVault,
                subprocessEnvironment,
                configurationDirectory,
                cancelled,
                candidateReady,
                INITIALIZE_TIMEOUT,
                REQUEST_TIMEOUT,
                System::nanoTime
        );
    }

    static McpClientSession connect(
            McpServerConfiguration server,
            McpSecretVault secretVault,
            Map<String, String> subprocessEnvironment,
            Path configurationDirectory,
            BooleanSupplier cancelled,
            Consumer<McpClientSession> candidateReady,
            Duration initializeTimeout,
            Duration requestTimeout,
            LongSupplier nanoTime
    ) {
        ResolvedTransport resolved = resolveTransport(server, secretVault, subprocessEnvironment, configurationDirectory);
        McpAsyncClient client;
        try {
            client = McpClient.async(resolved.transport())
                    .clientInfo(new McpSchema.Implementation("Chat4J", applicationVersion()))
                    .capabilities(McpSchema.ClientCapabilities.builder().build())
                    .initializationTimeout(initializeTimeout)
                    .requestTimeout(requestTimeout)
                    .jsonSchemaValidator(new DefaultJsonSchemaValidator(JSON))
                    .enableCallToolSchemaCaching(false)
                    .build();
        } catch (RuntimeException e) {
            try {
                resolved.hardClose().run();
            } catch (RuntimeException ignored) {
            }
            throw new IllegalStateException("Could not create MCP client.");
        }
        McpClientSession session = new McpClientSession(
                server,
                client,
                resolved.hardClose(),
                resolved.transportHealthy(),
                requestTimeout,
                nanoTime,
                resolved.secretValues()
        );
        resolved.transport().setExceptionHandler(error -> {
            if (!session.closeStarted.get()) {
                session.poison(error);
            }
        });
        resolved.outOfBandCompletionRegistration().accept(error -> {
            if (!session.closeStarted.get()) {
                session.poison(error);
            }
        });
        candidateReady.accept(session);
        try {
            McpSchema.InitializeResult result = awaitUntil(
                    client.initialize(),
                    nanoTime.getAsLong() + initializeTimeout.toNanos(),
                    cancelled,
                    nanoTime
            );
            if (result == null || !ProtocolVersions.MCP_2025_06_18.equals(result.protocolVersion())) {
                throw new IllegalStateException("Server selected an unsupported MCP protocol version.");
            }
            if (result.capabilities() == null || result.capabilities().tools() == null) {
                throw new IllegalStateException("Server does not declare the MCP tools capability.");
            }
            return session;
        } catch (Exception e) {
            session.poison(e);
            Throwable root = rootCause(e);
            throw session.failure("Could not initialize (%s)".formatted(root.getClass().getSimpleName()));
        }
    }

    List<McpDiscoveredTool> listTools(BooleanSupplier cancelled) {
        ensureUsable(cancelled);
        List<McpDiscoveredTool> tools = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Set<String> names = new HashSet<>();
        String cursor = null;
        int pages = 0;
        long descriptionBytes = 0;
        long schemaBytes = 0;
        long catalogBytes = 0;
        long deadline = nanoTime.getAsLong() + requestTimeout.toNanos();
        try {
            do {
                ensureUsable(cancelled);
                if (pages >= MAX_PAGES) {
                    throw new IllegalStateException("Server tool catalog exceeds the 64-page limit.");
                }
                McpSchema.ListToolsResult result = awaitUntil(
                        cursor == null ? client.listTools((String) null) : client.listTools(cursor),
                        deadline,
                        cancelled,
                        nanoTime
                );
                if (result == null || result.tools() == null) {
                    throw new IllegalStateException("Server returned no tools/list result.");
                }
                for (McpSchema.Tool tool : result.tools()) {
                    validateTool(tool, names);
                    if (tools.size() >= MAX_TOOLS) {
                        throw new IllegalStateException("Server tool catalog exceeds the 512-tool limit.");
                    }
                    long toolDescriptionBytes = utf8Bytes(tool.title()) + utf8Bytes(tool.description());
                    long toolSchemaBytes = jsonBytes(tool.inputSchema()) + jsonBytes(tool.outputSchema());
                    long toolCatalogBytes = utf8Bytes(tool.name()) + toolDescriptionBytes + toolSchemaBytes;
                    descriptionBytes = addBounded(
                            descriptionBytes,
                            toolDescriptionBytes,
                            MAX_DESCRIPTION_BYTES,
                            "Server tool descriptions exceed the 512 KiB limit."
                    );
                    schemaBytes = addBounded(
                            schemaBytes,
                            toolSchemaBytes,
                            MAX_SCHEMA_BYTES,
                            "Server tool schemas exceed the 4 MiB limit."
                    );
                    catalogBytes = addBounded(
                            catalogBytes,
                            toolCatalogBytes,
                            MAX_CATALOG_BYTES,
                            "Server tool catalog exceeds the 5 MiB limit."
                    );
                    tools.add(new McpDiscoveredTool(
                            tool.name(),
                            redactForDisplay(tool.title()),
                            redactForDisplay(tool.description()),
                            tool.inputSchema(),
                            tool.outputSchema()
                    ));
                }
                pages++;
                cursor = result.nextCursor();
                if (cursor != null && !cursors.add(cursor)) {
                    throw new IllegalStateException("Server repeated a tools/list cursor.");
                }
            } while (cursor != null);
            return List.copyOf(tools);
        } catch (Exception e) {
            poison(e);
            throw failure("Could not list tools");
        }
    }

    ToolInvocationResult call(
            String toolName,
            Map<String, Object> arguments,
            Map<String, Object> outputSchema,
            ToolInvocationRequest request,
            BooleanSupplier cancelled,
            McpInvocationPermit permit
    ) {
        try {
            ensureUsable(cancelled);
            CompletableFuture<McpSchema.CallToolResult> operation = admittedCall(
                    permit,
                    cancelled,
                    () -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments))
            );
            McpSchema.CallToolResult result = awaitUntil(
                    operation,
                    nanoTime.getAsLong() + requestTimeout.toNanos(),
                    cancelled,
                    nanoTime
            );
            ensureUsable(cancelled);
            if (result == null) {
                throw new IllegalStateException("Server returned no tools/call result.");
            }
            String converted = convertResult(result, outputSchema);
            String bounded = AgentToolResultLimiter.limit(converted);
            if (Boolean.TRUE.equals(result.isError())) {
                return ToolInvocationResult.failure(
                        request,
                        StringUtils.defaultIfBlank(bounded, "MCP tool reported an error.")
                );
            }
            return ToolInvocationResult.success(request, bounded);
        } catch (CancellationException e) {
            poison(e);
            return ToolInvocationResult.failure(request, "MCP tool call was cancelled.");
        } catch (TimeoutException e) {
            poison(e);
            return ToolInvocationResult.failure(request, "MCP tool call timed out.");
        } catch (InvalidToolResultException e) {
            poison(e);
            return ToolInvocationResult.failure(request, "MCP tool returned an invalid result.");
        } catch (Exception e) {
            poison(e);
            return ToolInvocationResult.failure(request, "MCP tool call failed.");
        }
    }

    void retryHardClose() {
        hardClose.run();
    }

    String redactForDisplay(String value) {
        return BoundedUtf8.presentation(redactExact(value), 8_192, 32_768);
    }

    boolean isUsable() {
        return !poisoned.get() && !closeStarted.get() && transportHealthy.getAsBoolean();
    }

    @Override
    public void close() {
        if (!closeStarted.compareAndSet(false, true)) {
            awaitCloseCompletion();
            return;
        }
        try {
            try {
                client.closeGracefully().block(Duration.ofSeconds(1));
            } catch (RuntimeException ignored) {
            } finally {
                try {
                    client.close();
                } catch (RuntimeException ignored) {
                }
                try {
                    hardClose.run();
                } finally {
                    secretValues.clear();
                }
            }
            closeCompletion.complete(null);
        } catch (RuntimeException e) {
            closeCompletion.completeExceptionally(e);
            throw e;
        }
    }

    private void poison(Throwable error) {
        poisoned.set(true);
        close();
    }

    private void ensureUsable(BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            var cancellation = new CancellationException("MCP operation cancelled.");
            poison(cancellation);
            throw cancellation;
        }
        if (!isUsable()) {
            throw failure("Client is unavailable");
        }
    }

    private void validateTool(McpSchema.Tool tool, Set<String> names) {
        if (tool == null || StringUtils.isBlank(tool.name()) || !names.add(tool.name())) {
            throw new IllegalStateException("Server returned a blank or duplicate tool name.");
        }
        secretValues.forEach(secret -> {
            if (tool.name().contains(secret) || containsSecret(tool.inputSchema(), secret)
                    || containsSecret(tool.outputSchema(), secret)) {
                throw new IllegalStateException("Server tool schema contains a configured credential value.");
            }
        });
        validateObjectSchema(tool.inputSchema(), "input");
        if (ObjectUtils.isNotEmpty(tool.outputSchema())) {
            validateObjectSchema(tool.outputSchema(), "output");
        }
    }

    private void validateObjectSchema(Map<String, Object> schema, String label) {
        if (schema == null || !"object".equals(schema.get("type"))) {
            throw new IllegalStateException("MCP tool %s schema must have top-level type object.".formatted(label));
        }
        Object dialect = schema.get("$schema");
        if (dialect != null && !McpSchema.JSON_SCHEMA_DIALECT_2020_12.equals(dialect.toString())) {
            throw new IllegalStateException("MCP tool %s schema declares an unsupported dialect.".formatted(label));
        }
        JsonSchemaValidator.ValidationResponse validation = new DefaultJsonSchemaValidator(JSON).validateSchema(schema);
        if (!validation.valid()) {
            throw new IllegalStateException("MCP tool %s schema is invalid.".formatted(label));
        }
    }

    private String convertResult(McpSchema.CallToolResult result, Map<String, Object> outputSchema) throws Exception {
        if (result.content() == null) {
            throw new InvalidToolResultException();
        }
        List<String> text = result.content().stream()
                .map(content -> content instanceof McpSchema.TextContent value
                        ? redactExact(value.text())
                        : "[unsupported MCP content: %s]".formatted(content.type()))
                .toList();
        Object structured = result.structuredContent();
        String structuredText = "";
        if (structured != null) {
            if (!(structured instanceof Map<?, ?>)) {
                throw new InvalidToolResultException();
            }
            if (!Boolean.TRUE.equals(result.isError()) && ObjectUtils.isNotEmpty(outputSchema)) {
                JsonSchemaValidator.ValidationResponse validation = new DefaultJsonSchemaValidator(JSON).validate(outputSchema, structured);
                if (!validation.valid()) {
                    throw new InvalidToolResultException();
                }
            }
            structuredText = redactExact(JSON.writeValueAsString(structured));
        } else if (ObjectUtils.isNotEmpty(outputSchema) && !Boolean.TRUE.equals(result.isError())) {
            throw new InvalidToolResultException();
        }
        String textValue = String.join("\n", text);
        if (StringUtils.isNotEmpty(textValue) && StringUtils.isNotEmpty(structuredText)) {
            return "Text:\n%s\n\nStructured JSON:\n%s".formatted(textValue, structuredText);
        }
        return StringUtils.isEmpty(textValue) ? structuredText : textValue;
    }

    private String redactExact(String value) {
        String result = StringUtils.defaultString(value);
        for (String secret : secretValues) {
            if (StringUtils.isNotEmpty(secret)) {
                result = result.replace(secret, "****");
            }
        }
        return result;
    }

    private long addBounded(long current, long addition, long limit, String message) {
        if (addition > limit - current) {
            throw new IllegalStateException(message);
        }
        return current + addition;
    }

    private long utf8Bytes(String value) {
        return StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private long jsonBytes(Object value) throws Exception {
        return value == null ? 0 : JSON.writeValueAsBytes(value).length;
    }

    private void awaitCloseCompletion() {
        try {
            closeCompletion.get(CLOSE_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            throw new IllegalStateException("MCP client cleanup did not settle.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("MCP client cleanup failed.", e.getCause());
        }
    }

    private boolean containsSecret(Object value, String secret) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().anyMatch(entry -> containsSecret(entry.getKey(), secret)
                    || containsSecret(entry.getValue(), secret));
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(item -> containsSecret(item, secret));
        }
        return value instanceof String string && string.contains(secret);
    }

    static <T> CompletableFuture<T> admittedCall(
            McpInvocationPermit permit,
            BooleanSupplier cancelled,
            Supplier<Mono<T>> operation
    ) {
        return permit.admit(cancelled, () -> operation.get().toFuture());
    }

    static <T> T awaitUntil(
            Mono<T> operation,
            long deadline,
            BooleanSupplier cancelled,
            LongSupplier nanoTime
    ) throws Exception {
        if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
            throw new CancellationException("MCP operation cancelled.");
        }
        if (deadline - nanoTime.getAsLong() <= 0) {
            throw new TimeoutException("MCP operation timed out.");
        }
        return awaitUntil(operation.toFuture(), deadline, cancelled, nanoTime);
    }

    private static <T> T awaitUntil(
            CompletableFuture<T> future,
            long deadline,
            BooleanSupplier cancelled,
            LongSupplier nanoTime
    ) throws Exception {
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted() || cancelled != null && cancelled.getAsBoolean()) {
                    future.cancel(true);
                    throw new CancellationException("MCP operation cancelled.");
                }
                long remaining = deadline - nanoTime.getAsLong();
                if (remaining <= 0) {
                    future.cancel(true);
                    throw new TimeoutException("MCP operation timed out.");
                }
                try {
                    return future.get(min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException("MCP operation failed.");
                }
            }
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static String applicationVersion() {
        return StringUtils.defaultIfBlank(
                McpClientSession.class.getPackage().getImplementationVersion(),
                "development"
        );
    }

    private static Throwable rootCause(Throwable error) {
        Throwable result = error;
        while (result.getCause() != null && result.getCause() != result) {
            result = result.getCause();
        }
        return result;
    }

    private IllegalStateException failure(String action) {
        return new IllegalStateException(
                "%s MCP server %s.".formatted(action, redactForDisplay(server.displayName()))
        );
    }

    private static ResolvedTransport resolveTransport(
            McpServerConfiguration server,
            McpSecretVault secretVault,
            Map<String, String> subprocessEnvironment,
            Path configurationDirectory
    ) {
        Map<String, String> secretMap = new LinkedHashMap<>();
        List<String> secretValues = new ArrayList<>();
        List<McpSecretReference> references = server.transport() == McpTransportType.STDIO
                ? server.environment()
                : server.headers();
        references.forEach(reference -> {
            try (ApiTokenLookup lookup = secretVault.lookup(reference.secretId())) {
                if (!lookup.present()) {
                    throw new IllegalStateException("An MCP credential is missing.");
                }
                char[] characters = lookup.token();
                String value;
                try {
                    value = new String(characters);
                } finally {
                    fill(characters, '\0');
                }
                if (server.transport() == McpTransportType.STREAMABLE_HTTP
                        && (value.contains("\r") || value.contains("\n"))) {
                    throw new IllegalStateException("MCP HTTP header credential contains a line break.");
                }
                secretMap.put(reference.key(), value);
                secretValues.add(value);
            }
        });
        if (server.transport() == McpTransportType.STDIO) {
            Map<String, String> environment = safeEnvironment(subprocessEnvironment);
            environment.putAll(secretMap);
            List<String> command = new ArrayList<>();
            command.add(server.executable());
            command.addAll(server.arguments());
            ProcessBuilder resolver = new ProcessBuilder(command);
            ProcessCommandSupport.applyEnvironment(resolver, environment);
            String resolvedExecutable = resolver.command().getFirst().toLowerCase(Locale.ROOT);
            if (resolvedExecutable.endsWith(".cmd") || resolvedExecutable.endsWith(".bat")) {
                throw new IllegalStateException(
                        "Windows MCP command scripts require a native interpreter such as node.exe."
                );
            }
            Path workingDirectory = stableWorkingDirectory(configurationDirectory);
            Chat4jStdioClientTransport transport = new Chat4jStdioClientTransport(
                    resolver.command(),
                    environment,
                    workingDirectory,
                    MCP_JSON
            );
            return new ResolvedTransport(
                    new FilteringMcpClientTransport(transport),
                    transport::retryCleanup,
                    transport::isAlive,
                    ignored -> { },
                    secretValues
            );
        }

        URI endpoint = URI.create(server.endpoint());
        String base = originBase(endpoint);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        var httpClientBuilder = new BoundedMcpHttpClientBuilder();
        httpClientBuilder.connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                .clientBuilder(httpClientBuilder)
                .requestBuilder(requestBuilder)
                .endpoint(endpoint.toString())
                .resumableStreams(false)
                .openConnectionOnStartup(false)
                .supportedProtocolVersions(List.of(ProtocolVersions.MCP_2025_06_18))
                .httpRequestCustomizer((builder, method, requestUri, body, context) -> {
                    if (!sameOrigin(endpoint, requestUri)) {
                        throw new IllegalStateException("MCP request escaped its configured origin.");
                    }
                    secretMap.forEach(builder::header);
                })
                .jsonMapper(MCP_JSON)
                .build();
        return new ResolvedTransport(
                new FilteringMcpClientTransport(transport),
                () -> {
                    BoundedMcpHttpClientBuilder.TrackedHttpClient httpClient = httpClientBuilder.builtClient();
                    if (httpClient != null) {
                        httpClient.shutdownNow();
                    }
                    secretMap.clear();
                },
                () -> {
                    BoundedMcpHttpClientBuilder.TrackedHttpClient httpClient = httpClientBuilder.builtClient();
                    return httpClient == null || !httpClient.isTerminated();
                },
                httpClientBuilder::onOutOfBandStreamCompletion,
                secretValues
        );
    }

    private static Map<String, String> safeEnvironment(Map<String, String> source) {
        Set<String> allowed = Set.of(
                "PATH",
                "PATHEXT",
                "HOME",
                "USERPROFILE",
                "TMP",
                "TEMP",
                "SYSTEMROOT",
                "WINDIR",
                "COMSPEC",
                "LANG",
                "LC_ALL",
                "USER",
                "LOGNAME"
        );
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (allowed.stream().anyMatch(name -> Strings.CI.equals(name, key))) {
                result.put(key, value);
            }
        });
        return result;
    }

    private static Path stableWorkingDirectory(Path configurationDirectory) {
        String home = System.getProperty("user.home");
        if (StringUtils.isNotBlank(home) && Files.isDirectory(Path.of(home))) {
            return Path.of(home);
        }
        try {
            Files.createDirectories(configurationDirectory);
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare the MCP working directory.", e);
        }
        return configurationDirectory;
    }

    static String originBase(URI endpoint) {
        try {
            return new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), null, null, null)
                    .toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("MCP endpoint origin is invalid.");
        }
    }

    private static boolean sameOrigin(URI expected, URI actual) {
        return Strings.CI.equals(expected.getScheme(), actual.getScheme())
                && Strings.CI.equals(expected.getHost(), actual.getHost())
                && effectivePort(expected) == effectivePort(actual);
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : defaultPort(uri.getScheme());
    }

    private static int defaultPort(String scheme) {
        return Strings.CI.equals("https", scheme) ? 443 : 80;
    }

    private static final class InvalidToolResultException extends Exception {
    }

    private record ResolvedTransport(
            McpClientTransport transport,
            Runnable hardClose,
            BooleanSupplier transportHealthy,
            Consumer<Consumer<Throwable>> outOfBandCompletionRegistration,
            List<String> secretValues
    ) {
        @Override
        public String toString() {
            return "ResolvedTransport[transport=%s, secretValues=****]".formatted(transport);
        }
    }
}
