package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class ThemeMenuApplyDispatchCoordinator {

    public boolean apply(
            String themeName,
            String className,
            @NonNull ThemeApplyAction themeApplyAction,
            @NonNull Runnable markModelsMenuDirty,
            @NonNull Runnable syncThemeMenuSelection,
            @NonNull Runnable syncFontMenuSelection,
            @NonNull ErrorPresenter errorPresenter
    ) {

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
