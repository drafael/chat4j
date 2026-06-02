package com.github.drafael.chat4j.storage;

import com.github.drafael.chat4j.provider.api.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ConversationLoadCoordinatorTest {

    @Test
    @DisplayName("Load async notifies loaded listener when repository calls succeed")
    void loadAsync_whenRepositoryReturnsData_notifiesLoadedListener() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepo.MessageRecord record = new ConversationRepo.MessageRecord(
                UUID.randomUUID(),
                Message.user("hello"),
                LocalDateTime.now()
        );
        ConversationRepo.ConversationRecord conversation = new ConversationRepo.ConversationRecord(
                conversationId,
                "demo",
                "OpenAI",
                "gpt-4.1",
                false,
                "off",
                false,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        ConversationRepo repo = new ConversationRepo(null) {
            @Override
            public List<ConversationRepo.MessageRecord> getMessages(UUID id) {
                return List.of(record);
            }

            @Override
            public Optional<ConversationRepo.ConversationRecord> findById(UUID id) {
                return Optional.of(conversation);
            }
        };

        var subject = new ConversationLoadCoordinator(repo);
        var loaded = new CountDownLatch(1);
        var failure = new AtomicReference<Exception>();
        var loadedRequestId = new AtomicLong(-1L);
        var loadedRecords = new AtomicReference<List<ConversationRepo.MessageRecord>>();
        var loadedConversation = new AtomicReference<ConversationRepo.ConversationRecord>();

        long requestId = subject.loadAsync(conversationId, new ConversationLoadCoordinator.Listener() {
            @Override
            public void onLoaded(
                    long callbackRequestId,
                    UUID callbackConversationId,
                    List<ConversationRepo.MessageRecord> records,
                    ConversationRepo.ConversationRecord loadedConversationRecord
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
        ConversationRepo repo = new ConversationRepo(null) {
            @Override
            public List<ConversationRepo.MessageRecord> getMessages(UUID id) {
                throw new IllegalStateException("boom");
            }
        };

        var subject = new ConversationLoadCoordinator(repo);
        var failed = new CountDownLatch(1);
        var callbackRequestIdRef = new AtomicLong(-1L);
        var capturedError = new AtomicReference<Exception>();

        long requestId = subject.loadAsync(conversationId, new ConversationLoadCoordinator.Listener() {
            @Override
            public void onLoaded(
                    long callbackRequestId,
                    UUID callbackConversationId,
                    List<ConversationRepo.MessageRecord> records,
                    ConversationRepo.ConversationRecord conversation
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
    @DisplayName("Invalidating pending loads makes latest request stale")
    void invalidatePendingLoads_whenRequestInFlight_marksRequestStale() throws Exception {
        UUID conversationId = UUID.randomUUID();
        ConversationRepo repo = new ConversationRepo(null) {
            @Override
            public List<ConversationRepo.MessageRecord> getMessages(UUID id) {
                return emptyList();
            }

            @Override
            public Optional<ConversationRepo.ConversationRecord> findById(UUID id) {
                return Optional.empty();
            }
        };
        var subject = new ConversationLoadCoordinator(repo);
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

        ConversationRepo repo = new ConversationRepo(null) {
            @Override
            public List<ConversationRepo.MessageRecord> getMessages(UUID id) {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    firstStarted.countDown();
                    awaitLatch(releaseFirst);
                }
                return emptyList();
            }

            @Override
            public Optional<ConversationRepo.ConversationRecord> findById(UUID id) {
                return Optional.empty();
            }
        };

        var subject = new ConversationLoadCoordinator(repo);
        long firstRequestId = subject.loadAsync(firstConversationId, noOpListener(callbacks));

        assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();

        long secondRequestId = subject.loadAsync(secondConversationId, noOpListener(callbacks));
        assertThat(subject.isCurrentRequest(firstRequestId)).isFalse();
        assertThat(subject.isCurrentRequest(secondRequestId)).isTrue();

        releaseFirst.countDown();
        assertThat(callbacks.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private ConversationLoadCoordinator.Listener noOpListener(CountDownLatch callbacks) {
        return new ConversationLoadCoordinator.Listener() {
            @Override
            public void onLoaded(
                    long requestId,
                    UUID conversationId,
                    List<ConversationRepo.MessageRecord> records,
                    ConversationRepo.ConversationRecord conversation
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
