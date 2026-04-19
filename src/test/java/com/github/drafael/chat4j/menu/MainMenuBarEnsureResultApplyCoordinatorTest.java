package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainMenuBarEnsureResultApplyCoordinatorTest {

    @Test
    @DisplayName("Apply preserves existing menu refs/flags when ensure result is not newly created")
    void apply_whenNotCreated_preservesExistingState() {
        var subject = new MainMenuBarEnsureResultApplyCoordinator();
        JMenuBar existingMenuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenu viewMenu = new JMenu("View");
        JMenu modelsMenu = new JMenu("Model");
        JMenu fontMenu = new JMenu("Font");
        JMenu themesMenu = new JMenu("Theme");
        JCheckBoxMenuItem togglePreview = new JCheckBoxMenuItem("Preview");

        var ensureResult = new MainMenuBarEnsureDispatchCoordinator.EnsureResult(existingMenuBar, null);

        MainMenuBarEnsureResultApplyCoordinator.ApplyState state = subject.apply(
                ensureResult,
                fileMenu,
                viewMenu,
                modelsMenu,
                fontMenu,
                themesMenu,
                togglePreview,
                false,
                true,
                true
        );

        assertThat(state.modelMenuBar()).isSameAs(existingMenuBar);
        assertThat(state.fileMenu()).isSameAs(fileMenu);
        assertThat(state.viewMenu()).isSameAs(viewMenu);
        assertThat(state.modelsMenu()).isSameAs(modelsMenu);
        assertThat(state.fontMenu()).isSameAs(fontMenu);
        assertThat(state.themesMenu()).isSameAs(themesMenu);
        assertThat(state.togglePreviewMenuItem()).isSameAs(togglePreview);
        assertThat(state.modelsMenuDirty()).isFalse();
        assertThat(state.themesMenuBuilt()).isTrue();
        assertThat(state.fontMenuBuilt()).isTrue();
    }

    @Test
    @DisplayName("Apply maps created apply result when ensure result is newly created")
    void apply_whenCreated_mapsCreatedApplyResult() {
        var subject = new MainMenuBarEnsureResultApplyCoordinator();
        JMenuBar createdMenuBar = new JMenuBar();

        var createdApplyResult = new MainMenuBarCreatedApplyCoordinator.ApplyResult(
                new JMenu("File"),
                new JMenu("View"),
                new JMenu("Model"),
                new JMenu("Font"),
                new JMenu("Theme"),
                new JCheckBoxMenuItem("Preview"),
                true,
                false,
                false
        );

        var ensureResult = new MainMenuBarEnsureDispatchCoordinator.EnsureResult(createdMenuBar, createdApplyResult);

        MainMenuBarEnsureResultApplyCoordinator.ApplyState state = subject.apply(
                ensureResult,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                true,
                true
        );

        assertThat(state.modelMenuBar()).isSameAs(createdMenuBar);
        assertThat(state.fileMenu()).isSameAs(createdApplyResult.fileMenu());
        assertThat(state.viewMenu()).isSameAs(createdApplyResult.viewMenu());
        assertThat(state.modelsMenu()).isSameAs(createdApplyResult.modelsMenu());
        assertThat(state.fontMenu()).isSameAs(createdApplyResult.fontMenu());
        assertThat(state.themesMenu()).isSameAs(createdApplyResult.themesMenu());
        assertThat(state.togglePreviewMenuItem()).isSameAs(createdApplyResult.togglePreviewMenuItem());
        assertThat(state.modelsMenuDirty()).isTrue();
        assertThat(state.themesMenuBuilt()).isFalse();
        assertThat(state.fontMenuBuilt()).isFalse();
    }

    @Test
    @DisplayName("Apply validates required input")
    void apply_whenInvalidInput_throwsException() {
        var subject = new MainMenuBarEnsureResultApplyCoordinator();

        assertThatThrownBy(() -> subject.apply(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensureResult must not be null");

        assertThatThrownBy(() -> new MainMenuBarEnsureResultApplyCoordinator.ApplyState(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modelMenuBar must not be null");
    }
}
