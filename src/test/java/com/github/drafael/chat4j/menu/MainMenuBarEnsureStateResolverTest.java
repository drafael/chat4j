package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainMenuBarEnsureStateResolverTest {

    @Test
    @DisplayName("Resolve delegates to ensure dispatch and result apply actions")
    void resolve_whenCalled_delegatesToEnsureAndApplyActions() {
        JMenuBar existingMenuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenu viewMenu = new JMenu("View");
        JMenu modelsMenu = new JMenu("Model");
        JMenu fontMenu = new JMenu("Font");
        JMenu themesMenu = new JMenu("Theme");
        JCheckBoxMenuItem togglePreview = new JCheckBoxMenuItem("Preview");

        var currentState = new MainMenuBarEnsureStateResolver.CurrentState(
                fileMenu,
                viewMenu,
                modelsMenu,
                fontMenu,
                themesMenu,
                togglePreview,
                true,
                false,
                true
        );

        var capturedEnsureResult = new AtomicReference<MainMenuBarEnsureDispatchCoordinator.EnsureResult>();
        var expectedApplyState = new MainMenuBarEnsureResultApplyCoordinator.ApplyState(
                new JMenuBar(),
                new JMenu("New File"),
                new JMenu("New View"),
                new JMenu("New Model"),
                new JMenu("New Font"),
                new JMenu("New Theme"),
                new JCheckBoxMenuItem("New Preview"),
                false,
                true,
                false
        );

        var subject = new MainMenuBarEnsureStateResolver(
                (existing, menuBarCreator, syncTogglePreviewMenuSelection) -> {
                    assertThat(existing).isSameAs(existingMenuBar);
                    return new MainMenuBarEnsureDispatchCoordinator.EnsureResult(existingMenuBar, null);
                },
                (ensureResult,
                 currentFileMenu,
                 currentViewMenu,
                 currentModelsMenu,
                 currentFontMenu,
                 currentThemesMenu,
                 currentTogglePreviewMenuItem,
                 currentModelsMenuDirty,
                 currentThemesMenuBuilt,
                 currentFontMenuBuilt) -> {
                    capturedEnsureResult.set(ensureResult);
                    assertThat(currentFileMenu).isSameAs(fileMenu);
                    assertThat(currentViewMenu).isSameAs(viewMenu);
                    assertThat(currentModelsMenu).isSameAs(modelsMenu);
                    assertThat(currentFontMenu).isSameAs(fontMenu);
                    assertThat(currentThemesMenu).isSameAs(themesMenu);
                    assertThat(currentTogglePreviewMenuItem).isSameAs(togglePreview);
                    assertThat(currentModelsMenuDirty).isTrue();
                    assertThat(currentThemesMenuBuilt).isFalse();
                    assertThat(currentFontMenuBuilt).isTrue();
                    return expectedApplyState;
                }
        );

        MainMenuBarEnsureResultApplyCoordinator.ApplyState resolved = subject.resolve(
                existingMenuBar,
                () -> {
                    throw new AssertionError("menuBarCreator should not be used in this stub path");
                },
                () -> {
                },
                currentState
        );

        assertThat(capturedEnsureResult.get()).isNotNull();
        assertThat(resolved).isSameAs(expectedApplyState);
    }

    @Test
    @DisplayName("Resolve validates required arguments and dependencies")
    void resolve_whenInvalidInput_throwsException() {
        var subject = new MainMenuBarEnsureStateResolver(
                (existingMenuBar, menuBarCreator, syncTogglePreviewMenuSelection) ->
                        new MainMenuBarEnsureDispatchCoordinator.EnsureResult(new JMenuBar(), null),
                (ensureResult,
                 currentFileMenu,
                 currentViewMenu,
                 currentModelsMenu,
                 currentFontMenu,
                 currentThemesMenu,
                 currentTogglePreviewMenuItem,
                 currentModelsMenuDirty,
                 currentThemesMenuBuilt,
                 currentFontMenuBuilt) ->
                        new MainMenuBarEnsureResultApplyCoordinator.ApplyState(
                                new JMenuBar(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                false,
                                false
                        )
        );

        assertThatThrownBy(() -> subject.resolve(
                null,
                null,
                () -> {
                },
                new MainMenuBarEnsureStateResolver.CurrentState(null, null, null, null, null, null, false, false, false)
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menuBarCreator");

        assertThatThrownBy(() -> new MainMenuBarEnsureStateResolver(
                (MainMenuBarEnsureStateResolver.EnsureDispatchAction) null,
                (ensureResult,
                 currentFileMenu,
                 currentViewMenu,
                 currentModelsMenu,
                 currentFontMenu,
                 currentThemesMenu,
                 currentTogglePreviewMenuItem,
                 currentModelsMenuDirty,
                 currentThemesMenuBuilt,
                 currentFontMenuBuilt) -> null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensureDispatchAction");
    }
}
