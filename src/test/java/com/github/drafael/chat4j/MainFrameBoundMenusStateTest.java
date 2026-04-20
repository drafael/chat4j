package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameBoundMenusStateTest {

    @Test
    @DisplayName("Default state starts with all bound menus unset")
    void defaults_whenConstructed_startWithNoBoundMenus() {
        var subject = new MainFrameBoundMenusState();

        assertThat(subject.modelsMenu()).isNull();
        assertThat(subject.themesMenu()).isNull();
        assertThat(subject.fontMenu()).isNull();
    }

    @Test
    @DisplayName("Setters update tracked bound menu references")
    void setters_whenCalled_updateBoundMenuReferences() {
        var subject = new MainFrameBoundMenusState();
        var modelsMenu = new JMenu("Models");
        var themesMenu = new JMenu("Themes");
        var fontMenu = new JMenu("Font");

        subject.setModelsMenu(modelsMenu);
        subject.setThemesMenu(themesMenu);
        subject.setFontMenu(fontMenu);

        assertThat(subject.modelsMenu()).isSameAs(modelsMenu);
        assertThat(subject.themesMenu()).isSameAs(themesMenu);
        assertThat(subject.fontMenu()).isSameAs(fontMenu);
    }
}
