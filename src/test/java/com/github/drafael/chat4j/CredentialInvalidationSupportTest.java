package com.github.drafael.chat4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static javax.swing.SwingUtilities.invokeAndWait;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialInvalidationSupportTest {

    @Test
    @DisplayName("Credential invalidation attempts continue after an earlier failure")
    void runAll_whenAnInvalidationFails_attemptsEveryInvalidation() {
        var attempts = new AtomicInteger();
        var firstFailure = new IllegalStateException("first failed");
        var secondFailure = new IllegalArgumentException("second failed");
        List<Runnable> invalidations = List.of(
                () -> {
                    attempts.incrementAndGet();
                    throw firstFailure;
                },
                attempts::incrementAndGet,
                () -> {
                    attempts.incrementAndGet();
                    throw secondFailure;
                }
        );

        assertThatThrownBy(() -> CredentialInvalidationSupport.runAll(invalidations))
                .isInstanceOf(IllegalStateException.class)
                .hasSuppressedException(firstFailure)
                .hasSuppressedException(secondFailure);
        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("Credential-backed UI refresh is scheduled once when invalidation fails")
    void invalidateAndRefresh_whenInvalidationFails_reportsFailureAndRefreshesOnce() throws Exception {
        var failure = new IllegalStateException("invalidation failed");
        var reportedFailure = new AtomicReference<RuntimeException>();
        var refreshCount = new AtomicInteger();

        CredentialInvalidationSupport.invalidateAndRefresh(
                () -> {
                    throw failure;
                },
                refreshCount::incrementAndGet,
                reportedFailure::set
        );
        invokeAndWait(() -> {
        });

        assertThat(reportedFailure).hasValue(failure);
        assertThat(refreshCount).hasValue(1);
    }
}
