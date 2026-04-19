package com.github.drafael.chat4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PersistedMessageCounterTest {

    @Test
    @DisplayName("Resolve loads and caches persisted count when not present")
    void resolve_whenCountIsMissing_loadsAndCachesCount() throws Exception {
        var subject = new PersistedMessageCounter();
        UUID conversationId = UUID.randomUUID();
        var loadCalls = new AtomicInteger();

        int firstCount = subject.resolve(conversationId, id -> {
            loadCalls.incrementAndGet();
            return 3;
        });
        int secondCount = subject.resolve(conversationId, id -> {
            loadCalls.incrementAndGet();
            return 9;
        });

        assertThat(firstCount).isEqualTo(3);
        assertThat(secondCount).isEqualTo(3);
        assertThat(loadCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Increment updates only known conversations")
    void incrementIfPresent_whenConversationIsKnown_incrementsCount() throws Exception {
        var subject = new PersistedMessageCounter();
        UUID knownConversationId = UUID.randomUUID();
        UUID unknownConversationId = UUID.randomUUID();

        subject.markConversationLoaded(knownConversationId, 2);
        subject.incrementIfPresent(knownConversationId);
        subject.incrementIfPresent(unknownConversationId);

        int knownCount = subject.resolve(knownConversationId, id -> 0);
        int unknownCount = subject.resolve(unknownConversationId, id -> 7);

        assertThat(knownCount).isEqualTo(3);
        assertThat(unknownCount).isEqualTo(7);
    }

    @Test
    @DisplayName("Negative counts are normalized to zero")
    void markPersisted_whenCountIsNegative_normalizesToZero() throws Exception {
        var subject = new PersistedMessageCounter();
        UUID conversationId = UUID.randomUUID();

        subject.markPersisted(conversationId, -5);

        int count = subject.resolve(conversationId, id -> 8);
        assertThat(count).isZero();
    }
}
