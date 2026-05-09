package com.github.drafael.chat4j.settings;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

public class FontMenuApplyDispatchCoordinator {

    public boolean apply(
            @NonNull FontApplyAction applyAction,
            String errorPrefix,
            @NonNull ErrorPresenter errorPresenter
    ) {
        Validate.notBlank(errorPrefix, "errorPrefix must not be blank");

        FontMenuApplyCoordinator.ApplyResult applyResult = applyAction.apply();
        if (applyResult.success()) {
            return true;
        }

        errorPresenter.show(errorPrefix + applyResult.errorMessage());
        return false;
    }

    @FunctionalInterface
    public interface FontApplyAction {
        FontMenuApplyCoordinator.ApplyResult apply();
    }

    @FunctionalInterface
    public interface ErrorPresenter {
        void show(String message);
    }
}
