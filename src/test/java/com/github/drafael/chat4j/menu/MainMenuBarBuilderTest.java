package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.event.MenuEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainMenuBarBuilderTest {

    @Test
    @DisplayName("Create builds menu bar and wires file/view/theme/font/model callbacks")
    void create_whenCalled_buildsMenuBarAndWiresCallbacks() {
        var binder = new MenuSelectionListenerBinder();
        var subject = new MainMenuBarBuilder(
                new FileMenuFactory(),
                new ViewMenuFactory(binder),
                new BoundMenuFactory(binder),
                new MenuBarAssemblyFactory(new StaticHelpMenuVisibilityResolver(true), new HelpMenuFactory())
        );

        var calls = new ArrayList<String>();
        var togglePreviewStates = new ArrayList<Boolean>();

        MainMenuBarBuilder.CreatedMenuBar created = subject.create(
                KeyEvent.CTRL_DOWN_MASK,
                "Chat4J",
                () -> calls.add("new-chat"),
                () -> calls.add("before-select"),
                () -> calls.add("view-selected"),
                () -> calls.add("toggle-sidebar"),
                () -> calls.add("toggle-model-dropdown"),
                () -> calls.add("chat-search"),
                selected -> {
                    calls.add("toggle-preview");
                    togglePreviewStates.add(selected);
                },
                () -> calls.add("themes-selected"),
                () -> calls.add("font-selected"),
                () -> calls.add("models-selected"),
                () -> calls.add("about")
        );

        assertThat(created.menuBar().getMenuCount()).isEqualTo(6);
        assertThat(created.fileMenu().getText()).isEqualTo("File");
        assertThat(created.viewMenu().getText()).isEqualTo("View");
        assertThat(created.modelsMenu().getText()).isEqualTo("Model");
        assertThat(created.fontMenu().getText()).isEqualTo("Font");
        assertThat(created.themesMenu().getText()).isEqualTo("Theme");
        assertThat(created.togglePreviewMenuItem().getText()).isEqualTo("Toggle Preview");

        created.fileMenu().getItem(0).doClick();
        created.viewMenu().getMenuListeners()[0].menuSelected(new MenuEvent(created.viewMenu()));
        created.viewMenu().getItem(0).doClick();
        created.viewMenu().getItem(1).doClick();
        created.viewMenu().getItem(2).doClick();
        created.togglePreviewMenuItem().doClick();
        created.togglePreviewMenuItem().doClick();
        created.themesMenu().getMenuListeners()[0].menuSelected(new MenuEvent(created.themesMenu()));
        created.fontMenu().getMenuListeners()[0].menuSelected(new MenuEvent(created.fontMenu()));
        created.modelsMenu().getMenuListeners()[0].menuSelected(new MenuEvent(created.modelsMenu()));
        created.menuBar().getMenu(5).getItem(0).doClick();

        assertThat(calls).containsExactly(
                "new-chat",
                "before-select",
                "view-selected",
                "toggle-sidebar",
                "toggle-model-dropdown",
                "chat-search",
                "toggle-preview",
                "toggle-preview",
                "before-select",
                "themes-selected",
                "before-select",
                "font-selected",
                "before-select",
                "models-selected",
                "about"
        );
        assertThat(togglePreviewStates).containsExactly(true, false);
    }

    @Test
    @DisplayName("Create validates required callbacks")
    void create_whenCallbackMissing_throwsException() {
        var binder = new MenuSelectionListenerBinder();
        var subject = new MainMenuBarBuilder(
                new FileMenuFactory(),
                new ViewMenuFactory(binder),
                new BoundMenuFactory(binder),
                new MenuBarAssemblyFactory(new StaticHelpMenuVisibilityResolver(true), new HelpMenuFactory())
        );

        assertThatThrownBy(() -> subject.create(
                KeyEvent.CTRL_DOWN_MASK,
                "Chat4J",
                null,
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                selected -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                },
                () -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("onNewChat");
    }

    @Test
    @DisplayName("Constructor validates required factories")
    void constructor_whenDependencyMissing_throwsException() {
        var binder = new MenuSelectionListenerBinder();

        assertThatThrownBy(() -> new MainMenuBarBuilder(
                null,
                new ViewMenuFactory(binder),
                new BoundMenuFactory(binder),
                new MenuBarAssemblyFactory(new StaticHelpMenuVisibilityResolver(true), new HelpMenuFactory())
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fileMenuFactory");
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
