package com.github.drafael.chat4j.mcp;

import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class McpManagerPersistenceTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Publishing a new header stores only an opaque reference in MCP JSON")
    void saveAndApply_whenHeaderIsNew_encryptsValueAndPublishesReference() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var tokenVault = new ApiTokenVault(storagePaths);
        var secrets = new McpSecretVault(tokenVault);
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                secrets,
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        try {
            String rowId = UUID.randomUUID().toString();
            var row = new McpSecretReference(rowId, "Authorization", "");
            var configuration = new McpConfiguration(1, List.of(server(List.of(row))));
            char[] value = "Bearer top-secret".toCharArray();

            McpApplyResult result = subject.saveAndApply(new McpConfigurationDraft(
                    configuration,
                    Map.of(rowId, value)
            )).join();

            assertThat(result.outcome()).withFailMessage(result.toString()).isEqualTo(McpApplyOutcome.APPLIED);
            McpSecretReference persisted = result.configuration().servers().getFirst().headers().getFirst();
            assertThat(persisted.secretId()).startsWith("MCP_");
            assertThat(Files.readString(storagePaths.mcpFile(), StandardCharsets.UTF_8))
                    .doesNotContain("top-secret")
                    .contains(persisted.secretId());
            try (var lookup = secrets.lookup(persisted.secretId())) {
                assertThat(lookup.present()).isTrue();
                assertThat(lookup.token()).containsExactly("Bearer top-secret".toCharArray());
            }
        } finally {
            subject.close();
        }
        assertThat(subject.publicationSettlementProvenOnClose()).isTrue();
    }

    @Test
    @DisplayName("An invalid startup file cannot be overwritten through ordinary save")
    void saveAndApply_whenStartupFileIsInvalid_rejectsAndPreservesFile() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        Files.createDirectories(storagePaths.mcpFile().getParent());
        Files.writeString(storagePaths.mcpFile(), "{invalid", StandardCharsets.UTF_8);
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        try {
            McpApplyResult result = subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(server(emptyList())))
            )).join();

            assertThat(result.outcome()).isEqualTo(McpApplyOutcome.REJECTED_OLD_STATE_INTACT);
            assertThat(Files.readString(storagePaths.mcpFile(), StandardCharsets.UTF_8)).isEqualTo("{invalid");
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("A repaired configuration remains authoritative when verification cannot connect")
    void replaceInvalidAndVerify_whenConnectionFails_retainsAppliedMetadataAndNewSecretReference() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        Files.createDirectories(storagePaths.mcpFile().getParent());
        Files.writeString(storagePaths.mcpFile(), "{invalid", StandardCharsets.UTF_8);
        var secrets = new McpSecretVault(new ApiTokenVault(storagePaths));
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                secrets,
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        String rowId = UUID.randomUUID().toString();
        var server = new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Unavailable server",
                "unavailable_server",
                true,
                false,
                McpTransportType.STDIO,
                "",
                tempDirectory.resolve("missing-executable").toString(),
                emptyList(),
                emptyList(),
                List.of(new McpSecretReference(rowId, "TOKEN", "")),
                false,
                emptySet()
        );
        try {
            McpVerificationResult result = subject.replaceInvalidAndVerify(
                    new McpConfigurationDraft(
                            new McpConfiguration(1, List.of(server)),
                            Map.of(rowId, "new-secret".toCharArray())
                    ),
                    server.id(),
                    () -> false
            ).join();

            assertThat(result.applyResult().outcome().applied()).isTrue();
            assertThat(result.verified()).isFalse();
            assertThat(result.verificationError()).isNotBlank().doesNotContain("new-secret");
            McpConfiguration applied = result.applyResult().configuration();
            String secretId = applied.servers().getFirst().environment().getFirst().secretId();
            assertThat(secretId).matches("MCP_[A-F0-9]{32}");
            assertThat(subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(applied)).join().outcome())
                    .isEqualTo(McpApplyOutcome.APPLIED);
            try (var lookup = secrets.lookup(secretId)) {
                assertThat(lookup.present()).isTrue();
            }
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Replacement secrets must address an exact configuration row")
    void saveAndApply_whenReplacementRowIsUnknown_rejectsWithoutCreatingSecret() {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var secrets = new McpSecretVault(new ApiTokenVault(storagePaths));
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                secrets,
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        try {
            McpApplyResult result = subject.saveAndApply(new McpConfigurationDraft(
                    new McpConfiguration(1, List.of(server(emptyList()))),
                    Map.of(UUID.randomUUID().toString(), "secret".toCharArray())
            )).join();

            assertThat(result.outcome()).isEqualTo(McpApplyOutcome.REJECTED_OLD_STATE_INTACT);
            assertThat(result.message()).contains("unknown row ID");
            assertThat(secrets.secretIds()).isEmpty();
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("A partially prepared secret batch is discarded when a later row is invalid")
    void saveAndApply_whenPreparationFailsAfterFirstReplacement_discardsPreparedSecrets() {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var secrets = new McpSecretVault(new ApiTokenVault(storagePaths));
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                secrets,
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        String firstRow = UUID.randomUUID().toString();
        String missingRow = UUID.randomUUID().toString();
        McpServerConfiguration configured = server(List.of(
                new McpSecretReference(firstRow, "Authorization", ""),
                new McpSecretReference(missingRow, "X-Second", "")
        ));
        try {
            McpApplyResult result = subject.saveAndApply(new McpConfigurationDraft(
                    new McpConfiguration(1, List.of(configured)),
                    Map.of(firstRow, "prepared-secret".toCharArray())
            )).join();

            assertThat(result.outcome()).isEqualTo(McpApplyOutcome.REJECTED_OLD_STATE_INTACT);
            assertThat(secrets.secretIds()).isEmpty();
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Post-publication idle-client close failure returns applied cleanup pending and retries safely")
    void saveAndApply_whenIdleRetirementCloseFails_returnsAppliedCleanupPending() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        try {
            McpServerConfiguration existing = server(emptyList());
            subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    new McpConfiguration(1, List.of(existing))
            )).join();
            McpClientSession client = mock(McpClientSession.class);
            doThrow(new IllegalStateException("forced close failure")).when(client).close();
            injectRetainedClient(subject, existing, client);
            McpApplyResult result = subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    McpConfiguration.empty()
            )).join();

            assertThat(result.outcome()).isEqualTo(McpApplyOutcome.APPLIED_CLEANUP_PENDING);
            assertThat(result.configuration()).isEqualTo(McpConfiguration.empty());
            assertThat(result.message()).contains("connection cleanup is pending");
            assertThat(subject.saveAndApply(McpConfigurationDraft.withoutSecretChanges(
                    McpConfiguration.empty()
            )).join().outcome()).isEqualTo(McpApplyOutcome.APPLIED);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Manager close bounds publication settlement instead of waiting indefinitely")
    void close_whenPublicationExecutorIsBlocked_failsBoundedlyAndInterruptsPublication() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var subject = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        CompletableFuture<Void> close = null;
        ExecutorService executor = null;
        try {
            executor = field(subject, "publicationExecutor", ExecutorService.class);
            executor.submit(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> closing = CompletableFuture.runAsync(subject::close);
            close = closing;
            assertThatThrownBy(() -> closing.get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("MCP configuration publication did not settle before shutdown.");
        } finally {
            release.countDown();
            if (executor != null) {
                executor.shutdownNow();
            }
            if (close != null) {
                close.handle((ignored, error) -> null).join();
            }
        }
        assertThat(subject.publicationSettlementProvenOnClose()).isFalse();
    }

    private void injectRetainedClient(
            McpManager manager,
            McpServerConfiguration server,
            McpClientSession client
    ) throws Exception {
        Method identityMethod = McpManager.class.getDeclaredMethod("connectionIdentity", McpServerConfiguration.class);
        identityMethod.setAccessible(true);
        Object identity = identityMethod.invoke(manager, server);
        Class<?> retainedType = Class.forName("com.github.drafael.chat4j.mcp.McpManager$RetainedClient");
        Constructor<?> constructor = retainedType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object retained = constructor.newInstance(client, identity, false, false);
        Map<String, Object> retainedClients = field(manager, "retainedClients", Map.class);
        retainedClients.put(server.id(), retained);
        Set<McpClientSession> allClients = field(manager, "allClients", Set.class);
        allClients.add(client);
    }

    private <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    private McpServerConfiguration server(List<McpSecretReference> headers) {
        return new McpServerConfiguration(
                UUID.randomUUID().toString(),
                "Server",
                "server_one",
                false,
                false,
                McpTransportType.STREAMABLE_HTTP,
                "https://example.test/mcp",
                "",
                emptyList(),
                headers,
                emptyList(),
                false,
                emptySet()
        );
    }
}
