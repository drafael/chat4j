package com.github.drafael.chat4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

final class CredentialInvalidationSupport {

    private CredentialInvalidationSupport() {
    }

    static void invalidateAndRefresh(
            Runnable invalidation,
            Runnable refreshUi,
            Consumer<RuntimeException> failureReporter
    ) {
        try {
            invalidation.run();
        } catch (RuntimeException e) {
            failureReporter.accept(e);
        } finally {
            SwingUtilities.invokeLater(refreshUi);
        }
    }

    static void runAll(List<Runnable> invalidations) {
        List<RuntimeException> failures = new ArrayList<>();
        invalidations.forEach(invalidation -> {
            try {
                invalidation.run();
            } catch (RuntimeException e) {
                failures.add(e);
            }
        });
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("One or more credential-backed cache invalidations failed");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }
}
