package com.github.drafael.chat4j.provider.capability.chat.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleAiResponseSafetyTest {

    @Test
    @DisplayName("Chunked response accepts the exact byte limit")
    void boundedBodySubscriber_whenBodyMatchesLimit_returnsCompleteBytes() {
        var subscription = new TestSubscription();
        var subject = new GoogleAiGenerateContentClient.BoundedBodySubscriber(-1L, 4L);

        subject.onSubscribe(subscription);
        subject.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4})));
        subject.onComplete();

        assertThat(subject.getBody().toCompletableFuture().join()).containsExactly(1, 2, 3, 4);
        assertThat(subscription.cancelled).isFalse();
    }

    @Test
    @DisplayName("Chunked response cancels as soon as limit plus one arrives")
    void boundedBodySubscriber_whenBodyExceedsLimit_cancelsAndFails() {
        var subscription = new TestSubscription();
        var subject = new GoogleAiGenerateContentClient.BoundedBodySubscriber(-1L, 4L);

        subject.onSubscribe(subscription);
        subject.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 5})));

        assertThat(subscription.cancelled).isTrue();
        assertThatThrownBy(() -> subject.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(java.io.IOException.class)
                .hasRootCauseMessage("Google AI response exceeded the response byte limit.");
    }

    @Test
    @DisplayName("Known oversized content length fails before requesting body data")
    void boundedBodySubscriber_whenContentLengthExceedsLimit_cancelsImmediately() {
        var subscription = new TestSubscription();
        var subject = new GoogleAiGenerateContentClient.BoundedBodySubscriber(5L, 4L);

        subject.onSubscribe(subscription);

        assertThat(subscription.cancelled).isTrue();
        assertThat(subscription.requested).isZero();
    }

    private static final class TestSubscription implements Flow.Subscription {
        private long requested;
        private boolean cancelled;

        @Override
        public void request(long count) {
            requested += count;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
