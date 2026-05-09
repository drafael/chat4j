package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainMenuBarEnsureApplyFlowCoordinatorTest {

    @Test
    @DisplayName("EnsureAndApply resolves apply state then dispatches it to all state setters")
    void ensureAndApply_whenCalled_resolvesAndAppliesState() {
        var expectedApplyState = new MainMenuBarEnsureResultApplyCoordinator.ApplyState(
                new JMenuBar(),
                new JMenu("File"),
                new JMenu("View"),
                new JMenu("Model"),
                new JMenu("Font"),
                new JMenu("Theme"),
                new JCheckBoxMenuItem("Preview"),
                true,
                false,
                true
        );

        var capturedCurrentState = new AtomicReference<MainMenuBarEnsureStateResolver.CurrentState>();

        var subject = new MainMenuBarEnsureApplyFlowCoordinator(
                (modelMenuBar, menuBarCreator, syncTogglePreviewMenuSelection, currentState) -> {
                    capturedCurrentState.set(currentState);
                    return expectedApplyState;
                },
                (applyState,
                 setModelMenuBar,
                 setFileMenu,
                 setViewMenu,
                 setModelsMenu,
                 setFontMenu,
                 setThemesMenu,
                 setTogglePreviewMenuItem,
                 setModelsMenuDirty,
                 setThemesMenuBuilt,
                 setFontMenuBuilt) -> {
                    assertThat(applyState).isSameAs(expectedApplyState);
                    setModelMenuBar.accept(applyState.modelMenuBar());
                    setFileMenu.accept(applyState.fileMenu());
                    setViewMenu.accept(applyState.viewMenu());
                    setModelsMenu.accept(applyState.modelsMenu());
                    setFontMenu.accept(applyState.fontMenu());
                    setThemesMenu.accept(applyState.themesMenu());
                    setTogglePreviewMenuItem.accept(applyState.togglePreviewMenuItem());
                    setModelsMenuDirty.accept(applyState.modelsMenuDirty());
                    setThemesMenuBuilt.accept(applyState.themesMenuBuilt());
                    setFontMenuBuilt.accept(applyState.fontMenuBuilt());
                }
        );

        var currentState = new MainMenuBarEnsureStateResolver.CurrentState(
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                true,
                false
        );

        var modelMenuBar = new AtomicReference<JMenuBar>();
        var fileMenu = new AtomicReference<JMenu>();
        var viewMenu = new AtomicReference<JMenu>();
        var modelsMenu = new AtomicReference<JMenu>();
        var fontMenu = new AtomicReference<JMenu>();
        var themesMenu = new AtomicReference<JMenu>();
        var togglePreview = new AtomicReference<JCheckBoxMenuItem>();
        var modelsDirty = new AtomicBoolean();
        var themesBuilt = new AtomicBoolean(true);
        var fontBuilt = new AtomicBoolean();

        subject.ensureAndApply(
                new JMenuBar(),
                () -> null,
                () -> {
                },
                currentState,
                modelMenuBar::set,
                fileMenu::set,
                viewMenu::set,
                modelsMenu::set,
                fontMenu::set,
                themesMenu::set,
                togglePreview::set,
                modelsDirty::set,
                themesBuilt::set,
                fontBuilt::set
        );

        assertThat(capturedCurrentState.get()).isSameAs(currentState);
        assertThat(modelMenuBar.get()).isSameAs(expectedApplyState.modelMenuBar());
        assertThat(fileMenu.get()).isSameAs(expectedApplyState.fileMenu());
        assertThat(viewMenu.get()).isSameAs(expectedApplyState.viewMenu());
        assertThat(modelsMenu.get()).isSameAs(expectedApplyState.modelsMenu());
        assertThat(fontMenu.get()).isSameAs(expectedApplyState.fontMenu());
        assertThat(themesMenu.get()).isSameAs(expectedApplyState.themesMenu());
        assertThat(togglePreview.get()).isSameAs(expectedApplyState.togglePreviewMenuItem());
        assertThat(modelsDirty.get()).isTrue();
        assertThat(themesBuilt.get()).isFalse();
        assertThat(fontBuilt.get()).isTrue();
    }

    @Test
    @DisplayName("EnsureAndApply validates required arguments and constructor dependencies")
    void ensureAndApply_whenInvalidInput_throwsException() {
        var subject = new MainMenuBarEnsureApplyFlowCoordinator(
                (modelMenuBar, menuBarCreator, syncTogglePreviewMenuSelection, currentState) ->
                        new MainMenuBarEnsureResultApplyCoordinator.ApplyState(
                                new JMenuBar(), null, null, null, null, null, null, false, false, false
                        ),
                (applyState,
                 setModelMenuBar,
                 setFileMenu,
                 setViewMenu,
                 setModelsMenu,
                 setFontMenu,
                 setThemesMenu,
                 setTogglePreviewMenuItem,
                 setModelsMenuDirty,
                 setThemesMenuBuilt,
                 setFontMenuBuilt) -> {
                }
        );

        assertThatThrownBy(() -> subject.ensureAndApply(
                new JMenuBar(),
                null,
                () -> {
                },
                new MainMenuBarEnsureStateResolver.CurrentState(null, null, null, null, null, null, false, false, false),
                value -> {
                },
                value -> {
                },
                value -> {
                },
                value -> {
                },
                value -> {
                },
                value -> {
                },
                value -> {
                },
                value -> {
                },
                value -> {
                },
                value -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menuBarCreator");

        assertThatThrownBy(() -> new MainMenuBarEnsureApplyFlowCoordinator(
                (MainMenuBarEnsureApplyFlowCoordinator.ResolveAction) null,
                (applyState,
                 setModelMenuBar,
                 setFileMenu,
                 setViewMenu,
                 setModelsMenu,
                 setFontMenu,
                 setThemesMenu,
                 setTogglePreviewMenuItem,
                 setModelsMenuDirty,
                 setThemesMenuBuilt,
                 setFontMenuBuilt) -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resolveAction");
    }
}
