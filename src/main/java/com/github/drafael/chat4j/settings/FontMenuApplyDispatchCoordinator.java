package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class FontMenuApplyDispatchCoordinator {

    public boolean apply(
            FontApplyAction applyAction,
            String errorPrefix,
            ErrorPresenter errorPresenter
    ) {
        Validate.notNull(applyAction, "applyAction must not be null");
        Validate.notBlank(errorPrefix, "errorPrefix must not be blank");
        Validate.notNull(errorPresenter, "errorPresenter must not be null");

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
