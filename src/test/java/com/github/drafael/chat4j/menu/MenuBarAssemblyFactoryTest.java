package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuBarAssemblyFactoryTest {

    @Test
    @DisplayName("Create assembles menu bar in expected order and appends help menu when visible")
    void create_whenHelpVisible_assemblesMenuBarAndAppendsHelpMenu() {
        var subject = new MenuBarAssemblyFactory(new StaticHelpMenuVisibilityResolver(true), new HelpMenuFactory());
        var onAboutCalls = new AtomicInteger();

        var menuBar = subject.create(
                "Chat4J",
                new JMenu("File"),
                new JMenu("View"),
                new JMenu("Model"),
                new JMenu("Font"),
                new JMenu("Theme"),
                onAboutCalls::incrementAndGet
        );

        assertThat(menuBar.getMenuCount()).isEqualTo(6);
        assertThat(menuBar.getMenu(0).getText()).isEqualTo("File");
        assertThat(menuBar.getMenu(1).getText()).isEqualTo("View");
        assertThat(menuBar.getMenu(2).getText()).isEqualTo("Model");
        assertThat(menuBar.getMenu(3).getText()).isEqualTo("Font");
        assertThat(menuBar.getMenu(4).getText()).isEqualTo("Theme");
        assertThat(menuBar.getMenu(5).getText()).isEqualTo("Help");

        var aboutItem = menuBar.getMenu(5).getItem(0);
        assertThat(aboutItem.getText()).isEqualTo("About Chat4J");
        aboutItem.doClick();
        assertThat(onAboutCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Create omits help menu when visibility resolver returns false")
    void create_whenHelpHidden_omitsHelpMenu() {
        var subject = new MenuBarAssemblyFactory(new StaticHelpMenuVisibilityResolver(false), new HelpMenuFactory());

        var menuBar = subject.create(
                "Chat4J",
                new JMenu("File"),
                new JMenu("View"),
                new JMenu("Model"),
                new JMenu("Font"),
                new JMenu("Theme"),
                () -> {
                }
        );

        assertThat(menuBar.getMenuCount()).isEqualTo(5);
        assertThat(menuBar.getMenu(4).getText()).isEqualTo("Theme");
    }

    @Test
    @DisplayName("Create validates required arguments")
    void create_whenArgumentMissing_throwsException() {
        var subject = new MenuBarAssemblyFactory(new StaticHelpMenuVisibilityResolver(true), new HelpMenuFactory());

        assertThatThrownBy(() -> subject.create(
                "Chat4J",
                null,
                new JMenu("View"),
                new JMenu("Model"),
                new JMenu("Font"),
                new JMenu("Theme"),
                () -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fileMenu must not be null");
    }

    private static class StaticHelpMenuVisibilityResolver extends HelpMenuVisibilityResolver {

        private final boolean showHelp;

        private StaticHelpMenuVisibilityResolver(boolean showHelp) {
            this.showHelp = showHelp;
        }

        @Override
        public boolean shouldShowHelpMenu() {
            return showHelp;
        }
    }
}
