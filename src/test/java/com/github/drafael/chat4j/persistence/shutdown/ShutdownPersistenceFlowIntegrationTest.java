package com.github.drafael.chat4j.persistence.shutdown;

import com.github.drafael.chat4j.persistence.conversation.ConversationHistoryEntry;
import com.github.drafael.chat4j.persistence.conversation.ConversationPersistenceCoordinator;
import com.github.drafael.chat4j.persistence.conversation.ConversationRepository;
import com.github.drafael.chat4j.provider.api.Message;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShutdownPersistenceFlowIntegrationTest {

    @Test
    @DisplayName("Final persistence recovery and independent cleanup both settle before finish")
    void dispatchStages_whenFinalRecoverySucceeds_waitsForCleanupBeforeFinishing() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var appendAttempts = new AtomicInteger();
        var finalRecoveryCompleted = new CountDownLatch(1);
        ConversationRepository repository = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (appendAttempts.incrementAndGet() == 1) {
                    throw new SQLException("initial write failed");
                }
                finalRecoveryCompleted.countDown();
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }
        };
        var persistenceCoordinator = new ConversationPersistenceCoordinator(repository);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
        assertThatThrownBy(() -> persistenceCoordinator.submitAssistant(conversationId, entry).join())
                .hasRootCauseMessage("initial write failed");
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        var finishCalls = new AtomicInteger();
        var finishCompleted = new CountDownLatch(1);
        var timeoutCalls = new AtomicInteger();
        var failureCalls = new AtomicInteger();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run);

        CompletableFuture<Void> terminal = persistenceCoordinator.sealWithFinal();
        subject.dispatchStages(
                System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
                terminal,
                cleanup,
                () -> {
                    finishCalls.incrementAndGet();
                    finishCompleted.countDown();
                },
                timeoutCalls::incrementAndGet,
                error -> failureCalls.incrementAndGet()
        );

        assertThat(finalRecoveryCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        terminal.join();
        assertThat(appendAttempts).hasValue(2);
        assertThat(finishCalls).hasValue(0);
        cleanup.complete(null);
        assertThat(finishCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(finishCalls).hasValue(1);
        assertThat(timeoutCalls).hasValue(0);
        assertThat(failureCalls).hasValue(0);
    }

    @Test
    @DisplayName("Failed final recovery still waits for cleanup and finishes once")
    void dispatchStages_whenFinalRecoveryFails_waitsForCleanupAndReportsOneFailure() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var appendAttempts = new AtomicInteger();
        ConversationRepository repository = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                appendAttempts.incrementAndGet();
                throw new SQLException("storage unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }
        };
        var persistenceCoordinator = new ConversationPersistenceCoordinator(repository);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
        assertThatThrownBy(() -> persistenceCoordinator.submitAssistant(conversationId, entry).join())
                .hasRootCauseMessage("storage unavailable");
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        var finishCalls = new AtomicInteger();
        var finishCompleted = new CountDownLatch(1);
        var failureCalls = new AtomicInteger();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run);

        subject.dispatchStages(
                System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
                persistenceCoordinator.sealWithFinal(),
                cleanup,
                () -> {
                    finishCalls.incrementAndGet();
                    finishCompleted.countDown();
                },
                () -> {},
                error -> failureCalls.incrementAndGet()
        );

        assertThat(finishCalls).hasValue(0);
        cleanup.complete(null);
        assertThat(finishCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(appendAttempts).hasValue(2);
        assertThat(failureCalls).hasValue(1);
        assertThat(finishCalls).hasValue(1);
    }

    @Test
    @DisplayName("An exhausted deadline finishes once without a final recovery task")
    void dispatchStages_whenDeadlineIsExhausted_finishesThroughTimeoutPath() {
        var persistenceCoordinator = new ConversationPersistenceCoordinator(new ConversationRepository(null));
        var cleanup = new CompletableFuture<Void>();
        var finishCalls = new AtomicInteger();
        var timeoutCalls = new AtomicInteger();
        var subject = new ShutdownSaveDispatchCoordinator(Runnable::run);

        subject.dispatchStages(
                System.nanoTime() - 1,
                persistenceCoordinator.sealWithoutFinal(),
                cleanup,
                finishCalls::incrementAndGet,
                timeoutCalls::incrementAndGet,
                error -> {}
        );

        assertThat(timeoutCalls).hasValue(1);
        assertThat(finishCalls).hasValue(1);
        cleanup.complete(null);
        assertThat(finishCalls).hasValue(1);
    }
}
