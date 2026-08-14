package com.github.drafael.chat4j.persistence.conversation;

import com.github.drafael.chat4j.provider.api.Message;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationLoadCoordinatorTest {

    @Test
    @DisplayName("Load async notifies loaded listener when repository calls succeed")
    void loadAsync_whenRepositoryReturnsData_notifiesLoadedListener() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepository.MessageRecord record = new ConversationRepository.MessageRecord(
                UUID.randomUUID(),
                1,
                Message.user("hello")
        );
        ConversationRepository.ConversationRecord conversation = new ConversationRepository.ConversationRecord(
                conversationId,
                "demo",
                "OpenAI",
                "gpt-4.1",
                false,
                "off",
                false,
                null,
                false,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public Optional<LoadedConversation> loadConversation(UUID id) {
                return Optional.of(new LoadedConversation(conversation, List.of(record)));
            }
        };

        var subject = loadCoordinator(repo);
        var loaded = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var loadedRequestId = new AtomicLong(-1L);
        var loadedRecords = new AtomicReference<List<ConversationRepository.MessageRecord>>();
        var loadedConversation = new AtomicReference<ConversationRepository.ConversationRecord>();

        long requestId = subject.loadAsync(conversationId, new ConversationLoadCoordinator.Listener() {
            @Override
            public void onLoaded(
                    long callbackRequestId,
                    UUID callbackConversationId,
                    List<ConversationRepository.MessageRecord> records,
                    ConversationRepository.ConversationRecord loadedConversationRecord
            ) {
                loadedRecords.set(records);
                loadedConversation.set(loadedConversationRecord);
                loadedRequestId.set(callbackRequestId);
                assertThat(callbackConversationId).isEqualTo(conversationId);
                loaded.countDown();
            }

            @Override
            public void onFailure(long callbackRequestId, UUID callbackConversationId, Exception error) {
                failure.set(error);
            }
        });

        assertThat(loaded.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(loadedRequestId.get()).isEqualTo(requestId);
        assertThat(subject.isCurrentRequest(requestId)).isTrue();
        assertThat(loadedRecords.get()).containsExactly(record);
        assertThat(loadedConversation.get()).isEqualTo(conversation);
    }

    @Test
    @DisplayName("Load async notifies failure listener when repository throws")
    void loadAsync_whenRepositoryThrows_notifiesFailureListener() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public Optional<LoadedConversation> loadConversation(UUID id) {
                throw new IllegalStateException("boom");
            }
        };

        var subject = loadCoordinator(repo);
        var failed = new CountDownLatch(1);
        var callbackRequestIdRef = new AtomicLong(-1L);
        var capturedError = new AtomicReference<Exception>();

        long requestId = subject.loadAsync(conversationId, new ConversationLoadCoordinator.Listener() {
            @Override
            public void onLoaded(
                    long callbackRequestId,
                    UUID callbackConversationId,
                    List<ConversationRepository.MessageRecord> records,
                    ConversationRepository.ConversationRecord conversation
            ) {
            }

            @Override
            public void onFailure(long callbackRequestId, UUID callbackConversationId, Exception error) {
                callbackRequestIdRef.set(callbackRequestId);
                assertThat(callbackConversationId).isEqualTo(conversationId);
                capturedError.set(error);
                failed.countDown();
            }
        });

        assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedError.get()).isNotNull();
        assertThat(capturedError.get()).hasMessageContaining("boom");
        assertThat(callbackRequestIdRef.get()).isEqualTo(requestId);
        assertThat(subject.isCurrentRequest(requestId)).isTrue();
    }

    @Test
    @DisplayName("A missing conversation is reported as a load failure")
    void loadAsync_whenConversationDoesNotExist_reportsTypedFailure() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public Optional<LoadedConversation> loadConversation(UUID id) {
                return Optional.empty();
            }
        };
        var subject = loadCoordinator(repo);
        var failed = new CountDownLatch(1);
        var capturedError = new AtomicReference<Exception>();

        subject.loadAsync(conversationId, new ConversationLoadCoordinator.Listener() {
            @Override
            public void onLoaded(
                    long requestId,
                    UUID loadedConversationId,
                    List<ConversationRepository.MessageRecord> records,
                    ConversationRepository.ConversationRecord conversation
            ) {
            }

            @Override
            public void onFailure(long requestId, UUID failedConversationId, Exception error) {
                capturedError.set(error);
                failed.countDown();
            }
        });

        assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(capturedError.get())
                .isInstanceOf(ConversationLoadCoordinator.ConversationNotFoundException.class)
                .hasMessageContaining(conversationId.toString());
    }

    @Test
    @DisplayName("Load waits for the conversation mutation fence before reading")
    void loadAsync_whenMutationFenceIsPending_waitsBeforeRepositoryRead() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var fence = new CompletableFuture<Long>();
        var repositoryReads = new AtomicInteger();
        var loaded = new CountDownLatch(1);
        ConversationPersistenceCoordinator persistenceCoordinator = mock(ConversationPersistenceCoordinator.class);
        when(persistenceCoordinator.fenceRevision(conversationId)).thenReturn(fence);
        when(persistenceCoordinator.revision(conversationId)).thenReturn(3L);
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public Optional<LoadedConversation> loadConversation(UUID id) {
                repositoryReads.incrementAndGet();
                return Optional.empty();
            }
        };
        var subject = new ConversationLoadCoordinator(repo, persistenceCoordinator);

        subject.loadAsync(conversationId, noOpListener(loaded));

        assertThat(repositoryReads).hasValue(0);
        fence.complete(3L);
        assertThat(loaded.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(repositoryReads).hasValue(1);
    }

    @Test
    @DisplayName("A mutation accepted during the read causes a fresh fenced load")
    void loadAsync_whenRevisionChangesDuringRead_reloadsConversation() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var repositoryReads = new AtomicInteger();
        var loaded = new CountDownLatch(1);
        ConversationPersistenceCoordinator persistenceCoordinator = mock(ConversationPersistenceCoordinator.class);
        when(persistenceCoordinator.fenceRevision(conversationId))
                .thenReturn(CompletableFuture.completedFuture(1L), CompletableFuture.completedFuture(2L));
        when(persistenceCoordinator.revision(conversationId)).thenReturn(2L, 2L);
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public Optional<LoadedConversation> loadConversation(UUID id) {
                repositoryReads.incrementAndGet();
                return Optional.empty();
            }
        };
        var subject = new ConversationLoadCoordinator(repo, persistenceCoordinator);

        subject.loadAsync(conversationId, noOpListener(loaded));

        assertThat(loaded.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(repositoryReads).hasValue(2);
    }

    @Test
    @DisplayName("A mutation after the database read marks the queued result for reload")
    void mutationChangedSinceRead_whenRevisionAdvancesAfterCallback_marksResultStale() throws Exception {
        UUID conversationId = UUID.randomUUID();
        var revision = new AtomicLong(4L);
        var loaded = new CountDownLatch(1);
        ConversationPersistenceCoordinator persistenceCoordinator = mock(ConversationPersistenceCoordinator.class);
        when(persistenceCoordinator.fenceRevision(conversationId)).thenReturn(CompletableFuture.completedFuture(4L));
        when(persistenceCoordinator.revision(conversationId)).thenAnswer(ignored -> revision.get());
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public Optional<LoadedConversation> loadConversation(UUID id) {
                return Optional.of(emptyLoadedConversation(conversationId));
            }
        };
        var subject = new ConversationLoadCoordinator(repo, persistenceCoordinator);

        long requestId = subject.loadAsync(conversationId, noOpListener(loaded));
        assertThat(loaded.await(2, TimeUnit.SECONDS)).isTrue();
        revision.incrementAndGet();

        assertThat(subject.isCurrentRequest(requestId)).isFalse();
        assertThat(subject.mutationChangedSinceRead(requestId, conversationId)).isTrue();
    }

    @Test
    @DisplayName("Invalidating pending loads makes latest request stale")
    void invalidatePendingLoads_whenRequestInFlight_marksRequestStale() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public Optional<LoadedConversation> loadConversation(UUID id) {
                return Optional.empty();
            }
        };
        var subject = loadCoordinator(repo);
        var callbacks = new CountDownLatch(1);

        long requestId = subject.loadAsync(conversationId, noOpListener(callbacks));
        assertThat(callbacks.await(2, TimeUnit.SECONDS)).isTrue();

        subject.invalidatePendingLoads();

        assertThat(subject.isCurrentRequest(requestId)).isFalse();
    }

    @Test
    @DisplayName("Latest request is treated as current when loads overlap")
    void isCurrentRequest_whenLoadsOverlap_tracksLatestRequestId() throws Exception {
        UUID firstConversationId = UUID.randomUUID();
        UUID secondConversationId = UUID.randomUUID();
        var calls = new AtomicInteger();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var callbacks = new CountDownLatch(2);

        ConversationRepository repo = new ConversationRepository(null) {
            @Override
            public Optional<LoadedConversation> loadConversation(UUID id) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    firstStarted.countDown();
                    awaitLatch(releaseFirst);
                }
                return Optional.empty();
            }
        };

        var subject = loadCoordinator(repo);
        long firstRequestId = subject.loadAsync(firstConversationId, noOpListener(callbacks));

        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

        long secondRequestId = subject.loadAsync(secondConversationId, noOpListener(callbacks));
        assertThat(subject.isCurrentRequest(firstRequestId)).isFalse();
        assertThat(subject.isCurrentRequest(secondRequestId)).isTrue();

        releaseFirst.countDown();
        assertThat(callbacks.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private ConversationRepository.LoadedConversation emptyLoadedConversation(UUID conversationId) {
        var conversation = new ConversationRepository.ConversationRecord(
                conversationId,
                "Empty",
                "OpenAI",
                "gpt-4.1",
                false,
                "off",
                false,
                null,
                false,
                null,
                null
        );
        return new ConversationRepository.LoadedConversation(conversation, List.of());
    }

    private ConversationLoadCoordinator loadCoordinator(ConversationRepository repo) {
        ConversationPersistenceCoordinator persistenceCoordinator = mock(ConversationPersistenceCoordinator.class);
        when(persistenceCoordinator.fenceRevision(any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(0L));
        when(persistenceCoordinator.revision(any(UUID.class))).thenReturn(0L);
        return new ConversationLoadCoordinator(repo, persistenceCoordinator);
    }

    private ConversationLoadCoordinator.Listener noOpListener(CountDownLatch callbacks) {
        return new ConversationLoadCoordinator.Listener() {
            @Override
            public void onLoaded(
                    long requestId,
                    UUID conversationId,
                    List<ConversationRepository.MessageRecord> records,
                    ConversationRepository.ConversationRecord conversation
            ) {
                callbacks.countDown();
            }

            @Override
            public void onFailure(long requestId, UUID conversationId, Exception error) {
                callbacks.countDown();
            }
        };
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (latch.await(2, TimeUnit.SECONDS)) {
                return;
            }
            throw new IllegalStateException("Timed out waiting for latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
