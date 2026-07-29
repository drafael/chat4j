package com.github.drafael.chat4j.mcp;

import com.github.drafael.chat4j.chat.agent.AgentToolDefinition;
import com.github.drafael.chat4j.chat.agent.AgentToolSource;
import com.github.drafael.chat4j.chat.agent.LocalAgentToolCatalog;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import static java.lang.Math.min;
import static java.util.Arrays.copyOf;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

@Slf4j
public final class McpManager implements McpRunProvider, AutoCloseable {

    private static final Pattern ALIAS_CHARACTERS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final int MAX_ALIAS_LENGTH = 64;
    private static final Duration PUBLICATION_SETTLEMENT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration CONNECTION_SETTLEMENT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration RUNTIME_SHUTDOWN_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CLOSE_SETTLEMENT_TIMEOUT = Duration.ofSeconds(6);

    private final McpConfigurationRepository repository;
    private final McpSecretVault secretVault;
    private final Map<String, String> subprocessEnvironment;
    private final Path configurationDirectory;
    private final ExecutorService publicationExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("chat4j-mcp-publication", 0).factory()
    );
    private final Object lock = new Object();
    private final ReentrantReadWriteLock configurationLock = new ReentrantReadWriteLock(true);
    private final Map<String, RetainedClient> retainedClients = new HashMap<>();
    private final Set<McpClientSession> allClients = new HashSet<>();
    private final Set<ConnectionAttempt> connectionAttempts = new HashSet<>();
    private final Set<String> pendingOrphanSecretIds = new HashSet<>();
    private final Set<McpClientSession> pendingClientCleanup = new HashSet<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private final AtomicBoolean runtimeShutdownStarted = new AtomicBoolean();
    private final CompletableFuture<Void> runtimeShutdownCompletion = new CompletableFuture<>();
    private McpConfigurationLoadResult loadResult;
    private McpConfiguration configuration;
    private long generation;
    private boolean runtimeAdmissionsStopped;
    private boolean writesSealed;
    private volatile boolean publicationSettlementProvenOnClose;
    private volatile IllegalStateException closeFailure;

    public McpManager(
            @NonNull McpConfigurationRepository repository,
            @NonNull McpSecretVault secretVault,
            @NonNull Map<String, String> subprocessEnvironment,
            @NonNull Path configurationDirectory
    ) {
        this.repository = repository;
        this.secretVault = secretVault;
        this.subprocessEnvironment = Map.copyOf(subprocessEnvironment);
        this.configurationDirectory = configurationDirectory;
        this.loadResult = repository.load();
        this.configuration = switch (loadResult) {
            case McpConfigurationLoadResult.Missing missing -> missing.configuration();
            case McpConfigurationLoadResult.Valid valid -> valid.configuration();
            case McpConfigurationLoadResult.Invalid ignored -> McpConfiguration.empty();
        };
        if (loadResult instanceof McpConfigurationLoadResult.Valid) {
            cleanupStartupOrphans();
        }
    }

    public McpConfigurationLoadResult loadResult() {
        synchronized (lock) {
            return loadResult;
        }
    }

    public long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    public String cleanupStatus() {
        synchronized (lock) {
            if (!pendingClientCleanup.isEmpty()) {
                return "MCP connection cleanup is pending.";
            }
            return pendingOrphanSecretIds.isEmpty() ? "" : "Encrypted MCP credential cleanup is pending.";
        }
    }

    public CompletableFuture<McpApplyResult> saveAndApply(@NonNull McpConfigurationDraft source) {
        return submitPublication(source, false);
    }

    public CompletableFuture<McpApplyResult> replaceInvalidAndApply(@NonNull McpConfigurationDraft source) {
        return submitPublication(source, true);
    }

    public CompletableFuture<McpVerificationResult> saveAndVerify(
            @NonNull McpConfigurationDraft source,
            String serverId,
            @NonNull BooleanSupplier cancelled
    ) {
        return saveAndApply(source).thenCompose(result -> verifyAppliedAsync(result, serverId, cancelled));
    }

    public CompletableFuture<McpVerificationResult> replaceInvalidAndVerify(
            @NonNull McpConfigurationDraft source,
            String serverId,
            @NonNull BooleanSupplier cancelled
    ) {
        return replaceInvalidAndApply(source).thenCompose(result -> verifyAppliedAsync(result, serverId, cancelled));
    }

    public CompletableFuture<McpVerificationResult> verifyAppliedAsync(
            @NonNull McpApplyResult result,
            String serverId,
            @NonNull BooleanSupplier cancelled
    ) {
        return CompletableFuture.supplyAsync(() -> verifyAppliedSafely(result, serverId, cancelled));
    }

    @Override
    public McpRunSession openRun(@NonNull BooleanSupplier cancelled) {
        configurationLock.readLock().lock();
        try {
            return openRunLocked(cancelled);
        } finally {
            configurationLock.readLock().unlock();
        }
    }

    private McpRunSession openRunLocked(BooleanSupplier cancelled) {
        McpConfiguration snapshot;
        synchronized (lock) {
            if (runtimeAdmissionsStopped || closed.get()) {
                throw new IllegalStateException("MCP runtime is shutting down.");
            }
            snapshot = configuration;
        }

        List<McpRunSession.ClientLease> leases = new ArrayList<>();
        List<AgentToolDefinition> tools = new ArrayList<>();
        Map<String, McpToolRoute> routes = new LinkedHashMap<>();
        Set<String> aliases = LocalAgentToolCatalog.definitions().stream()
                .map(AgentToolDefinition::name)
                .collect(toSet());
        try {
            snapshot.servers().stream().filter(McpServerConfiguration::enabled).forEach(server -> {
                McpRunSession.ClientLease lease = null;
                List<McpDiscoveredTool> discovered;
                try {
                    lease = acquireClient(server, cancelled);
                    discovered = lease.client().listTools(cancelled);
                } catch (RuntimeException e) {
                    var failure = new IllegalStateException(
                            "%s Verify or repair enabled MCP server %s in MCP Settings, or disable it before running Agent Mode."
                                    .formatted(
                                            StringUtils.defaultIfBlank(
                                                    e.getMessage(),
                                                    "Could not initialize the enabled MCP server."
                                            ),
                                            server.displayName()
                                    ),
                            e
                    );
                    if (lease != null) {
                        try {
                            lease.close();
                        } catch (RuntimeException cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                    throw failure;
                }
                McpRunSession.ClientLease acquiredLease = lease;
                leases.add(acquiredLease);
                discovered.stream()
                        .filter(tool -> !server.disabledTools().contains(tool.name()))
                        .forEach(tool -> {
                            String alias = alias(server, tool.name());
                            if (!aliases.add(alias)) {
                                throw new IllegalStateException("MCP tool aliases are not unique.");
                            }
                            String description = "[%s] %s".formatted(
                                    acquiredLease.client().redactForDisplay(server.displayName()),
                                    StringUtils.defaultString(tool.description())
                            );
                            AgentToolDefinition definition = new AgentToolDefinition(
                                    alias,
                                    description,
                                    tool.inputSchema(),
                                    AgentToolSource.MCP
                            );
                            tools.add(definition);
                            routes.put(alias, new McpToolRoute(
                                    server,
                                    tool.name(),
                                    tool.outputSchema(),
                                    acquiredLease.client()
                            ));
                        });
            });
            return new McpRunSession(tools, routes, leases);
        } catch (Exception e) {
            leases.reversed().forEach(McpRunSession.ClientLease::close);
            throw e;
        }
    }

    public void stopRuntimeAdmissions() {
        synchronized (lock) {
            runtimeAdmissionsStopped = true;
        }
    }

    public void beginRuntimeShutdown() {
        if (!runtimeShutdownStarted.compareAndSet(false, true)) {
            awaitRuntimeShutdown();
            return;
        }
        try {
            List<McpClientSession> clients;
            List<ConnectionAttempt> attempts;
            synchronized (lock) {
                runtimeAdmissionsStopped = true;
                clients = List.copyOf(allClients);
                attempts = List.copyOf(connectionAttempts);
                allClients.clear();
                retainedClients.clear();
            }
            attempts.forEach(ConnectionAttempt::cancelAndClose);
            log.info("MCP runtime shutdown started (clients={}, connecting={})", clients.size(), attempts.size());
            try {
                closeClients(clients);
            } finally {
                retryPendingClientCleanup();
                awaitConnectionAttempts(attempts);
                synchronized (lock) {
                    if (!pendingClientCleanup.isEmpty()) {
                        throw new IllegalStateException("MCP connection cleanup remains pending.");
                    }
                }
            }
            runtimeShutdownCompletion.complete(null);
        } catch (RuntimeException e) {
            runtimeShutdownCompletion.completeExceptionally(e);
            throw e;
        }
    }

    public void sealWrites() {
        synchronized (lock) {
            writesSealed = true;
        }
    }

    public CompletableFuture<Void> publicationsSettled() {
        return CompletableFuture.runAsync(() -> { }, publicationExecutor);
    }

    public boolean publicationSettlementProvenOnClose() {
        return publicationSettlementProvenOnClose;
    }

    private boolean awaitPublicationSettlement() {
        try {
            publicationsSettled().get(PUBLICATION_SETTLEMENT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException | ExecutionException | RejectedExecutionException e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            awaitManagerClose();
            if (closeFailure != null) {
                throw closeFailure;
            }
            return;
        }
        try {
            stopRuntimeAdmissions();
            sealWrites();
            boolean publicationsFinished = awaitPublicationSettlement();
            if (publicationsFinished) {
                publicationSettlementProvenOnClose = true;
            }
            RuntimeException runtimeFailure = null;
            try {
                beginRuntimeShutdown();
            } catch (RuntimeException e) {
                runtimeFailure = e;
            } finally {
                publicationExecutor.shutdownNow();
            }
            if (!publicationsFinished) {
                closeFailure = new IllegalStateException(
                        "MCP configuration publication did not settle before shutdown."
                );
                throw closeFailure;
            }
            if (runtimeFailure != null) {
                closeFailure = new IllegalStateException("MCP runtime cleanup failed.");
                throw closeFailure;
            }
        } finally {
            closeCompletion.complete(null);
        }
    }

    private CompletableFuture<McpApplyResult> submitPublication(McpConfigurationDraft source, boolean replaceInvalid) {
        McpConfigurationDraft owned = source.copy();
        source.clearSecrets();
        synchronized (lock) {
            if (writesSealed || closed.get()) {
                owned.clearSecrets();
                return CompletableFuture.completedFuture(rejected("MCP configuration writes are closed."));
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return apply(owned, replaceInvalid);
                } finally {
                    owned.clearSecrets();
                }
            }, publicationExecutor);
        }
    }

    private McpApplyResult apply(McpConfigurationDraft draft, boolean replaceInvalid) {
        configurationLock.writeLock().lock();
        try {
            return applyLocked(draft, replaceInvalid);
        } finally {
            configurationLock.writeLock().unlock();
        }
    }

    private McpApplyResult applyLocked(McpConfigurationDraft draft, boolean replaceInvalid) {
        synchronized (lock) {
            boolean invalid = loadResult instanceof McpConfigurationLoadResult.Invalid;
            if (invalid && !replaceInvalid || !invalid && replaceInvalid) {
                return rejected(invalid
                        ? "Confirm replacement of the invalid MCP configuration first."
                        : "MCP configuration is not in repair mode.");
            }
        }

        retryPendingClientCleanup();
        PreparedPublication prepared;
        try {
            McpConfigurationValidator.validate(draft.configuration());
            prepared = prepare(draft);
        } catch (Exception e) {
            return rejected(e.getMessage());
        }

        try {
            secretVault.publish(prepared.newSecrets(), emptySet());
        } catch (Exception e) {
            clearSecretMap(prepared.newSecrets());
            return rejected("Could not save encrypted MCP credentials.");
        }

        try {
            repository.save(prepared.configuration());
        } catch (Exception e) {
            try {
                secretVault.remove(prepared.newSecrets().keySet());
                clearSecretMap(prepared.newSecrets());
                return rejected("Could not save MCP configuration.");
            } catch (Exception cleanupFailure) {
                synchronized (lock) {
                    pendingOrphanSecretIds.addAll(prepared.newSecrets().keySet());
                }
                clearSecretMap(prepared.newSecrets());
                return new McpApplyResult(
                        McpApplyOutcome.REJECTED_ORPHAN_CLEANUP_PENDING,
                        generation(),
                        currentConfiguration(),
                        "Configuration was not changed; encrypted orphan cleanup is pending."
                );
            }
        }
        clearSecretMap(prepared.newSecrets());

        McpConfiguration previous;
        long publishedGeneration;
        List<McpClientSession> clientsToClose;
        synchronized (lock) {
            previous = configuration;
            configuration = prepared.configuration();
            loadResult = new McpConfigurationLoadResult.Valid(configuration);
            publishedGeneration = ++generation;
            clientsToClose = retireChangedClients(configuration);
        }
        boolean clientCleanupPending = !closeRetiredClients(clientsToClose);
        synchronized (lock) {
            clientCleanupPending = clientCleanupPending || !pendingClientCleanup.isEmpty();
        }

        McpApplyOutcome outcome = clientCleanupPending
                ? McpApplyOutcome.APPLIED_CLEANUP_PENDING
                : McpApplyOutcome.APPLIED;
        String message = clientCleanupPending
                ? "MCP configuration was applied; connection cleanup is pending."
                : "";
        Set<String> obsolete = referencedSecretIds(previous);
        obsolete.removeAll(referencedSecretIds(prepared.configuration()));
        obsolete.addAll(pendingOrphanSecretIds);
        try {
            if (!obsolete.isEmpty()) {
                secretVault.remove(obsolete);
            }
            synchronized (lock) {
                pendingOrphanSecretIds.removeAll(obsolete);
            }
        } catch (Exception e) {
            synchronized (lock) {
                pendingOrphanSecretIds.addAll(obsolete);
            }
            outcome = McpApplyOutcome.APPLIED_CLEANUP_PENDING;
            message = clientCleanupPending
                    ? "MCP configuration was applied; connection and credential cleanup are pending."
                    : "MCP configuration was applied; credential cleanup is pending.";
        }
        return new McpApplyResult(outcome, publishedGeneration, prepared.configuration(), message);
    }

    private McpVerificationResult verifyAppliedSafely(
            @NonNull McpApplyResult result,
            String serverId,
            @NonNull BooleanSupplier cancelled
    ) {
        try {
            return verifyApplied(result, serverId, cancelled);
        } catch (RuntimeException e) {
            return McpVerificationResult.failed(result, serverId, e.getMessage());
        }
    }

    private McpVerificationResult verifyApplied(
            @NonNull McpApplyResult result,
            String serverId,
            @NonNull BooleanSupplier cancelled
    ) {
        configurationLock.readLock().lock();
        try {
            return verifyAppliedLocked(result, serverId, cancelled);
        } finally {
            configurationLock.readLock().unlock();
        }
    }

    private McpVerificationResult verifyAppliedLocked(
            @NonNull McpApplyResult result,
            String serverId,
            @NonNull BooleanSupplier cancelled
    ) {
        if (!result.outcome().applied()) {
            return McpVerificationResult.failed(result, serverId, result.message());
        }
        synchronized (lock) {
            if (generation != result.generation()) {
                throw new IllegalStateException("MCP configuration changed before verification started.");
            }
        }
        McpServerConfiguration server = result.configuration().servers().stream()
                .filter(candidate -> candidate.id().equals(serverId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MCP server no longer exists."));
        McpRunSession.ClientLease lease = acquireClient(server, cancelled);
        try {
            return McpVerificationResult.successful(result, serverId, lease.client().listTools(cancelled));
        } finally {
            lease.close();
        }
    }

    private McpRunSession.ClientLease acquireClient(
            McpServerConfiguration server,
            BooleanSupplier cancelled
    ) {
        if (!server.longRunning() || server.transport() != McpTransportType.STDIO) {
            return temporaryLease(connect(server, cancelled));
        }
        synchronized (lock) {
            RetainedClient retained = retainedClients.get(server.id());
            if (retained != null && !retained.busy() && !retained.retired()
                    && retained.connectionIdentity().equals(connectionIdentity(server)) && retained.client().isUsable()) {
                retained.busy(true);
                return retainedLease(retained);
            }
        }
        McpClientSession candidate = connect(server, cancelled);
        RetainedClient promoted = null;
        McpClientSession replacedClient = null;
        synchronized (lock) {
            RetainedClient current = retainedClients.get(server.id());
            if (current == null || current.retired() || !current.client().isUsable()) {
                promoted = new RetainedClient(candidate, connectionIdentity(server), true, false);
                retainedClients.put(server.id(), promoted);
                if (current != null && !current.busy()) {
                    replacedClient = current.client();
                }
            }
        }
        if (replacedClient != null) {
            closeClient(replacedClient);
        }
        return promoted == null ? temporaryLease(candidate) : retainedLease(promoted);
    }

    private McpClientSession connect(McpServerConfiguration server, BooleanSupplier cancelled) {
        ConnectionAttempt attempt = new ConnectionAttempt(cancelled);
        synchronized (lock) {
            if (closed.get() || runtimeAdmissionsStopped) {
                throw new IllegalStateException("MCP runtime is shutting down.");
            }
            connectionAttempts.add(attempt);
        }
        McpClientSession client = null;
        try {
            client = McpClientSession.connect(
                    server,
                    secretVault,
                    subprocessEnvironment,
                    configurationDirectory,
                    attempt::isCancelled,
                    attempt::attach
            );
            boolean rejected;
            synchronized (lock) {
                rejected = closed.get() || runtimeAdmissionsStopped;
                if (!rejected) {
                    allClients.add(client);
                }
            }
            if (rejected) {
                client.close();
                throw new IllegalStateException("MCP runtime is shutting down.");
            }
            log.info(
                    "MCP server connected (id={}, modelId={}, name={}, transport={}, protocol=2025-06-18)",
                    server.id(),
                    client.redactForDisplay(server.modelId()),
                    client.redactForDisplay(server.displayName()),
                    server.transport()
            );
            return client;
        } finally {
            attempt.settle();
            synchronized (lock) {
                connectionAttempts.remove(attempt);
            }
        }
    }

    private McpRunSession.ClientLease retainedLease(RetainedClient retained) {
        return new McpRunSession.ClientLease() {
            private final AtomicBoolean released = new AtomicBoolean();

            @Override
            public McpClientSession client() {
                return retained.client();
            }

            @Override
            public void close() {
                if (!released.compareAndSet(false, true)) {
                    return;
                }
                boolean shouldClose;
                synchronized (lock) {
                    retained.busy(false);
                    shouldClose = retained.retired() || !retained.client().isUsable();
                    if (shouldClose) {
                        retainedClients.values().removeIf(value -> value == retained);
                    }
                }
                if (shouldClose) {
                    closeClient(retained.client());
                }
            }
        };
    }

    private McpRunSession.ClientLease temporaryLease(McpClientSession client) {
        return new McpRunSession.ClientLease() {
            private final AtomicBoolean released = new AtomicBoolean();

            @Override
            public McpClientSession client() {
                return client;
            }

            @Override
            public void close() {
                if (released.compareAndSet(false, true)) {
                    closeClient(client);
                }
            }
        };
    }

    private void closeClients(List<McpClientSession> clients) {
        if (clients.isEmpty()) {
            return;
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?>[] closures = clients.stream()
                    .map(client -> CompletableFuture.runAsync(() -> {
                        try {
                            closeClient(client);
                        } catch (RuntimeException e) {
                            synchronized (lock) {
                                pendingClientCleanup.add(client);
                            }
                        }
                    }, executor))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(closures).join();
        }
    }

    private void closeClient(McpClientSession client) {
        synchronized (lock) {
            allClients.remove(client);
        }
        client.close();
    }

    private boolean closeRetiredClients(List<McpClientSession> clients) {
        boolean successful = true;
        for (McpClientSession client : clients) {
            try {
                closeClient(client);
            } catch (RuntimeException e) {
                synchronized (lock) {
                    pendingClientCleanup.add(client);
                }
                successful = false;
            }
        }
        return successful;
    }

    private void retryPendingClientCleanup() {
        List<McpClientSession> pending;
        synchronized (lock) {
            pending = List.copyOf(pendingClientCleanup);
        }
        pending.forEach(client -> {
            try {
                client.retryHardClose();
                synchronized (lock) {
                    pendingClientCleanup.remove(client);
                }
            } catch (RuntimeException ignored) {
            }
        });
    }

    private PreparedPublication prepare(McpConfigurationDraft draft) {
        validateDraftSecrets(draft);
        Map<String, char[]> newSecrets = new LinkedHashMap<>();
        try {
            List<McpServerConfiguration> servers = draft.configuration().servers().stream()
                    .map(server -> replaceSecretReferences(server, draft, newSecrets))
                    .toList();
            return new PreparedPublication(new McpConfiguration(McpConfiguration.CURRENT_VERSION, servers), newSecrets);
        } catch (RuntimeException e) {
            clearSecretMap(newSecrets);
            throw e;
        }
    }

    private void validateDraftSecrets(McpConfigurationDraft draft) {
        Set<String> allRows = draft.configuration().servers().stream()
                .flatMap(server -> concat(
                        server.headers().stream(),
                        server.environment().stream()
                ))
                .map(McpSecretReference::rowId)
                .collect(toSet());
        Set<String> headerRows = draft.configuration().servers().stream()
                .flatMap(server -> server.headers().stream())
                .map(McpSecretReference::rowId)
                .collect(toSet());
        if (!allRows.containsAll(draft.replacementSecrets().keySet())) {
            throw new IllegalArgumentException("MCP credential replacements contain an unknown row ID.");
        }
        draft.replacementSecrets().forEach((rowId, value) -> {
            if (value == null || value.length == 0
                    || CharBuffer.wrap(value).chars().allMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("MCP credential values must not be blank.");
            }
            boolean lineBreak = CharBuffer.wrap(value).chars().anyMatch(character -> character == '\r'
                    || character == '\n');
            if (headerRows.contains(rowId) && lineBreak) {
                throw new IllegalArgumentException("MCP HTTP header values must not contain line breaks.");
            }
            if (!headerRows.contains(rowId)
                    && CharBuffer.wrap(value).chars().anyMatch(character -> character == '\0')) {
                throw new IllegalArgumentException("MCP environment values must not contain NUL.");
            }
        });
    }

    private McpServerConfiguration replaceSecretReferences(
            McpServerConfiguration server,
            McpConfigurationDraft draft,
            Map<String, char[]> newSecrets
    ) {
        List<McpSecretReference> headers = replaceRows(server.headers(), draft, newSecrets);
        List<McpSecretReference> environment = replaceRows(server.environment(), draft, newSecrets);
        return new McpServerConfiguration(
                server.id(),
                server.name(),
                server.modelId(),
                server.enabled(),
                server.automatic(),
                server.transport(),
                server.endpoint(),
                server.executable(),
                server.arguments(),
                headers,
                environment,
                server.longRunning(),
                server.disabledTools()
        );
    }

    private List<McpSecretReference> replaceRows(
            List<McpSecretReference> rows,
            McpConfigurationDraft draft,
            Map<String, char[]> newSecrets
    ) {
        return rows.stream()
                .map(row -> {
                    char[] replacement = draft.replacementSecrets().get(row.rowId());
                    if (replacement == null) {
                        if (StringUtils.isBlank(row.secretId())) {
                            throw new IllegalArgumentException("A new MCP credential value is required.");
                        }
                        return row;
                    }
                    String secretId = "MCP_%s".formatted(UUID.randomUUID().toString()
                            .replace("-", "").toUpperCase(Locale.ROOT));
                    newSecrets.put(secretId, copyOf(replacement, replacement.length));
                    return new McpSecretReference(row.rowId(), row.key(), secretId);
                })
                .toList();
    }

    private List<McpClientSession> retireChangedClients(McpConfiguration current) {
        Map<String, McpServerConfiguration> currentById = current.servers().stream()
                .collect(toMap(McpServerConfiguration::id, identity()));
        List<McpClientSession> clientsToClose = new ArrayList<>();
        retainedClients.forEach((id, retained) -> {
            McpServerConfiguration next = currentById.get(id);
            if (next == null || !next.enabled()
                    || !retained.connectionIdentity().equals(connectionIdentity(next))) {
                if (retained.busy()) {
                    retained.retired(true);
                } else {
                    clientsToClose.add(retained.client());
                }
            }
        });
        retainedClients.entrySet().removeIf(entry -> {
            RetainedClient retained = entry.getValue();
            McpServerConfiguration next = currentById.get(entry.getKey());
            return !retained.busy() && (next == null || !next.enabled()
                    || !retained.connectionIdentity().equals(connectionIdentity(next)));
        });
        return clientsToClose;
    }

    private ConnectionIdentity connectionIdentity(McpServerConfiguration server) {
        return server.transport() == McpTransportType.STREAMABLE_HTTP
                ? new ConnectionIdentity(
                        server.transport(),
                        false,
                        server.endpoint(),
                        "",
                        emptyList(),
                        server.headers(),
                        emptyList()
                )
                : new ConnectionIdentity(
                        server.transport(),
                        server.longRunning(),
                        "",
                        server.executable(),
                        server.arguments(),
                        emptyList(),
                        server.environment()
                );
    }

    private String alias(McpServerConfiguration server, String toolName) {
        String base = ALIAS_CHARACTERS.matcher("mcp_%s_%s".formatted(server.modelId(), toolName))
                .replaceAll("_");
        String suffix = digest("%s\0%s".formatted(server.id(), toolName)).substring(0, 10);
        int maxBaseLength = MAX_ALIAS_LENGTH - suffix.length() - 1;
        String bounded = base.substring(0, min(base.length(), maxBaseLength));
        return "%s_%s".formatted(bounded, suffix);
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create MCP tool alias.", e);
        }
    }

    private Set<String> referencedSecretIds(McpConfiguration value) {
        Set<String> result = new HashSet<>();
        value.servers().forEach(server -> {
            server.headers().stream().map(McpSecretReference::secretId).filter(StringUtils::isNotBlank).forEach(result::add);
            server.environment().stream().map(McpSecretReference::secretId).filter(StringUtils::isNotBlank).forEach(result::add);
        });
        return result;
    }

    private void cleanupStartupOrphans() {
        Set<String> orphans = new HashSet<>(secretVault.secretIds());
        orphans.removeAll(referencedSecretIds(configuration));
        if (!orphans.isEmpty()) {
            try {
                secretVault.remove(orphans);
            } catch (Exception e) {
                pendingOrphanSecretIds.addAll(orphans);
            }
        }
    }

    private McpApplyResult rejected(String message) {
        return new McpApplyResult(
                McpApplyOutcome.REJECTED_OLD_STATE_INTACT,
                generation(),
                currentConfiguration(),
                StringUtils.defaultIfBlank(message, "MCP configuration was not changed.")
        );
    }

    private McpConfiguration currentConfiguration() {
        synchronized (lock) {
            return configuration;
        }
    }

    private void clearSecretMap(Map<String, char[]> values) {
        values.values().forEach(value -> fill(value, '\0'));
    }

    private void awaitManagerClose() {
        try {
            closeCompletion.get(CLOSE_SETTLEMENT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException ignored) {
        }
    }

    private void awaitRuntimeShutdown() {
        try {
            runtimeShutdownCompletion.get(RUNTIME_SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            throw new IllegalStateException("MCP runtime cleanup did not settle.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("MCP runtime cleanup failed.", e.getCause());
        }
    }

    private void awaitConnectionAttempts(List<ConnectionAttempt> attempts) {
        long deadline = System.nanoTime() + CONNECTION_SETTLEMENT_TIMEOUT.toNanos();
        attempts.forEach(attempt -> attempt.await(deadline));
    }

    private record PreparedPublication(McpConfiguration configuration, Map<String, char[]> newSecrets) {
        @Override
        public String toString() {
            return "PreparedPublication[configuration=%s, newSecrets=****]".formatted(configuration);
        }
    }

    private record ConnectionIdentity(
            McpTransportType transport,
            boolean longRunning,
            String endpoint,
            String executable,
            List<String> arguments,
            List<McpSecretReference> headers,
            List<McpSecretReference> environment
    ) {
        private ConnectionIdentity {
            arguments = List.copyOf(arguments);
            headers = List.copyOf(headers);
            environment = List.copyOf(environment);
        }
    }

    private static final class ConnectionAttempt {
        private final BooleanSupplier externalCancellation;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableFuture<Void> settled = new CompletableFuture<>();
        private volatile McpClientSession client;

        private ConnectionAttempt(BooleanSupplier externalCancellation) {
            this.externalCancellation = externalCancellation;
        }

        private boolean isCancelled() {
            return cancelled.get() || externalCancellation != null && externalCancellation.getAsBoolean();
        }

        private void attach(McpClientSession candidate) {
            client = candidate;
            if (cancelled.get()) {
                candidate.close();
            }
        }

        private void cancelAndClose() {
            cancelled.set(true);
            McpClientSession candidate = client;
            if (candidate != null) {
                candidate.close();
            }
        }

        private void settle() {
            settled.complete(null);
        }

        private void await(long deadline) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                settled.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (TimeoutException | ExecutionException ignored) {
            }
        }
    }

    private static final class RetainedClient {
        private final McpClientSession client;
        private final ConnectionIdentity connectionIdentity;
        private boolean busy;
        private boolean retired;

        private RetainedClient(
                McpClientSession client,
                ConnectionIdentity connectionIdentity,
                boolean busy,
                boolean retired
        ) {
            this.client = client;
            this.connectionIdentity = connectionIdentity;
            this.busy = busy;
            this.retired = retired;
        }

        McpClientSession client() {
            return client;
        }

        ConnectionIdentity connectionIdentity() {
            return connectionIdentity;
        }

        boolean busy() {
            return busy;
        }

        void busy(boolean busy) {
            this.busy = busy;
        }

        boolean retired() {
            return retired;
        }

        void retired(boolean retired) {
            this.retired = retired;
        }
    }
}
