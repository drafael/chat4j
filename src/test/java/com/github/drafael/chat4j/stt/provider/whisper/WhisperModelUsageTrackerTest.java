package com.github.drafael.chat4j.stt.provider.whisper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WhisperModelUsageTrackerTest {

    @Test
    @DisplayName("A model remains in use until every overlapping lease closes")
    void acquire_whenLeasesOverlap_tracksEachLeaseAtomically() {
        var subject = new WhisperModelUsageTracker();
        var first = subject.acquire("tiny.en");
        var second = subject.acquire("tiny.en");

        first.close();

        assertThat(subject.inUse("tiny.en")).isTrue();
        second.close();
        assertThat(subject.inUse("tiny.en")).isFalse();
    }

    @Test
    @DisplayName("Closing the same lease twice does not release another lease")
    void close_whenCalledTwice_isIdempotent() {
        var subject = new WhisperModelUsageTracker();
        var first = subject.acquire("tiny.en");
        var second = subject.acquire("tiny.en");

        first.close();
        first.close();

        assertThat(subject.inUse("tiny.en")).isTrue();
        second.close();
        assertThat(subject.inUse("tiny.en")).isFalse();
    }
}
