package com.github.drafael.chat4j.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThemeMenuApplyDispatchCoordinatorTest {

    @Test
    @DisplayName("Apply returns true and does not present error when theme apply succeeds")
    void apply_whenSuccess_returnsTrueWithoutError() {
        var subject = new ThemeMenuApplyDispatchCoordinator();
        var calls = new ArrayList<String>();
        var messages = new ArrayList<String>();

        boolean applied = subject.apply(
                "GitHub",
                "theme.Class",
                (themeName, className, markModelsMenuDirty, syncThemeMenuSelection, syncFontMenuSelection) -> {
                    calls.add("apply:%s:%s".formatted(themeName, className));
                    markModelsMenuDirty.run();
                    syncThemeMenuSelection.run();
                    syncFontMenuSelection.run();
                    return ThemeMenuApplyCoordinator.ApplyResult.successResult();
                },
                () -> calls.add("mark-dirty"),
                () -> calls.add("sync-theme"),
                () -> calls.add("sync-font"),
                messages::add
        );

        assertThat(applied).isTrue();
        assertThat(calls).containsExactly("apply:GitHub:theme.Class", "mark-dirty", "sync-theme", "sync-font");
        assertThat(messages).isEmpty();
    }

    @Test
    @DisplayName("Apply returns false and presents formatted error when theme apply fails")
    void apply_whenFailure_returnsFalseAndPresentsError() {
        var subject = new ThemeMenuApplyDispatchCoordinator();
        var messages = new ArrayList<String>();

        boolean applied = subject.apply(
                "GitHub",
                "theme.Class",
                (themeName, className, markModelsMenuDirty, syncThemeMenuSelection, syncFontMenuSelection) ->
                        ThemeMenuApplyCoordinator.ApplyResult.failureResult("boom"),
                () -> {
                },
                () -> {
                },
                () -> {
                },
                messages::add
        );

        assertThat(applied).isFalse();
        assertThat(messages).containsExactly("Failed to apply theme: boom");
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new ThemeMenuApplyDispatchCoordinator();

        assertThatThrownBy(() -> subject.apply(
                "GitHub",
                "theme.Class",
                null,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                message -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("themeApplyAction must not be null");

        assertThatThrownBy(() -> subject.apply(
                "GitHub",
                "theme.Class",
                (themeName, className, markModelsMenuDirty, syncThemeMenuSelection, syncFontMenuSelection) ->
                        ThemeMenuApplyCoordinator.ApplyResult.successResult(),
                () -> {
                },
                () -> {
                },
                () -> {
                },
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorPresenter must not be null");
    }
}
