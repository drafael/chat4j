package com.github.drafael.chat4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuBar;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameTopMenusStateTest {

    @Test
    @DisplayName("Default state starts without menu bar and top menus")
    void defaults_whenConstructed_startWithNoTopMenus() {
        var subject = new MainFrameTopMenusState();

        assertThat(subject.modelMenuBar()).isNull();
        assertThat(subject.fileMenu()).isNull();
        assertThat(subject.viewMenu()).isNull();
    }

    @Test
    @DisplayName("Setters update tracked top menu references")
    void setters_whenCalled_updateTopMenuReferences() {
        var subject = new MainFrameTopMenusState();
        var menuBar = new JMenuBar();
        var fileMenu = new JMenu("File");
        var viewMenu = new JMenu("View");

        subject.setModelMenuBar(menuBar);
        subject.setFileMenu(fileMenu);
        subject.setViewMenu(viewMenu);

        assertThat(subject.modelMenuBar()).isSameAs(menuBar);
        assertThat(subject.fileMenu()).isSameAs(fileMenu);
        assertThat(subject.viewMenu()).isSameAs(viewMenu);
    }
}
