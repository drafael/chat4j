package com.github.drafael.chat4j.bootstrap;

import com.github.drafael.chat4j.chat.conversation.webview.jcef.JcefRuntime;
import com.github.drafael.chat4j.storage.SettingsRepo;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
final class JcefStartupInitializer {

    private final JcefRuntime jcefRuntime;
    private final JcefStartupInitializationDecider decider;
    private final Executor executor;

    JcefStartupInitializer() {
        this(JcefRuntime.getInstance(), new JcefStartupInitializationDecider(), ForkJoinPool.commonPool());
    }

    JcefStartupInitializer(
            @NonNull JcefRuntime jcefRuntime,
            @NonNull JcefStartupInitializationDecider decider,
            @NonNull Executor executor
    ) {
        this.jcefRuntime = jcefRuntime;
        this.decider = decider;
        this.executor = executor;
    }

    void initializeIfNeeded(@NonNull SettingsRepo settingsRepo) {
        if (GraphicsEnvironment.isHeadless()) {
            log.debug("Skipping startup JCEF initialization in headless environment");
            return;
        }
        if (jcefRuntime.cachedAvailability().isPresent()) {
            log.debug("Skipping startup JCEF initialization because availability is already cached");
            return;
        }
        if (!decider.shouldInitialize(settingsRepo)) {
            log.debug("Skipping startup JCEF initialization because JCEF is not in the startup path");
            return;
        }
        if (jcefRuntime.cachedAvailability().isPresent()) {
            log.debug("Skipping startup JCEF initialization because availability was cached during decision");
            return;
        }

        log.info("Starting startup JCEF initialization with progress dialog");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        JcefInitializationProgressDialog dialog = new JcefInitializationProgressDialog();

        runOnEdtAndWait(() -> {
            CompletableFuture.supplyAsync(() -> jcefRuntime.availability(dialog::updateProgress), executor)
                    .whenComplete((availability, throwable) -> {
                        if (throwable != null) {
                            failure.set(throwable);
                        }
                        dialog.close();
                    });
            dialog.showDialog();
        });

        Throwable throwable = failure.get();
        if (throwable != null) {
            log.warn("Startup JCEF initialization failed: {}", ExceptionUtils.getRootCauseMessage(throwable));
        }
    }

    private static void runOnEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while showing JCEF startup progress", e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Failed to show JCEF startup progress", e.getCause());
        }
    }
}
