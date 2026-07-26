package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationPersistenceCoordinatorTest {

    @Test
    @DisplayName("Fence captures the revision at admission rather than counting later queued mutations")
    void fenceRevision_whenLaterMutationIsAccepted_returnsEarlierRevision() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                firstStarted.countDown();
                awaitLatch(releaseFirst);
            }

            @Override
            public void updateTitle(UUID id, String title) {
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var firstEntry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("first"));
        var first = subject.submitUserMessage(conversationId, firstEntry);
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

        var fence = subject.fenceRevision(conversationId);
        var later = subject.submitRename(conversationId, "later");
        releaseFirst.countDown();

        first.join();
        assertThat(fence.join()).isEqualTo(1);
        later.join();
        assertThat(subject.revision(conversationId)).isEqualTo(2);
        subject.close();
    }

    @Test
    @DisplayName("A commit-then-exception append reconciles as success without changing identity")
    void submitUserMessage_whenAppendCommittedBeforeException_completesSuccessfully() {
        UUID conversationId = UUID.randomUUID();
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("hello"));
        var appendCalls = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry candidate) throws SQLException {
                appendCalls.incrementAndGet();
                throw new SQLException("connection lost after commit");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry candidate) {
                return id.equals(conversationId) && candidate.equals(entry);
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);

        UUID persistedId = subject.submitUserMessage(conversationId, entry).join();

        assertThat(persistedId).isEqualTo(conversationId);
        assertThat(appendCalls).hasValue(1);
        subject.close();
    }

    @Test
    @DisplayName("An indeterminate assistant prerequisite is not reported as the unattempted user message")
    void submitUserMessage_whenAssistantPrerequisiteIsIndeterminate_doesNotAliasUserOutcome() {
        UUID conversationId = UUID.randomUUID();
        var assistantAppends = new AtomicInteger();
        var userAppends = new AtomicInteger();
        var canonicalChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (entry.message().role() == Role.USER) {
                    userAppends.incrementAndGet();
                    return;
                }
                assistantAppends.incrementAndGet();
                throw new SQLException("assistant append unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                int check = canonicalChecks.incrementAndGet();
                if (check == 1) {
                    return false;
                }
                if (check == 2) {
                    throw new SQLException("assistant reconciliation unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
        var user = new ConversationHistoryEntry(UUID.randomUUID(), 3, Message.user("next"));

        assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                .hasRootCauseMessage("assistant append unavailable");
        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, user).join())
                .hasCauseInstanceOf(ConversationPersistencePrerequisiteIndeterminateException.class);
        assertThat(userAppends).hasValue(0);

        assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isTrue();
        assertThat(userAppends).hasValue(0);
        assertThat(assistantAppends).hasValue(2);
        subject.close();
    }

    @Test
    @DisplayName("A following user message repairs a failed assistant prefix first")
    void submitUserMessage_whenAssistantRecoveryExists_retriesAssistantBeforeUser() {
        UUID conversationId = UUID.randomUUID();
        var ordinals = new ArrayList<Integer>();
        var assistantAttempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                ordinals.add(entry.ordinal());
                if (entry.message().role() == Role.ASSISTANT && assistantAttempts.incrementAndGet() == 1) {
                    throw new SQLException("forced assistant failure");
                }
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
        var user = new ConversationHistoryEntry(UUID.randomUUID(), 3, Message.user("next"));

        assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                .hasRootCauseMessage("forced assistant failure");
        assertThat(subject.pendingAssistantRecoveryMessageIds(conversationId)).containsExactly(assistant.messageId());

        subject.submitUserMessage(conversationId, user).join();

        assertThat(ordinals).containsExactly(2, 2, 3);
        assertThat(subject.pendingAssistantRecoveryMessageIds(conversationId)).isEmpty();
        subject.close();
    }

    @Test
    @DisplayName("A later favorite value survives a noncanonical indeterminate predecessor")
    void submitFavorite_whenPriorDesiredValueIsConfirmedAbsent_preservesLatestAcceptedValue() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstWriteStarted = new CountDownLatch(1);
        var releaseFirstWrite = new CountDownLatch(1);
        var favorite = new AtomicReference<>(false);
        var writes = new AtomicInteger();
        var postconditionChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void setFavorite(UUID id, boolean desired) throws SQLException {
                if (writes.incrementAndGet() == 1) {
                    firstWriteStarted.countDown();
                    awaitLatch(releaseFirstWrite);
                    throw new SQLException("commit outcome unavailable");
                }
                favorite.set(desired);
            }

            @Override
            public boolean hasFavorite(UUID id, boolean desired) throws SQLException {
                if (postconditionChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return favorite.get() == desired;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);

        CompletableFuture<Void> first = subject.submitFavorite(conversationId, true);
        assertThat(firstWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = subject.submitFavorite(conversationId, false);
        releaseFirstWrite.countDown();

        assertThatThrownBy(first::join).hasRootCauseMessage("commit outcome unavailable");
        assertThatThrownBy(second::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isFalse();
        assertThat(favorite).hasValue(false);
        assertThat(writes).hasValue(2);
        subject.close();
    }

    @Test
    @DisplayName("A repeated latest favorite value survives noncanonical predecessor reconciliation")
    void submitFavorite_whenLatestIntentRepeatsIndeterminateValue_preservesLatestAcceptance() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstWriteStarted = new CountDownLatch(1);
        var releaseFirstWrite = new CountDownLatch(1);
        var favorite = new AtomicReference<>(false);
        var writes = new AtomicInteger();
        var postconditionChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void setFavorite(UUID id, boolean desired) throws SQLException {
                if (writes.incrementAndGet() == 1) {
                    firstWriteStarted.countDown();
                    awaitLatch(releaseFirstWrite);
                    throw new SQLException("commit outcome unavailable");
                }
                favorite.set(desired);
            }

            @Override
            public boolean hasFavorite(UUID id, boolean desired) throws SQLException {
                if (postconditionChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return favorite.get() == desired;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);

        CompletableFuture<Void> first = subject.submitFavorite(conversationId, true);
        assertThat(firstWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = subject.submitFavorite(conversationId, false);
        CompletableFuture<Void> third = subject.submitFavorite(conversationId, true);
        releaseFirstWrite.countDown();

        assertThatThrownBy(first::join).hasRootCauseMessage("commit outcome unavailable");
        assertThatThrownBy(second::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(third::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isFalse();
        assertThat(favorite).hasValue(true);
        assertThat(writes).hasValue(2);
        subject.close();
    }

    @Test
    @DisplayName("A repeated latest title survives noncanonical predecessor reconciliation at shutdown")
    void sealWithFinal_whenLatestTitleRepeatsIndeterminateValue_preservesLatestAcceptance() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstWriteStarted = new CountDownLatch(1);
        var releaseFirstWrite = new CountDownLatch(1);
        var title = new AtomicReference<>("Original");
        var writes = new AtomicInteger();
        var postconditionChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateTitle(UUID id, String desired) throws SQLException {
                if (writes.incrementAndGet() == 1) {
                    firstWriteStarted.countDown();
                    awaitLatch(releaseFirstWrite);
                    throw new SQLException("commit outcome unavailable");
                }
                title.set(desired);
            }

            @Override
            public boolean hasTitle(UUID id, String desired) throws SQLException {
                if (postconditionChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return Objects.equals(title.get(), desired);
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);

        CompletableFuture<Void> first = subject.submitRename(conversationId, "Repeated");
        assertThat(firstWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = subject.submitRename(conversationId, "Intermediate");
        CompletableFuture<Void> third = subject.submitRename(conversationId, "Repeated");
        releaseFirstWrite.countDown();

        assertThatThrownBy(first::join).hasRootCauseMessage("commit outcome unavailable");
        assertThatThrownBy(second::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(third::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
        subject.sealWithFinal().join();
        assertThat(title).hasValue("Repeated");
        assertThat(writes).hasValue(2);
    }

    @Test
    @DisplayName("A later favorite value survives a canonical indeterminate predecessor")
    void submitFavorite_whenPriorDesiredValueIsConfirmedCanonical_preservesLatestAcceptedValue() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstWriteStarted = new CountDownLatch(1);
        var releaseFirstWrite = new CountDownLatch(1);
        var favorite = new AtomicReference<>(false);
        var writes = new AtomicInteger();
        var postconditionChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void setFavorite(UUID id, boolean desired) throws SQLException {
                favorite.set(desired);
                if (writes.incrementAndGet() == 1) {
                    firstWriteStarted.countDown();
                    awaitLatch(releaseFirstWrite);
                    throw new SQLException("commit outcome unavailable");
                }
            }

            @Override
            public boolean hasFavorite(UUID id, boolean desired) throws SQLException {
                if (postconditionChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return favorite.get() == desired;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);

        CompletableFuture<Void> first = subject.submitFavorite(conversationId, true);
        assertThat(firstWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = subject.submitFavorite(conversationId, false);
        releaseFirstWrite.countDown();

        assertThatThrownBy(first::join).hasRootCauseMessage("commit outcome unavailable");
        assertThatThrownBy(second::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isTrue();
        assertThat(favorite).hasValue(false);
        assertThat(writes).hasValue(2);
        subject.close();
    }

    @Test
    @DisplayName("Canonical favorite reconciliation consumes a repeated latest desired value")
    void submitFavorite_whenLatestIntentRepeatsCanonicalValue_avoidsRedundantWrite() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstWriteStarted = new CountDownLatch(1);
        var releaseFirstWrite = new CountDownLatch(1);
        var favorite = new AtomicReference<>(false);
        var writes = new AtomicInteger();
        var postconditionChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void setFavorite(UUID id, boolean desired) throws SQLException {
                favorite.set(desired);
                if (writes.incrementAndGet() == 1) {
                    firstWriteStarted.countDown();
                    awaitLatch(releaseFirstWrite);
                    throw new SQLException("commit outcome unavailable");
                }
            }

            @Override
            public boolean hasFavorite(UUID id, boolean desired) throws SQLException {
                if (postconditionChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return favorite.get() == desired;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        try {
            CompletableFuture<Void> first = subject.submitFavorite(conversationId, true);
            assertThat(firstWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> second = subject.submitFavorite(conversationId, false);
            CompletableFuture<Void> third = subject.submitFavorite(conversationId, true);
            releaseFirstWrite.countDown();

            assertThatThrownBy(first::join).hasRootCauseMessage("commit outcome unavailable");
            assertThatThrownBy(second::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
            assertThatThrownBy(third::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
            assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isTrue();
            assertThat(favorite).hasValue(true);
            assertThat(writes).hasValue(1);
        } finally {
            releaseFirstWrite.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("Canonical runtime reconciliation consumes a repeated latest desired value")
    void submitReasoningLevel_whenLatestIntentRepeatsCanonicalValue_avoidsRedundantWrite() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstWriteStarted = new CountDownLatch(1);
        var releaseFirstWrite = new CountDownLatch(1);
        var reasoningLevel = new AtomicReference<>(ReasoningLevel.OFF);
        var writes = new AtomicInteger();
        var postconditionChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateReasoningLevel(UUID id, ReasoningLevel desired) throws SQLException {
                reasoningLevel.set(desired);
                if (writes.incrementAndGet() == 1) {
                    firstWriteStarted.countDown();
                    awaitLatch(releaseFirstWrite);
                    throw new SQLException("commit outcome unavailable");
                }
            }

            @Override
            public boolean hasReasoningLevel(UUID id, ReasoningLevel desired) throws SQLException {
                if (postconditionChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return reasoningLevel.get() == desired;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        try {
            CompletableFuture<Void> first = subject.submitReasoningLevel(conversationId, ReasoningLevel.HIGH);
            assertThat(firstWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> second = subject.submitReasoningLevel(conversationId, ReasoningLevel.OFF);
            CompletableFuture<Void> third = subject.submitReasoningLevel(conversationId, ReasoningLevel.HIGH);
            releaseFirstWrite.countDown();

            assertThatThrownBy(first::join).hasRootCauseMessage("commit outcome unavailable");
            assertThatThrownBy(second::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
            assertThatThrownBy(third::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
            assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isTrue();
            assertThat(reasoningLevel).hasValue(ReasoningLevel.HIGH);
            assertThat(writes).hasValue(1);
        } finally {
            releaseFirstWrite.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("Confirmed-absent indeterminate rename is not retried during explicit reconciliation")
    void reconcileBlocked_whenRenameIsConfirmedAbsent_doesNotRetryWrite() {
        UUID conversationId = UUID.randomUUID();
        var writes = new AtomicInteger();
        var postconditionChecks = new AtomicInteger();
        ConversationRepository repo = indeterminateRenameRepository(writes, postconditionChecks);
        var subject = new ConversationPersistenceCoordinator(repo);

        assertThatThrownBy(() -> subject.submitRename(conversationId, "Changed").join())
                .hasRootCauseMessage("rename outcome unavailable");

        assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isFalse();
        assertThat(writes).hasValue(1);
        subject.close();
    }

    @Test
    @DisplayName("Confirmed-absent indeterminate rename is not retried by a load fence")
    void fenceRevision_whenRenameIsConfirmedAbsent_doesNotRetryWrite() {
        UUID conversationId = UUID.randomUUID();
        var writes = new AtomicInteger();
        var postconditionChecks = new AtomicInteger();
        ConversationRepository repo = indeterminateRenameRepository(writes, postconditionChecks);
        var subject = new ConversationPersistenceCoordinator(repo);

        assertThatThrownBy(() -> subject.submitRename(conversationId, "Changed").join())
                .hasRootCauseMessage("rename outcome unavailable");

        assertThat(subject.fenceRevision(conversationId).join()).isEqualTo(1L);
        assertThat(writes).hasValue(1);
        subject.close();
    }

    @Test
    @DisplayName("Confirmed-absent indeterminate rename is not retried during final recovery")
    void sealWithFinal_whenRenameIsConfirmedAbsent_doesNotRetryWrite() {
        UUID conversationId = UUID.randomUUID();
        var writes = new AtomicInteger();
        var postconditionChecks = new AtomicInteger();
        ConversationRepository repo = indeterminateRenameRepository(writes, postconditionChecks);
        var subject = new ConversationPersistenceCoordinator(repo);

        assertThatThrownBy(() -> subject.submitRename(conversationId, "Changed").join())
                .hasRootCauseMessage("rename outcome unavailable");

        subject.sealWithFinal().join();
        assertThat(writes).hasValue(1);
    }

    @Test
    @DisplayName("Exact-set delete advances every affected conversation revision")
    void submitDelete_whenMultipleIdsAreAccepted_advancesEveryRevision() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void deleteConversations(List<UUID> ids) {
                assertThat(ids).containsExactly(firstId, secondId);
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);

        subject.submitDelete(List.of(firstId, secondId)).join();

        assertThat(subject.revision(firstId)).isEqualTo(1);
        assertThat(subject.revision(secondId)).isEqualTo(1);
        subject.close();
    }

    @Test
    @DisplayName("Indeterminate exact-set delete exposes its complete reconciliation identity set")
    void indeterminateConversationIds_whenExactSetDeleteIsUnresolved_returnsEveryAffectedId() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        var absenceChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void deleteConversations(List<UUID> ids) throws SQLException {
                throw new SQLException("delete outcome unknown");
            }

            @Override
            public boolean conversationsAbsent(List<UUID> ids) throws SQLException {
                if (absenceChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        try {
            assertThatThrownBy(() -> subject.submitDelete(List.of(firstId, secondId)).join())
                    .hasRootCauseMessage("delete outcome unknown");

            assertThat(subject.indeterminateDeleteConversationIds(firstId)).containsExactly(firstId, secondId);
            assertThat(subject.indeterminateDeleteConversationIds(secondId)).containsExactly(firstId, secondId);
            assertThat(subject.reconcileBlocked(firstId).join().canonical()).isFalse();
            assertThat(subject.indeterminateDeleteConversationIds(firstId)).isEmpty();
            assertThat(subject.indeterminateDeleteConversationIds(secondId)).isEmpty();
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Final sealing retries failed runtime metadata with the same desired value")
    void sealWithFinal_whenRuntimeMetadataWriteFailed_retriesDesiredValue() {
        UUID conversationId = UUID.randomUUID();
        var attempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateReasoningLevel(UUID id, ReasoningLevel reasoningLevel) throws SQLException {
                if (attempts.incrementAndGet() == 1) {
                    throw new SQLException("forced failure");
                }
                assertThat(id).isEqualTo(conversationId);
                assertThat(reasoningLevel).isEqualTo(ReasoningLevel.HIGH);
            }

            @Override
            public boolean hasReasoningLevel(UUID id, ReasoningLevel reasoningLevel) {
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);

        assertThatThrownBy(() -> subject.submitReasoningLevel(conversationId, ReasoningLevel.HIGH).join())
                .hasRootCauseMessage("forced failure");
        subject.sealWithFinal().join();

        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("Repeated runtime values retain the latest accepted recovery identity")
    void submitReasoningLevel_whenLatestValueRepeats_preservesLatestRecovery() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstWriteStarted = new CountDownLatch(1);
        var releaseFirstWrite = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        var persisted = new AtomicReference<ReasoningLevel>();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateReasoningLevel(UUID id, ReasoningLevel level) throws SQLException {
                int attempt = attempts.incrementAndGet();
                if (attempt == 1) {
                    firstWriteStarted.countDown();
                    awaitLatch(releaseFirstWrite);
                }
                if (attempt == 3) {
                    throw new SQLException("latest write failed");
                }
                persisted.set(level);
            }

            @Override
            public boolean hasReasoningLevel(UUID id, ReasoningLevel level) {
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        try {
            CompletableFuture<Void> first = subject.submitReasoningLevel(conversationId, ReasoningLevel.HIGH);
            assertThat(firstWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> middle = subject.submitReasoningLevel(conversationId, ReasoningLevel.OFF);
            CompletableFuture<Void> latest = subject.submitReasoningLevel(conversationId, ReasoningLevel.HIGH);
            releaseFirstWrite.countDown();

            first.join();
            middle.join();
            assertThatThrownBy(latest::join).hasRootCauseMessage("latest write failed");
            subject.sealWithFinal().join();

            assertThat(attempts).hasValue(4);
            assertThat(persisted).hasValue(ReasoningLevel.HIGH);
        } finally {
            releaseFirstWrite.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("Load fence retries failed runtime metadata before allowing the read")
    void fenceRevision_whenReasoningRecoveryExists_retriesDesiredValue() {
        UUID conversationId = UUID.randomUUID();
        var attempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateReasoningLevel(UUID id, ReasoningLevel reasoningLevel) throws SQLException {
                if (attempts.incrementAndGet() == 1) {
                    throw new SQLException("forced failure");
                }
            }

            @Override
            public boolean hasReasoningLevel(UUID id, ReasoningLevel reasoningLevel) {
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);

        assertThatThrownBy(() -> subject.submitReasoningLevel(conversationId, ReasoningLevel.HIGH).join())
                .hasRootCauseMessage("forced failure");
        subject.fenceRevision(conversationId).join();

        assertThat(attempts).hasValue(2);
        subject.close();
    }

    @Test
    @DisplayName("Successful truncation discards failed assistant recovery in the removed suffix")
    void submitTruncate_whenAssistantRecoveryIsInSuffix_preventsShutdownResurrection() {
        UUID conversationId = UUID.randomUUID();
        UUID retainedMessageId = UUID.randomUUID();
        var assistantAttempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                assistantAttempts.incrementAndGet();
                throw new SQLException("forced assistant failure");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }

            @Override
            public void truncateAfter(UUID id, UUID messageId, int ordinal) {
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("old answer"));

        assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                .hasRootCauseMessage("forced assistant failure");
        subject.submitTruncate(conversationId, retainedMessageId, 1).join();
        subject.sealWithFinal().join();

        assertThat(assistantAttempts).hasValue(1);
    }

    @Test
    @DisplayName("Assistant admitted after truncate reaches the database is purged before settlement")
    void submitTruncate_whenAssistantArrivesAfterDatabaseMutation_doesNotRecoverRemovedSuffix() {
        UUID conversationId = UUID.randomUUID();
        UUID retainedMessageId = UUID.randomUUID();
        var assistantAppends = new AtomicInteger();
        var lateAssistant = new AtomicReference<CompletableFuture<Void>>();
        var subjectRef = new AtomicReference<ConversationPersistenceCoordinator>();
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("stale"));
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void truncateAfter(UUID id, UUID messageId, int ordinal) {
                lateAssistant.set(subjectRef.get().submitAssistant(conversationId, assistant));
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                assistantAppends.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        subjectRef.set(subject);
        try {
            subject.submitTruncate(conversationId, retainedMessageId, 1).join();
            assertThatThrownBy(() -> lateAssistant.get().join())
                    .hasRootCauseInstanceOf(RejectedExecutionException.class);
            subject.fenceRevision(conversationId).join();
            subject.sealWithFinal().join();

            assertThat(assistantAppends).hasValue(0);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Clear purges assistant recovery admitted after the database mutation")
    void submitClear_whenAssistantArrivesAfterDatabaseMutation_doesNotRecoverClearedEntry() {
        UUID conversationId = UUID.randomUUID();
        var assistantAppends = new AtomicInteger();
        var lateAssistant = new AtomicReference<CompletableFuture<Void>>();
        var subjectRef = new AtomicReference<ConversationPersistenceCoordinator>();
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("stale"));
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void clearMessages(UUID id) {
                lateAssistant.set(subjectRef.get().submitAssistant(conversationId, assistant));
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                assistantAppends.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        subjectRef.set(subject);
        try {
            subject.submitClear(conversationId).join();
            assertThatThrownBy(() -> lateAssistant.get().join())
                    .hasRootCauseInstanceOf(RejectedExecutionException.class);
            subject.fenceRevision(conversationId).join();
            subject.sealWithFinal().join();

            assertThat(assistantAppends).hasValue(0);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Canonical clear reconciliation atomically purges a concurrently admitted assistant")
    void fenceRevision_whenClearBecomesCanonical_doesNotRecoverAssistantAdmittedByPostcondition() {
        UUID conversationId = UUID.randomUUID();
        var clearChecks = new AtomicInteger();
        var assistantAppends = new AtomicInteger();
        var lateAssistant = new AtomicReference<CompletableFuture<Void>>();
        var subjectRef = new AtomicReference<ConversationPersistenceCoordinator>();
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("stale"));
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void clearMessages(UUID id) throws SQLException {
                throw new SQLException("clear outcome unavailable");
            }

            @Override
            public boolean isClear(UUID id) throws SQLException {
                if (clearChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                lateAssistant.set(subjectRef.get().submitAssistant(conversationId, assistant));
                return true;
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                assistantAppends.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        subjectRef.set(subject);
        try {
            assertThatThrownBy(() -> subject.submitClear(conversationId).join())
                    .hasRootCauseMessage("clear outcome unavailable");

            subject.fenceRevision(conversationId).join();
            assertThatThrownBy(() -> lateAssistant.get().join())
                    .hasRootCauseInstanceOf(RejectedExecutionException.class);
            subject.sealWithFinal().join();

            assertThat(assistantAppends).hasValue(0);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Clear releases destructive admission before completion callbacks submit later work")
    void submitClear_whenFutureSettles_allowsCompletionCallbackMutation() {
        UUID conversationId = UUID.randomUUID();
        var appendCalls = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void clearMessages(UUID id) {
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                appendCalls.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var followUp = new AtomicReference<CompletableFuture<Void>>();
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.assistant("after clear"));

        subject.submitClear(conversationId).whenComplete((ignored, error) ->
                followUp.set(subject.submitAssistant(conversationId, entry))
        ).join();
        followUp.get().join();

        assertThat(appendCalls).hasValue(1);
    }

    @Test
    @DisplayName("Pending clear rejects stale writes and permits post-clear writes only after settlement")
    void submitClear_whenPending_blocksLaterMutationUntilClearSettles() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var clearStarted = new CountDownLatch(1);
        var releaseClear = new CountDownLatch(1);
        var appendCalls = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void clearMessages(UUID id) {
                clearStarted.countDown();
                awaitLatch(releaseClear);
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                appendCalls.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("after clear"));

        var clear = subject.submitClear(conversationId);
        assertThat(clearStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        releaseClear.countDown();
        clear.join();
        subject.submitUserMessage(conversationId, entry).join();

        assertThat(appendCalls).hasValue(1);
        subject.close();
    }

    @Test
    @DisplayName("Rejected overlapping delete cannot clear the first delete admission guard")
    void submitDelete_whenOverlappingDeleteIsPending_keepsOriginalGuard() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var deleteStarted = new CountDownLatch(1);
        var releaseDelete = new CountDownLatch(1);
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void deleteConversations(List<UUID> ids) {
                deleteStarted.countDown();
                awaitLatch(releaseDelete);
            }

            @Override
            public boolean conversationsAbsent(List<UUID> ids) {
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("stale"));

        var firstDelete = subject.submitDelete(List.of(conversationId));
        assertThat(deleteStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> subject.submitDelete(List.of(conversationId)).join())
                .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        releaseDelete.countDown();
        firstDelete.join();
        subject.close();
    }

    @Test
    @DisplayName("A conflicting assistant command cannot replace an in-flight stable recovery identity")
    void submitAssistant_whenSameMessageIdIsPending_rejectsConflictingCommand() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var firstAppendStarted = new CountDownLatch(1);
        var releaseFirstAppend = new CountDownLatch(1);
        var appendCalls = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                appendCalls.incrementAndGet();
                firstAppendStarted.countDown();
                awaitLatch(releaseFirstAppend);
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var first = new ConversationHistoryEntry(messageId, 2, Message.assistant("first"));
        var conflicting = new ConversationHistoryEntry(messageId, 2, Message.assistant("conflicting"));
        try {
            CompletableFuture<Void> firstFuture = subject.submitAssistant(conversationId, first);
            assertThat(firstAppendStarted.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> subject.submitAssistant(conversationId, conflicting).join())
                    .hasRootCauseInstanceOf(RejectedExecutionException.class)
                    .hasRootCauseMessage("Assistant message persistence is already pending");
            assertThatThrownBy(() -> subject.submitAssistant(conversationId, conflicting).join())
                    .hasRootCauseInstanceOf(RejectedExecutionException.class)
                    .hasRootCauseMessage("Assistant message persistence is already pending");
            releaseFirstAppend.countDown();
            firstFuture.join();

            assertThat(appendCalls).hasValue(1);
            assertThat(subject.pendingAssistantRecoveryMessageIds(conversationId)).isEmpty();
        } finally {
            releaseFirstAppend.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("Assistant completion during a failed delete remains recoverable")
    void submitAssistant_whenDeleteIsPendingAndFails_recoversAfterDeleteSettlement() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var deleteStarted = new CountDownLatch(1);
        var releaseDelete = new CountDownLatch(1);
        var appendedEntries = new ArrayList<ConversationHistoryEntry>();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void deleteConversations(List<UUID> ids) throws SQLException {
                deleteStarted.countDown();
                try {
                    assertThat(releaseDelete.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(e);
                }
                throw new SQLException("forced delete failure");
            }

            @Override
            public boolean conversationsAbsent(List<UUID> ids) {
                return false;
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                appendedEntries.add(entry);
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
        CompletableFuture<Void> delete = subject.submitDelete(List.of(conversationId));
        assertThat(deleteStarted.await(2, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
        releaseDelete.countDown();
        assertThatThrownBy(delete::join).hasRootCauseMessage("forced delete failure");
        subject.fenceRevision(conversationId).join();
        var nextUser = new ConversationHistoryEntry(UUID.randomUUID(), 3, Message.user("next"));
        subject.submitUserMessage(conversationId, nextUser).join();

        assertThat(appendedEntries).containsExactly(assistant, nextUser);
    }

    @Test
    @DisplayName("A confirmed assistant retry failure does not prevent loading the durable prefix")
    void fenceRevision_whenAssistantRetryFailsConfirmed_completesFenceForOverlayLoad() {
        UUID conversationId = UUID.randomUUID();
        var attempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                attempts.incrementAndGet();
                throw new SQLException("still unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
        assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                .hasRootCauseMessage("still unavailable");

        assertThat(subject.fenceRevision(conversationId).join()).isEqualTo(1L);
        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("Successful delete permanently rejects stale writes for the deleted identity")
    void submitDelete_whenCommitted_permanentlyBlocksConversationIdentity() {
        UUID conversationId = UUID.randomUUID();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void deleteConversations(List<UUID> ids) {
            }

            @Override
            public boolean conversationsAbsent(List<UUID> ids) {
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("stale"));

        subject.submitDelete(List.of(conversationId)).join();

        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        subject.close();
    }

    @Test
    @DisplayName("A completed load fence preserves its outcome for a later explicit observer")
    void reconcileBlocked_whenFenceAlreadyConsumedRecovery_returnsSettledOutcome() {
        UUID conversationId = UUID.randomUUID();
        var checks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                throw new SQLException("write outcome unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (checks.incrementAndGet() == 1) {
                    throw new SQLException("initial read unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var user = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("committed"));

        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, user).join())
                .hasRootCauseMessage("write outcome unavailable");
        subject.fenceRevision(conversationId).join();

        assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isTrue();
        assertThat(checks).hasValue(2);
        subject.close();
    }

    @Test
    @DisplayName("A newly accepted mutation cannot consume an older settled outcome")
    void reconcileBlocked_whenEarlierFenceOutcomeWasUnobserved_returnsCurrentMutationOutcome() {
        UUID conversationId = UUID.randomUUID();
        var reasoningChecks = new AtomicInteger();
        var userChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateReasoningLevel(UUID id, ReasoningLevel level) throws SQLException {
                throw new SQLException("reasoning outcome unavailable");
            }

            @Override
            public boolean hasReasoningLevel(UUID id, ReasoningLevel level) throws SQLException {
                if (reasoningChecks.incrementAndGet() == 1) {
                    throw new SQLException("reasoning read unavailable");
                }
                return true;
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                throw new SQLException("user outcome unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (userChecks.incrementAndGet() == 1) {
                    throw new SQLException("user read unavailable");
                }
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var user = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("not committed"));
        try {
            assertThatThrownBy(() -> subject.submitReasoningLevel(conversationId, ReasoningLevel.HIGH).join())
                    .hasRootCauseMessage("reasoning outcome unavailable");
            subject.fenceRevision(conversationId).join();
            assertThatThrownBy(() -> subject.submitUserMessage(conversationId, user).join())
                    .hasRootCauseMessage("user outcome unavailable");

            assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isFalse();
            assertThat(userChecks).hasValue(2);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("An indeterminate follow-on Sidebar recovery remains independently reconcilable")
    void reconcileBlocked_whenFollowOnSidebarRecoveryIsIndeterminate_preservesNewRecovery() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var userWriteStarted = new CountDownLatch(1);
        var releaseUserWrite = new CountDownLatch(1);
        var userChecks = new AtomicInteger();
        var titleChecks = new AtomicInteger();
        var titleWrites = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                userWriteStarted.countDown();
                awaitLatch(releaseUserWrite);
                throw new SQLException("user outcome unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (userChecks.incrementAndGet() == 1) {
                    throw new SQLException("user reconciliation unavailable");
                }
                return false;
            }

            @Override
            public void updateTitle(UUID id, String title) throws SQLException {
                titleWrites.incrementAndGet();
                throw new SQLException("rename outcome unavailable");
            }

            @Override
            public boolean hasTitle(UUID id, String title) throws SQLException {
                if (titleChecks.incrementAndGet() == 1) {
                    throw new SQLException("rename reconciliation unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var user = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("one"));
        try {
            CompletableFuture<UUID> userFuture = subject.submitUserMessage(conversationId, user);
            assertThat(userWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> renameFuture = subject.submitRename(conversationId, "Later");
            releaseUserWrite.countDown();
            assertThatThrownBy(userFuture::join).hasRootCauseMessage("user outcome unavailable");
            assertThatThrownBy(renameFuture::join)
                    .hasCauseInstanceOf(ConversationPersistencePrerequisiteIndeterminateException.class);

            assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isFalse();
            assertThat(subject.hasIndeterminateMutation(conversationId)).isTrue();
            assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isTrue();
            assertThat(subject.hasIndeterminateMutation(conversationId)).isFalse();
            assertThat(titleWrites).hasValue(1);
        } finally {
            releaseUserWrite.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("A failed Sidebar recovery cannot replace the requested reconciliation outcome")
    void reconcileBlocked_whenFollowOnSidebarRecoveryFails_returnsRequestedOutcome() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var userWriteStarted = new CountDownLatch(1);
        var releaseUserWrite = new CountDownLatch(1);
        var renameRetryFinished = new CountDownLatch(1);
        var userChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                userWriteStarted.countDown();
                awaitLatch(releaseUserWrite);
                throw new SQLException("user outcome unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (userChecks.incrementAndGet() == 1) {
                    throw new SQLException("user reconciliation unavailable");
                }
                return false;
            }

            @Override
            public void updateTitle(UUID id, String title) throws SQLException {
                throw new SQLException("rename failed");
            }

            @Override
            public boolean hasTitle(UUID id, String title) {
                renameRetryFinished.countDown();
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var user = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("one"));
        try {
            CompletableFuture<UUID> userFuture = subject.submitUserMessage(conversationId, user);
            assertThat(userWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> renameFuture = subject.submitRename(conversationId, "Later");
            releaseUserWrite.countDown();
            assertThatThrownBy(userFuture::join).hasRootCauseMessage("user outcome unavailable");
            assertThatThrownBy(renameFuture::join)
                    .hasCauseInstanceOf(ConversationPersistencePrerequisiteIndeterminateException.class);

            assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isFalse();
            assertThat(renameRetryFinished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(subject.hasIndeterminateMutation(conversationId)).isFalse();
            assertThat(subject.consumeFailedSidebarRecovery(conversationId)).isTrue();
            assertThat(subject.consumeFailedSidebarRecovery(conversationId)).isFalse();
        } finally {
            releaseUserWrite.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("A definitive Sidebar recovery failure remains reportable beside an indeterminate recovery")
    void reconcileBlocked_whenSidebarRecoveriesHaveMixedOutcomes_preservesDefinitiveFailure() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var userWriteStarted = new CountDownLatch(1);
        var releaseUserWrite = new CountDownLatch(1);
        var userChecks = new AtomicInteger();
        var titleWrites = new AtomicInteger();
        var favoriteWrites = new AtomicInteger();
        var favoriteChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                userWriteStarted.countDown();
                awaitLatch(releaseUserWrite);
                throw new SQLException("user outcome unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (userChecks.incrementAndGet() == 1) {
                    throw new SQLException("user reconciliation unavailable");
                }
                return false;
            }

            @Override
            public void updateTitle(UUID id, String title) throws SQLException {
                titleWrites.incrementAndGet();
                throw new SQLException("rename failed");
            }

            @Override
            public boolean hasTitle(UUID id, String title) {
                return false;
            }

            @Override
            public void setFavorite(UUID id, boolean favorite) throws SQLException {
                favoriteWrites.incrementAndGet();
                throw new SQLException("favorite outcome unavailable");
            }

            @Override
            public boolean hasFavorite(UUID id, boolean favorite) throws SQLException {
                if (favoriteChecks.incrementAndGet() == 1) {
                    throw new SQLException("favorite reconciliation unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var user = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("one"));
        try {
            CompletableFuture<UUID> userFuture = subject.submitUserMessage(conversationId, user);
            assertThat(userWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> renameFuture = subject.submitRename(conversationId, "Later");
            CompletableFuture<Void> favoriteFuture = subject.submitFavorite(conversationId, true);
            releaseUserWrite.countDown();
            assertThatThrownBy(userFuture::join).hasRootCauseMessage("user outcome unavailable");
            assertThatThrownBy(renameFuture::join)
                    .hasCauseInstanceOf(ConversationPersistencePrerequisiteIndeterminateException.class);
            assertThatThrownBy(favoriteFuture::join)
                    .hasCauseInstanceOf(ConversationPersistencePrerequisiteIndeterminateException.class);

            assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isFalse();
            assertThat(subject.hasIndeterminateMutation(conversationId)).isTrue();
            assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isTrue();

            assertThat(titleWrites).hasValue(1);
            assertThat(favoriteWrites).hasValue(1);
            assertThat(subject.consumeFailedSidebarRecovery(conversationId)).isTrue();
        } finally {
            releaseUserWrite.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("Explicit reconciliation observes the canonical outcome consumed by a concurrent load fence")
    void reconcileBlocked_whenFenceConsumesRecovery_returnsSharedCanonicalOutcome() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var fenceReconciliationStarted = new CountDownLatch(1);
        var releaseFenceReconciliation = new CountDownLatch(1);
        var canonicalChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                throw new SQLException("write outcome unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                int check = canonicalChecks.incrementAndGet();
                if (check == 1) {
                    throw new SQLException("initial read unavailable");
                }
                fenceReconciliationStarted.countDown();
                awaitLatch(releaseFenceReconciliation);
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("committed"));
        try {
            assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                    .hasRootCauseMessage("write outcome unavailable");

            CompletableFuture<Long> fence = subject.fenceRevision(conversationId);
            assertThat(fenceReconciliationStarted.await(2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<ConversationPersistenceCoordinator.ReconciliationResult> reconciliation = subject.reconcileBlocked(conversationId);
            releaseFenceReconciliation.countDown();

            fence.join();
            assertThat(reconciliation.join().canonical()).isTrue();
            assertThat(canonicalChecks).hasValue(2);
        } finally {
            releaseFenceReconciliation.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("A settled non-delete outcome cannot be consumed as deletion")
    void consumeSettledDeleteReconciliation_whenUserOutcomeSettled_returnsEmpty() {
        UUID conversationId = UUID.randomUUID();
        var checks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                throw new SQLException("write outcome unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (checks.incrementAndGet() == 1) {
                    throw new SQLException("initial read unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("committed"));
        try {
            assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                    .hasRootCauseMessage("write outcome unavailable");
            subject.fenceRevision(conversationId).join();

            assertThat(subject.consumeSettledDeleteReconciliation(List.of(conversationId))).isEmpty();
            assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isTrue();
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("A load fence exposes a settled exact-delete outcome to UI settlement")
    void consumeSettledReconciliation_whenFenceConfirmsDelete_returnsCompleteIdentityOutcome() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        var absenceChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void deleteConversations(List<UUID> ids) throws SQLException {
                throw new SQLException("delete outcome unavailable");
            }

            @Override
            public boolean conversationsAbsent(List<UUID> ids) throws SQLException {
                if (absenceChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        List<UUID> ids = List.of(firstId, secondId);
        try {
            assertThatThrownBy(() -> subject.submitDelete(ids).join())
                    .hasRootCauseMessage("delete outcome unavailable");

            subject.fenceRevision(firstId).join();

            assertThat(subject.consumeSettledDeleteReconciliation(ids)).contains(true);
            assertThat(subject.consumeSettledDeleteReconciliation(ids)).isEmpty();
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Generic reconciliation preserves a settled delete identity and exact ID set")
    void reconcileBlocked_whenFenceSettledDelete_returnsTypedDeleteResult() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        var absenceChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void deleteConversations(List<UUID> ids) throws SQLException {
                throw new SQLException("delete outcome unavailable");
            }

            @Override
            public boolean conversationsAbsent(List<UUID> ids) throws SQLException {
                if (absenceChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        List<UUID> ids = List.of(firstId, secondId);
        try {
            assertThatThrownBy(() -> subject.submitDelete(ids).join())
                    .hasRootCauseMessage("delete outcome unavailable");
            subject.fenceRevision(firstId).join();

            ConversationPersistenceCoordinator.ReconciliationResult result = subject.reconcileBlocked(firstId).join();

            assertThat(result.canonical()).isTrue();
            assertThat(result.reconcilesDelete()).isTrue();
            assertThat(result.deleteConversationIds()).containsExactlyElementsOf(ids);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Concurrent reconciliation callers share one canonical outcome")
    void reconcileBlocked_whenCalledConcurrently_returnsSharedFuture() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var reconciliationStarted = new CountDownLatch(1);
        var releaseReconciliation = new CountDownLatch(1);
        var canonicalChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                throw new SQLException("write unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (canonicalChecks.incrementAndGet() == 1) {
                    throw new SQLException("initial read unavailable");
                }
                reconciliationStarted.countDown();
                try {
                    if (!releaseReconciliation.await(2, TimeUnit.SECONDS)) {
                        throw new SQLException("reconciliation timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(e);
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("committed"));
        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseMessage("write unavailable");

        CompletableFuture<ConversationPersistenceCoordinator.ReconciliationResult> first = subject.reconcileBlocked(conversationId);
        assertThat(reconciliationStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<ConversationPersistenceCoordinator.ReconciliationResult> second = subject.reconcileBlocked(conversationId);
        assertThat(second).isSameAs(first);
        releaseReconciliation.countDown();

        assertThat(first.join().canonical()).isTrue();
        assertThat(second.join().canonical()).isTrue();
        subject.close();
    }

    @Test
    @DisplayName("A second explicit edit for the same stable entry is rejected instead of replacing recovery")
    void submitEdit_whenSameEntryMutationIsPending_rejectsDuplicateRecoveryKey() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                firstStarted.countDown();
                try {
                    if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                        throw new SQLException("edit timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(e);
                }
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var firstEntry = new ConversationHistoryEntry(messageId, 1, Message.user("first edit"));
        var secondEntry = new ConversationHistoryEntry(messageId, 1, Message.user("second edit"));

        CompletableFuture<Void> first = subject.submitEdit(conversationId, firstEntry);
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> second = subject.submitEdit(conversationId, secondEntry);

        assertThatThrownBy(second::join).hasRootCauseInstanceOf(RejectedExecutionException.class);
        releaseFirst.countDown();
        first.join();
        subject.close();
    }

    @Test
    @DisplayName("Blocked reconciliation reports only the original mutation result")
    void reconcileBlocked_whenFollowOnRuntimeRecoveryExists_doesNotAliasItsResult() {
        UUID conversationId = UUID.randomUUID();
        var entryChecks = new AtomicInteger();
        var reasoningAttempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateReasoningLevel(UUID id, ReasoningLevel level) throws SQLException {
                reasoningAttempts.incrementAndGet();
                throw new SQLException("reasoning write failed");
            }

            @Override
            public boolean hasReasoningLevel(UUID id, ReasoningLevel level) {
                return false;
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                throw new SQLException("user write unavailable");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (entryChecks.incrementAndGet() == 1) {
                    throw new SQLException("user reconciliation unavailable");
                }
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("not committed"));

        assertThatThrownBy(() -> subject.submitReasoningLevel(conversationId, ReasoningLevel.HIGH).join())
                .hasRootCauseMessage("reasoning write failed");
        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseMessage("user write unavailable");

        assertThat(subject.reconcileBlocked(conversationId).join().canonical()).isFalse();
        assertThat(reasoningAttempts).hasValue(1);
        assertThat(subject.hasIndeterminateMutation(conversationId)).isFalse();
        subject.close();
    }

    @Test
    @DisplayName("Final sealing retries an accepted user command whose failure was not delivered")
    void sealWithFinal_whenUserFailureWasNotDelivered_retriesStableCommand() {
        UUID conversationId = UUID.randomUUID();
        var attempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (attempts.incrementAndGet() == 1) {
                    throw new SQLException("forced failure");
                }
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("preserve"));

        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseMessage("forced failure");
        subject.sealWithFinal().join();

        assertThat(attempts).hasValue(2);
    }

    @Test
    @DisplayName("Final recovery preserves accepted user mutation order")
    void sealWithFinal_whenMultipleUserMutationsFailed_retriesInAdmissionOrder() {
        UUID conversationId = UUID.randomUUID();
        Map<Integer, AtomicInteger> attemptsByOrdinal = new java.util.concurrent.ConcurrentHashMap<>();
        List<Integer> committedOrdinals = new ArrayList<>();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                int attempt = attemptsByOrdinal
                        .computeIfAbsent(entry.ordinal(), ignored -> new AtomicInteger())
                        .incrementAndGet();
                if (attempt == 1) {
                    throw new SQLException("forced failure %d".formatted(entry.ordinal()));
                }
                committedOrdinals.add(entry.ordinal());
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var second = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.user("second"));
        var third = new ConversationHistoryEntry(UUID.randomUUID(), 3, Message.user("third"));

        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, second).join())
                .hasRootCauseMessage("forced failure 2");
        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, third).join())
                .hasRootCauseMessage("forced failure 3");
        subject.sealWithFinal().join();

        assertThat(committedOrdinals).containsExactly(2, 3);
    }

    @Test
    @DisplayName("Final indeterminate truncation blocks stale assistant recovery")
    void sealWithFinal_whenTruncateRetryBecomesIndeterminate_doesNotRestoreRemovedSuffix() {
        UUID conversationId = UUID.randomUUID();
        UUID retainedMessageId = UUID.randomUUID();
        var appendAttempts = new AtomicInteger();
        var truncateAttempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                appendAttempts.incrementAndGet();
                throw new SQLException("assistant write failed");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }

            @Override
            public void truncateAfter(UUID id, UUID messageId, int ordinal) throws SQLException {
                truncateAttempts.incrementAndGet();
                throw new SQLException("truncate outcome unavailable");
            }

            @Override
            public boolean isCanonicalTruncate(UUID id, UUID messageId, int ordinal) throws SQLException {
                if (truncateAttempts.get() > 1) {
                    throw new SQLException("truncate reconciliation unavailable");
                }
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("stale suffix"));

        assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                .hasRootCauseMessage("assistant write failed");
        assertThatThrownBy(() -> subject.submitTruncate(conversationId, retainedMessageId, 1).join())
                .hasRootCauseMessage("truncate outcome unavailable");

        assertThatThrownBy(() -> subject.sealWithFinal().join())
                .hasRootCauseMessage("truncate outcome unavailable");
        assertThat(subject.hasIndeterminateMutation(conversationId)).isTrue();
        assertThat(appendAttempts).hasValue(1);
        assertThat(truncateAttempts).hasValue(2);
    }

    @Test
    @DisplayName("Clear retains runtime-setting recovery for the reused conversation")
    void submitClear_whenRuntimeRecoveryIsPending_preservesFinalRetry() {
        UUID conversationId = UUID.randomUUID();
        var reasoningAttempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateReasoningLevel(UUID id, ReasoningLevel level) throws SQLException {
                if (reasoningAttempts.incrementAndGet() == 1) {
                    throw new SQLException("reasoning write failed");
                }
            }

            @Override
            public boolean hasReasoningLevel(UUID id, ReasoningLevel level) {
                return false;
            }

            @Override
            public void clearMessages(UUID id) {
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);

        assertThatThrownBy(() -> subject.submitReasoningLevel(conversationId, ReasoningLevel.HIGH).join())
                .hasRootCauseMessage("reasoning write failed");
        subject.submitClear(conversationId).join();
        subject.sealWithFinal().join();

        assertThat(reasoningAttempts).hasValue(2);
    }

    @Test
    @DisplayName("Delivered user failure is excluded from final automatic retry")
    void sealWithFinal_whenUserFailureWasDelivered_doesNotRetryCommand() {
        UUID conversationId = UUID.randomUUID();
        var attempts = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                attempts.incrementAndGet();
                throw new SQLException("forced failure");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("manual retry"));

        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseMessage("forced failure");
        subject.markUserMessageFailureDelivered(conversationId, entry.messageId());
        subject.sealWithFinal().join();

        assertThat(attempts).hasValue(1);
    }

    @Test
    @DisplayName("Canonical clear reconciliation discards pre-clear assistant recovery")
    void fenceRevision_whenIndeterminateClearCommitted_doesNotResurrectAssistantRecovery() {
        UUID conversationId = UUID.randomUUID();
        var appendCalls = new AtomicInteger();
        var clearChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                appendCalls.incrementAndGet();
                throw new SQLException("assistant write failed");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) {
                return false;
            }

            @Override
            public void clearMessages(UUID id) throws SQLException {
                throw new SQLException("connection lost after clear commit");
            }

            @Override
            public boolean isClear(UUID id) throws SQLException {
                if (clearChecks.incrementAndGet() == 1) {
                    throw new SQLException("clear reconciliation unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));

        assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                .hasRootCauseMessage("assistant write failed");
        assertThatThrownBy(() -> subject.submitClear(conversationId).join())
                .hasRootCauseMessage("connection lost after clear commit");
        subject.fenceRevision(conversationId).join();

        assertThat(appendCalls).hasValue(1);
        subject.close();
    }

    @Test
    @DisplayName("Canonical delete reconciliation permanently blocks the deleted identity")
    void fenceRevision_whenIndeterminateDeleteCommitted_keepsIdentityDeleted() {
        UUID conversationId = UUID.randomUUID();
        var absenceChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void deleteConversations(List<UUID> ids) throws SQLException {
                throw new SQLException("connection lost after delete commit");
            }

            @Override
            public boolean conversationsAbsent(List<UUID> ids) throws SQLException {
                if (absenceChecks.incrementAndGet() == 1) {
                    throw new SQLException("delete reconciliation unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("stale"));

        assertThatThrownBy(() -> subject.submitDelete(List.of(conversationId)).join())
                .hasRootCauseMessage("connection lost after delete commit");
        subject.fenceRevision(conversationId).join();

        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        subject.close();
    }

    @Test
    @DisplayName("A queued command cannot overtake an outcome that becomes indeterminate")
    void submitUserMessage_whenEarlierQueuedMutationBecomesIndeterminate_rejectsBeforeRepositoryCall() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var appendCalls = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                appendCalls.incrementAndGet();
                firstStarted.countDown();
                awaitLatch(releaseFirst);
                throw new SQLException("write outcome unknown");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                throw new SQLException("reconciliation unavailable");
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var firstEntry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("first"));
        var secondEntry = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.user("second"));

        var first = subject.submitUserMessage(conversationId, firstEntry);
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        var second = subject.submitUserMessage(conversationId, secondEntry);
        releaseFirst.countDown();

        assertThatThrownBy(first::join)
                .hasCauseInstanceOf(ConversationPersistenceIndeterminateException.class)
                .hasRootCauseMessage("write outcome unknown");
        assertThatThrownBy(second::join)
                .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThat(appendCalls).hasValue(1);
        subject.close();
    }

    @Test
    @DisplayName("Queued clear rejected by an indeterminate predecessor releases its admission guard")
    void submitClear_whenEarlierQueuedMutationBecomesIndeterminate_releasesClearGuard() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var appendCalls = new AtomicInteger();
        var reconciliationCalls = new AtomicInteger();
        var clearCalls = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (appendCalls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    awaitLatch(releaseFirst);
                    throw new SQLException("write outcome unknown");
                }
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (reconciliationCalls.incrementAndGet() == 1) {
                    throw new SQLException("reconciliation unavailable");
                }
                return false;
            }

            @Override
            public void clearMessages(UUID id) {
                clearCalls.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var firstEntry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("first"));
        var laterEntry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("later"));

        var first = subject.submitUserMessage(conversationId, firstEntry);
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        var clear = subject.submitClear(conversationId);
        releaseFirst.countDown();

        assertThatThrownBy(first::join).hasRootCauseMessage("write outcome unknown");
        assertThatThrownBy(clear::join)
                .hasCauseInstanceOf(ConversationPersistencePrerequisiteIndeterminateException.class)
                .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        subject.fenceRevision(conversationId).join();
        subject.submitUserMessage(conversationId, laterEntry).join();

        assertThat(clearCalls).hasValue(0);
        assertThat(appendCalls).hasValue(2);
        subject.close();
    }

    @Test
    @DisplayName("Queued delete rejected by an indeterminate predecessor retains prerequisite identity")
    void submitDelete_whenEarlierQueuedMutationBecomesIndeterminate_reportsPrerequisite() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var reconciliationCalls = new AtomicInteger();
        var deleteCalls = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                firstStarted.countDown();
                awaitLatch(releaseFirst);
                throw new SQLException("write outcome unknown");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (reconciliationCalls.incrementAndGet() == 1) {
                    throw new SQLException("reconciliation unavailable");
                }
                return false;
            }

            @Override
            public void deleteConversations(List<UUID> ids) {
                deleteCalls.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var firstEntry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("first"));

        var first = subject.submitUserMessage(conversationId, firstEntry);
        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
        var delete = subject.submitDelete(List.of(conversationId));
        releaseFirst.countDown();

        assertThatThrownBy(first::join).hasRootCauseMessage("write outcome unknown");
        assertThatThrownBy(delete::join)
                .hasCauseInstanceOf(ConversationPersistencePrerequisiteIndeterminateException.class)
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(subject.indeterminateDeleteConversationIds(conversationId)).isEmpty();
        assertThat(deleteCalls).hasValue(0);
        subject.close();
    }

    @Test
    @DisplayName("Failed reconciliation blocks mutations until a fence confirms the outcome")
    void fenceRevision_whenPriorOutcomeWasIndeterminate_reconcilesBeforeUnblocking() {
        UUID conversationId = UUID.randomUUID();
        var appendCalls = new AtomicInteger();
        var reconciliationCalls = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (appendCalls.incrementAndGet() == 1) {
                    throw new SQLException("write outcome unknown");
                }
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (reconciliationCalls.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return false;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("one"));

        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseMessage("write outcome unknown");
        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        subject.fenceRevision(conversationId).join();
        subject.submitUserMessage(conversationId, entry).join();

        assertThat(appendCalls).hasValue(2);
        subject.close();
    }

    @Test
    @DisplayName("Assistant completion admitted after truncate intent cannot recreate the removed suffix")
    void submitAssistant_whenTruncateIsPending_doesNotRecreateSuffix() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID retainedMessageId = UUID.randomUUID();
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var assistantAppends = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void updateTitle(UUID id, String title) {
                blockerStarted.countDown();
                awaitLatch(releaseBlocker);
            }

            @Override
            public void truncateAfter(UUID id, UUID messageId, int ordinal) {
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                assistantAppends.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        try {
            var blocker = subject.submitRename(conversationId, "blocking");
            assertThat(blockerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var truncate = subject.submitTruncate(conversationId, retainedMessageId, 1);
            var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("stale"));

            assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                    .hasRootCauseInstanceOf(RejectedExecutionException.class);
            releaseBlocker.countDown();
            blocker.join();
            truncate.join();
            subject.sealWithFinal().join();

            assertThat(assistantAppends).hasValue(0);
        } finally {
            releaseBlocker.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("Assistant completion retained during failed truncate is retried after settlement")
    void submitAssistant_whenPendingTruncateFails_retriesOnFence() throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID retainedMessageId = UUID.randomUUID();
        var truncateStarted = new CountDownLatch(1);
        var releaseTruncate = new CountDownLatch(1);
        var assistantAppends = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void truncateAfter(UUID id, UUID messageId, int ordinal) throws SQLException {
                truncateStarted.countDown();
                awaitLatch(releaseTruncate);
                throw new SQLException("truncate failed");
            }

            @Override
            public boolean isCanonicalTruncate(UUID id, UUID messageId, int ordinal) {
                return false;
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                assistantAppends.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        try {
            var truncate = subject.submitTruncate(conversationId, retainedMessageId, 1);
            assertThat(truncateStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
            assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                    .hasRootCauseInstanceOf(RejectedExecutionException.class);
            releaseTruncate.countDown();
            assertThatThrownBy(truncate::join).hasRootCauseMessage("truncate failed");

            subject.fenceRevision(conversationId).join();

            assertThat(assistantAppends).hasValue(1);
        } finally {
            releaseTruncate.countDown();
            subject.close();
        }
    }

    @Test
    @DisplayName("Assistant completion retained during indeterminate delete is retried when delete did not commit")
    void submitAssistant_whenDeleteIsIndeterminateAndAbsentCheckIsFalse_retriesOnFence() {
        UUID conversationId = UUID.randomUUID();
        var absenceChecks = new AtomicInteger();
        var assistantAppends = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void deleteConversations(List<UUID> ids) throws SQLException {
                throw new SQLException("delete outcome unknown");
            }

            @Override
            public boolean conversationsAbsent(List<UUID> ids) throws SQLException {
                if (absenceChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return false;
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                assistantAppends.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        try {
            assertThatThrownBy(() -> subject.submitDelete(List.of(conversationId)).join())
                    .hasRootCauseMessage("delete outcome unknown");
            var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
            assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                    .hasRootCauseInstanceOf(RejectedExecutionException.class);

            subject.fenceRevision(conversationId).join();

            assertThat(assistantAppends).hasValue(1);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Late canonical assistant reconciliation consumes recovery without a redundant append")
    void fenceRevision_whenAssistantPostconditionBecomesCanonical_doesNotAppendAgain() {
        UUID conversationId = UUID.randomUUID();
        var appendCalls = new AtomicInteger();
        var canonicalChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                appendCalls.incrementAndGet();
                throw new SQLException("write outcome unknown");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (canonicalChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        try {
            var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("answer"));
            assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                    .hasRootCauseMessage("write outcome unknown");

            subject.fenceRevision(conversationId).join();

            assertThat(appendCalls).hasValue(1);
            assertThat(canonicalChecks).hasValue(2);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("Late canonical user reconciliation consumes explicit shutdown recovery")
    void fenceRevision_whenUserPostconditionBecomesCanonical_doesNotRetryAtShutdown() {
        UUID conversationId = UUID.randomUUID();
        var appendCalls = new AtomicInteger();
        var canonicalChecks = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) throws SQLException {
                appendCalls.incrementAndGet();
                throw new SQLException("write outcome unknown");
            }

            @Override
            public boolean isCanonicalEntry(UUID id, ConversationHistoryEntry entry) throws SQLException {
                if (canonicalChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return true;
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        var entry = new ConversationHistoryEntry(UUID.randomUUID(), 1, Message.user("hello"));

        assertThatThrownBy(() -> subject.submitUserMessage(conversationId, entry).join())
                .hasRootCauseMessage("write outcome unknown");
        subject.fenceRevision(conversationId).join();
        subject.sealWithFinal().join();

        assertThat(appendCalls).hasValue(1);
        assertThat(canonicalChecks).hasValue(2);
    }

    @Test
    @DisplayName("Assistant completion retained during indeterminate clear is discarded when clear committed")
    void submitAssistant_whenClearIsIndeterminateAndCanonical_doesNotRecreateClearedHistory() {
        UUID conversationId = UUID.randomUUID();
        var clearChecks = new AtomicInteger();
        var assistantAppends = new AtomicInteger();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public void clearMessages(UUID id) throws SQLException {
                throw new SQLException("clear outcome unknown");
            }

            @Override
            public boolean isClear(UUID id) throws SQLException {
                if (clearChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return true;
            }

            @Override
            public void appendMessage(UUID id, ConversationHistoryEntry entry) {
                assistantAppends.incrementAndGet();
            }
        };
        var subject = new ConversationPersistenceCoordinator(repo);
        try {
            assertThatThrownBy(() -> subject.submitClear(conversationId).join())
                    .hasRootCauseMessage("clear outcome unknown");
            var assistant = new ConversationHistoryEntry(UUID.randomUUID(), 2, Message.assistant("stale"));
            assertThatThrownBy(() -> subject.submitAssistant(conversationId, assistant).join())
                    .hasRootCauseInstanceOf(RejectedExecutionException.class);

            subject.fenceRevision(conversationId).join();
            subject.sealWithFinal().join();

            assertThat(assistantAppends).hasValue(0);
        } finally {
            subject.close();
        }
    }

    @Test
    @DisplayName("First terminal call wins and later submissions are rejected")
    void sealWithoutFinal_whenCalledFirst_reusesTerminalFutureAndSealsAdmission() {
        UUID conversationId = UUID.randomUUID();
        var subject = new ConversationPersistenceCoordinator(new ConversationRepository(null));

        CompletableFuture<Void> terminal = subject.sealWithoutFinal();

        assertThat(subject.sealWithFinal()).isSameAs(terminal);
        assertThat(subject.sealWithoutFinal()).isSameAs(terminal);
        assertThatThrownBy(() -> subject.submitRename(conversationId, "late").join())
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
        terminal.join();
    }

    @Test
    @DisplayName("Executor rejection rolls back ordinary and exact-delete revisions")
    void submit_whenExecutorRejects_doesNotPublishPhantomRevision() {
        UUID conversationId = UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var subject = new ConversationPersistenceCoordinator(new ConversationRepository(null), executor);

        assertThatThrownBy(() -> subject.submitRename(conversationId, "title").join())
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(subject.revision(conversationId)).isZero();
        assertThatThrownBy(() -> subject.submitDelete(List.of(conversationId)).join())
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
        assertThat(subject.revision(conversationId)).isZero();
    }

    private ConversationRepository indeterminateRenameRepository(
            AtomicInteger writes,
            AtomicInteger postconditionChecks
    ) {
        return new ConversationRepository(null) {
            @Override
            public void updateTitle(UUID id, String title) throws SQLException {
                writes.incrementAndGet();
                throw new SQLException("rename outcome unavailable");
            }

            @Override
            public boolean hasTitle(UUID id, String title) throws SQLException {
                if (postconditionChecks.incrementAndGet() == 1) {
                    throw new SQLException("read unavailable");
                }
                return false;
            }
        };
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

}
