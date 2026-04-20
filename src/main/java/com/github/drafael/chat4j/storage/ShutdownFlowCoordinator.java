package com.github.drafael.chat4j.storage;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import java.util.function.BooleanSupplier;

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
            long timeoutMillis,
            @NonNull Runnable preShutdownAction,
            @NonNull SaveActionFactory saveActionFactory,
            @NonNull Runnable finishAction,
            @NonNull Runnable timeoutAction,
            @NonNull ShutdownSaveDispatchCoordinator.FailureHandler failureHandler
    ) {
        Validate.isTrue(timeoutMillis > 0, "timeoutMillis must be greater than zero");

        if (shutdownInProgressSupplier.getAsBoolean()) {
            return false;
        }

        markShutdownInProgress.run();
        preShutdownAction.run();
        var saveAction = Validate.notNull(saveActionFactory.create(), "saveActionFactory must not return null");
        shutdownDispatcher.dispatch(timeoutMillis, saveAction, finishAction::run, timeoutAction::run, failureHandler);
        return true;
    }

    @FunctionalInterface
    public interface SaveActionFactory {
        ShutdownSaveDispatchCoordinator.SaveAction create();
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
