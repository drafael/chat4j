package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalServiceHealthTest {

    @Test
    @DisplayName("Non-blocking health check returns false when no cached status exists")
    void isReachableNonBlocking_whenNoCachedStatusExists_returnsFalse() {
        boolean reachable = LocalServiceHealth.isReachableNonBlocking("http://127.0.0.1:1/v1");

        assertThat(reachable).isFalse();
    }

    @Test
    @DisplayName("Non-blocking health check returns false when base URL is blank")
    void isReachableNonBlocking_whenBaseUrlBlank_returnsFalse() {
        boolean reachable = LocalServiceHealth.isReachableNonBlocking("  ");

        assertThat(reachable).isFalse();
    }
}
