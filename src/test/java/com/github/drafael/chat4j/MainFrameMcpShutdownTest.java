package com.github.drafael.chat4j;

import com.github.drafael.chat4j.chat.ChatPanel;
import com.github.drafael.chat4j.mcp.McpConfigurationRepository;
import com.github.drafael.chat4j.mcp.McpManager;
import com.github.drafael.chat4j.persistence.StoragePaths;
import com.github.drafael.chat4j.provider.support.ApiTokenVault;
import com.github.drafael.chat4j.provider.support.CredentialMutationService;
import com.github.drafael.chat4j.provider.support.McpSecretVault;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Answers.RETURNS_DEFAULTS;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MainFrameMcpShutdownTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Repeated exit admission reuses the original mandatory MCP shutdown future")
    void beginMcpRuntimeShutdown_whenCalledRepeatedly_reusesCleanupFuture() throws Exception {
        MainFrame subject = mock(MainFrame.class, CALLS_REAL_METHODS);
        McpManager manager = mock(McpManager.class);
        setField(subject, "mcpManager", manager);

        var first = subject.beginMcpRuntimeShutdown();
        var second = subject.beginMcpRuntimeShutdown();
        first.join();

        assertThat(second).isSameAs(first).isCompleted();
        verify(manager, times(1)).beginRuntimeShutdown();
    }

    @Test
    @DisplayName("Exit admission fences Chat and starts tracked MCP cleanup before Settings saves")
    void beginApplicationExit_whenSettingsRequestsRestart_admitsMandatoryCleanupImmediately() throws Exception {
        MainFrame subject = mock(MainFrame.class, CALLS_REAL_METHODS);
        ChatPanel chatPanel = mock(ChatPanel.class);
        McpManager manager = mock(McpManager.class);
        setField(subject, "chatPanel", chatPanel);
        setField(subject, "mcpManager", manager);

        long hardDeadline = subject.beginApplicationExit(Long.MAX_VALUE - 1);
        subject.beginMcpRuntimeShutdown().join();

        assertThat(hardDeadline).isEqualTo(Long.MAX_VALUE - 1);
        verify(chatPanel).beginShutdown();
        verify(manager).beginRuntimeShutdown();
    }

    @Test
    @DisplayName("Final MCP cleanup preserves runtime failure and suppresses later safe cleanup failures")
    void closeMcpServicesAfterRuntimeSettlement_whenRuntimeAndSafeCleanupFail_preservesFailureOrder() {
        McpManager manager = mock(McpManager.class);
        CredentialMutationService credentials = mock(CredentialMutationService.class);
        var runtimeFailure = new IllegalStateException("runtime shutdown failed");
        var managerFailure = new IllegalStateException("manager close failed");
        var credentialFailure = new IllegalStateException("credential close failed");
        doThrow(managerFailure).when(manager).close();
        when(manager.publicationSettlementProvenOnClose()).thenReturn(true);
        doThrow(credentialFailure).when(credentials).closeSecrets();

        CompletableFuture<Void> cleanup = MainFrame.closeMcpServicesAfterRuntimeSettlement(
                CompletableFuture.failedFuture(runtimeFailure),
                manager,
                credentials
        );
        Throwable thrown = catchThrowable(cleanup::join);

        assertThat(thrown).isInstanceOf(CompletionException.class).hasCause(runtimeFailure);
        assertThat(runtimeFailure.getSuppressed()).containsExactly(managerFailure, credentialFailure);
        var order = inOrder(manager, credentials);
        order.verify(manager).close();
        order.verify(manager).publicationSettlementProvenOnClose();
        order.verify(credentials).closeSecrets();
    }

    @Test
    @DisplayName("Final MCP cleanup skips credential closure when publication settlement is unproven")
    void closeMcpServicesAfterRuntimeSettlement_whenPublicationDoesNotSettle_skipsCredentialClosure() {
        McpManager manager = mock(McpManager.class);
        CredentialMutationService credentials = mock(CredentialMutationService.class);
        var runtimeFailure = new IllegalStateException("runtime shutdown failed");
        var managerFailure = new IllegalStateException("publication settlement failed");
        doThrow(managerFailure).when(manager).close();
        when(manager.publicationSettlementProvenOnClose()).thenReturn(false);

        CompletableFuture<Void> cleanup = MainFrame.closeMcpServicesAfterRuntimeSettlement(
                CompletableFuture.failedFuture(runtimeFailure),
                manager,
                credentials
        );
        Throwable thrown = catchThrowable(cleanup::join);

        assertThat(thrown).isInstanceOf(CompletionException.class).hasCause(runtimeFailure);
        assertThat(runtimeFailure.getSuppressed()).containsExactly(managerFailure);
        verify(manager).close();
        verify(credentials, never()).closeSecrets();
    }

    @Test
    @DisplayName("MainFrame cleanup future waits for manager retry after an initial stdio close failure")
    void beginMcpRuntimeShutdown_whenInitialClientCloseFails_waitsForMandatoryRetry() throws Exception {
        StoragePaths storagePaths = StoragePaths.ofConfigHome(tempDirectory);
        var retryEntered = new CountDownLatch(1);
        var releaseRetry = new CountDownLatch(1);
        var manager = new McpManager(
                new McpConfigurationRepository(storagePaths.mcpFile()),
                new McpSecretVault(new ApiTokenVault(storagePaths)),
                emptyMap(),
                storagePaths.appConfigDirectory()
        );
        try {
            var closeCalls = new AtomicInteger();
            var retryCalls = new AtomicInteger();
            Class<?> clientType = Class.forName("com.github.drafael.chat4j.mcp.McpClientSession");
            Object client = mock(clientType, invocation -> {
                if ("close".equals(invocation.getMethod().getName())) {
                    closeCalls.incrementAndGet();
                    throw new IllegalStateException("first cleanup failed");
                }
                if ("retryHardClose".equals(invocation.getMethod().getName())) {
                    retryCalls.incrementAndGet();
                    retryEntered.countDown();
                    releaseRetry.await();
                    return null;
                }
                return RETURNS_DEFAULTS.answer(invocation);
            });
            field(manager, "allClients", Set.class).add(client);
            MainFrame subject = mock(MainFrame.class, CALLS_REAL_METHODS);
            setField(subject, "mcpManager", manager);
            var shutdown = subject.beginMcpRuntimeShutdown();
            assertThat(retryEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(shutdown).isNotDone();

            releaseRetry.countDown();
            shutdown.get(5, TimeUnit.SECONDS);

            assertThat(closeCalls).hasValue(1);
            assertThat(retryCalls).hasValue(1);
        } finally {
            releaseRetry.countDown();
            manager.close();
        }
    }

    private <T> T field(Object target, String name, Class<T> type) throws Exception {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return type.cast(field.get(target));
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
