package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class ThemeMenuApplyDispatchCoordinator {

    public boolean apply(
            String themeName,
            String className,
            ThemeApplyAction themeApplyAction,
            Runnable markModelsMenuDirty,
            Runnable syncThemeMenuSelection,
            Runnable syncFontMenuSelection,
            ErrorPresenter errorPresenter
    ) {
        Validate.notNull(themeApplyAction, "themeApplyAction must not be null");
        Validate.notNull(markModelsMenuDirty, "markModelsMenuDirty must not be null");
        Validate.notNull(syncThemeMenuSelection, "syncThemeMenuSelection must not be null");
        Validate.notNull(syncFontMenuSelection, "syncFontMenuSelection must not be null");
        Validate.notNull(errorPresenter, "errorPresenter must not be null");

        ThemeMenuApplyCoordinator.ApplyResult applyResult = themeApplyAction.apply(
                themeName,
                className,
                markModelsMenuDirty,
                syncThemeMenuSelection,
                syncFontMenuSelection
        );

        if (applyResult.success()) {
            return true;
        }

        errorPresenter.show("Failed to apply theme: %s".formatted(applyResult.errorMessage()));
        return false;
    }

    @FunctionalInterface
    public interface ThemeApplyAction {
        ThemeMenuApplyCoordinator.ApplyResult apply(
                String themeName,
                String className,
                Runnable markModelsMenuDirty,
                Runnable syncThemeMenuSelection,
                Runnable syncFontMenuSelection
        );
    }

    @FunctionalInterface
    public interface ErrorPresenter {
        void show(String message);
    }
}
