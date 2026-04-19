package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainMenuBarEnsureCoordinatorTest {

    @Test
    @DisplayName("Ensure returns existing menu bar without invoking creator or callback")
    void ensure_whenMenuBarAlreadyExists_returnsExistingWithoutInvokingCreatorOrCallback() {
        var subject = new MainMenuBarEnsureCoordinator();
        JMenuBar existing = new JMenuBar();
        var creatorCalls = new AtomicInteger();
        var createdCallbacks = new AtomicInteger();

        JMenuBar result = subject.ensure(
                existing,
                () -> {
                    creatorCalls.incrementAndGet();
                    return createdMenuBar();
                },
                created -> createdCallbacks.incrementAndGet()
        );

        assertThat(result).isSameAs(existing);
        assertThat(creatorCalls.get()).isZero();
        assertThat(createdCallbacks.get()).isZero();
    }

    @Test
    @DisplayName("Ensure creates menu bar and invokes callback when none exists")
    void ensure_whenMenuBarMissing_createsAndInvokesCallback() {
        var subject = new MainMenuBarEnsureCoordinator();
        var creatorCalls = new AtomicInteger();
        var createdCallbacks = new AtomicInteger();

        JMenuBar result = subject.ensure(
                null,
                () -> {
                    creatorCalls.incrementAndGet();
                    return createdMenuBar();
                },
                created -> {
                    createdCallbacks.incrementAndGet();
                    assertThat(created.menuBar()).isSameAs(resultHolder.menuBar);
                }
        );

        assertThat(result).isSameAs(resultHolder.menuBar);
        assertThat(creatorCalls.get()).isEqualTo(1);
        assertThat(createdCallbacks.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Ensure validates required arguments and created menu bar")
    void ensure_whenArgumentInvalid_throwsException() {
        var subject = new MainMenuBarEnsureCoordinator();

        assertThatThrownBy(() -> subject.ensure(new JMenuBar(), null, created -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menuBarCreator must not be null");

        assertThatThrownBy(() -> subject.ensure(new JMenuBar(), this::createdMenuBar, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("onCreated must not be null");

        assertThatThrownBy(() -> subject.ensure(null, () -> null, created -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdMenuBar must not be null");
    }

    private static final ResultHolder resultHolder = new ResultHolder();

    private MainMenuBarBuilder.CreatedMenuBar createdMenuBar() {
        return resultHolder.createdMenuBar();
    }

    private static class ResultHolder {

        private final JMenuBar menuBar = new JMenuBar();

        private MainMenuBarBuilder.CreatedMenuBar createdMenuBar() {
            return new MainMenuBarBuilder.CreatedMenuBar(
                    menuBar,
                    new JMenu("File"),
                    new JMenu("View"),
                    new JMenu("Model"),
                    new JMenu("Font"),
                    new JMenu("Theme"),
                    new JCheckBoxMenuItem("Toggle Preview")
            );
        }
    }
}
