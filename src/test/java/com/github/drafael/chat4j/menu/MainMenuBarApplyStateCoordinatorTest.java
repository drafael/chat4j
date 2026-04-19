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

class MainMenuBarApplyStateCoordinatorTest {

    @Test
    @DisplayName("Apply dispatches every state value to corresponding setter")
    void apply_whenCalled_dispatchesAllStateValues() {
        var subject = new MainMenuBarApplyStateCoordinator();
        var applyState = new MainMenuBarEnsureResultApplyCoordinator.ApplyState(
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

        subject.apply(
                applyState,
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

        assertThat(modelMenuBar.get()).isSameAs(applyState.modelMenuBar());
        assertThat(fileMenu.get()).isSameAs(applyState.fileMenu());
        assertThat(viewMenu.get()).isSameAs(applyState.viewMenu());
        assertThat(modelsMenu.get()).isSameAs(applyState.modelsMenu());
        assertThat(fontMenu.get()).isSameAs(applyState.fontMenu());
        assertThat(themesMenu.get()).isSameAs(applyState.themesMenu());
        assertThat(togglePreview.get()).isSameAs(applyState.togglePreviewMenuItem());
        assertThat(modelsDirty.get()).isTrue();
        assertThat(themesBuilt.get()).isFalse();
        assertThat(fontBuilt.get()).isTrue();
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenArgumentMissing_throwsException() {
        var subject = new MainMenuBarApplyStateCoordinator();

        assertThatThrownBy(() -> subject.apply(
                null,
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
                .hasMessageContaining("applyState must not be null");

        var applyState = new MainMenuBarEnsureResultApplyCoordinator.ApplyState(
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
        );

        assertThatThrownBy(() -> subject.apply(
                applyState,
                null,
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
                .hasMessageContaining("setModelMenuBar must not be null");
    }
}
