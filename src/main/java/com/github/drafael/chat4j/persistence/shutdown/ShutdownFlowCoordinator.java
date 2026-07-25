package com.github.drafael.chat4j.persistence.shutdown;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

public class ShutdownFlowCoordinator {

    private final ShutdownDispatcher shutdownDispatcher;

    public ShutdownFlowCoordinator(@NonNull ShutdownSaveDispatchCoordinator shutdownSaveDispatchCoordinator) {
        this(shutdownSaveDispatchCoordinator::dispatch);
    }

    ShutdownFlowCoordinator(@NonNull ShutdownDispatcher shutdownDispatcher) {
        this.shutdownDispatcher = shutdownDispatcher;
    }

    public boolean request(
            @NonNull BooleanSupplier shutdownInProgressSupplier,
            @NonNull Runnable markShutdownInProgress,
            @NonNull LongSupplier remainingTimeoutMillisSupplier,
            @NonNull Runnable preShutdownAction,
            @NonNull Supplier<ShutdownSaveDispatchCoordinator.SaveAction> saveActionSupplier,
            @NonNull Runnable finishAction,
            @NonNull Runnable timeoutAction,
            @NonNull ShutdownSaveDispatchCoordinator.FailureHandler failureHandler
    ) {
        if (shutdownInProgressSupplier.getAsBoolean()) {
            return false;
        }

        markShutdownInProgress.run();
        preShutdownAction.run();
        var saveAction = Validate.notNull(saveActionSupplier.get(), "saveActionSupplier must not return null");
        long remainingTimeoutMillis = remainingTimeoutMillisSupplier.getAsLong();
        if (remainingTimeoutMillis <= 0) {
            timeoutAction.run();
            finishAction.run();
            return true;
        }
        shutdownDispatcher.dispatch(
                remainingTimeoutMillis,
                saveAction,
                finishAction::run,
                timeoutAction::run,
                failureHandler
        );
        return true;
    }

    @FunctionalInterface
    interface ShutdownDispatcher {
        void dispatch(
                long timeoutMillis,
                ShutdownSaveDispatchCoordinator.SaveAction saveAction,
                ShutdownSaveDispatchCoordinator.CompletionHandler completionHandler,
                ShutdownSaveDispatchCoordinator.TimeoutHandler timeoutHandler,
                ShutdownSaveDispatchCoordinator.FailureHandler failureHandler
        );
    }
}
