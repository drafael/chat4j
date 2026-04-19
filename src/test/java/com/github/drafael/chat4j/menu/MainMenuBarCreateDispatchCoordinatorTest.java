package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainMenuBarCreateDispatchCoordinatorTest {

    @Test
    @DisplayName("Create resolves shortcut mask and delegates to create action")
    void create_whenCalled_resolvesMaskAndDelegates() {
        var capturedMask = new AtomicInteger();
        var capturedTitle = new AtomicReference<String>();

        var expected = new MainMenuBarBuilder.CreatedMenuBar(
                new JMenuBar(),
                new JMenu("File"),
                new JMenu("View"),
                new JMenu("Model"),
                new JMenu("Font"),
                new JMenu("Theme"),
                new JCheckBoxMenuItem("Preview")
        );

        var subject = new MainMenuBarCreateDispatchCoordinator(
                () -> 123,
                (menuShortcutMask,
                 appTitle,
                 onNewChat,
                 beforeMenuSelected,
                 onViewMenuSelected,
                 onToggleSidebar,
                 onToggleModelDropdown,
                 onChatSearch,
                 onTogglePreview,
                 onThemesMenuSelected,
                 onFontMenuSelected,
                 onModelsMenuSelected,
                 onAbout) -> {
                    capturedMask.set(menuShortcutMask);
                    capturedTitle.set(appTitle);
                    return expected;
                }
        );

        MainMenuBarBuilder.CreatedMenuBar created = subject.create(
                "Chat4J",
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
        );

        assertThat(capturedMask.get()).isEqualTo(123);
        assertThat(capturedTitle.get()).isEqualTo("Chat4J");
        assertThat(created).isSameAs(expected);
    }

    @Test
    @DisplayName("Create validates constructor dependencies")
    void create_whenDependencyMissing_throwsException() {
        assertThatThrownBy(() -> new MainMenuBarCreateDispatchCoordinator(
                null,
                (menuShortcutMask,
                 appTitle,
                 onNewChat,
                 beforeMenuSelected,
                 onViewMenuSelected,
                 onToggleSidebar,
                 onToggleModelDropdown,
                 onChatSearch,
                 onTogglePreview,
                 onThemesMenuSelected,
                 onFontMenuSelected,
                 onModelsMenuSelected,
                 onAbout) -> null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("shortcutMaskSupplier must not be null");

        assertThatThrownBy(() -> new MainMenuBarCreateDispatchCoordinator(
                () -> 1,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createAction must not be null");
    }
}
