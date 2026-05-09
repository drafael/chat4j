package com.github.drafael.chat4j.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainMenuBarEnsureDispatchCoordinatorTest {

    @Test
    @DisplayName("Ensure returns existing menu bar and skips created apply when already initialized")
    void ensure_whenMenuBarExists_returnsExistingAndSkipsCreatedApply() {
        var createdApplyCalls = new AtomicInteger();
        JMenuBar existing = new JMenuBar();

        var subject = new MainMenuBarEnsureDispatchCoordinator(
                (existingMenuBar, menuBarCreator, onCreated) -> existingMenuBar,
                (createdMenuBar, syncTogglePreviewMenuSelection) -> {
                    createdApplyCalls.incrementAndGet();
                    return new MainMenuBarCreatedApplyCoordinator.ApplyResult(
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
                }
        );

        MainMenuBarEnsureDispatchCoordinator.EnsureResult result = subject.ensure(
                existing,
                () -> {
                    throw new AssertionError("menuBarCreator should not be called");
                },
                () -> {
                }
        );

        assertThat(result.menuBar()).isSameAs(existing);
        assertThat(result.created()).isFalse();
        assertThat(result.createdApplyResult()).isNull();
        assertThat(createdApplyCalls.get()).isZero();
    }

    @Test
    @DisplayName("Ensure creates menu bar and captures created apply result when missing")
    void ensure_whenMenuBarMissing_createsAndCapturesApplyResult() {
        JMenuBar createdSwingMenuBar = new JMenuBar();
        var createdMenuBar = new MainMenuBarBuilder.CreatedMenuBar(
                createdSwingMenuBar,
                new JMenu("File"),
                new JMenu("View"),
                new JMenu("Model"),
                new JMenu("Font"),
                new JMenu("Theme"),
                new JCheckBoxMenuItem("Preview")
        );
        var syncCalls = new AtomicInteger();
        var applySyncObserved = new AtomicBoolean();

        var expectedApplyResult = new MainMenuBarCreatedApplyCoordinator.ApplyResult(
                createdMenuBar.fileMenu(),
                createdMenuBar.viewMenu(),
                createdMenuBar.modelsMenu(),
                createdMenuBar.fontMenu(),
                createdMenuBar.themesMenu(),
                createdMenuBar.togglePreviewMenuItem(),
                true,
                false,
                false
        );

        var subject = new MainMenuBarEnsureDispatchCoordinator(
                (existingMenuBar, menuBarCreator, onCreated) -> {
                    MainMenuBarBuilder.CreatedMenuBar built = menuBarCreator.get();
                    onCreated.accept(built);
                    return built.menuBar();
                },
                (created, syncTogglePreviewMenuSelection) -> {
                    syncTogglePreviewMenuSelection.run();
                    applySyncObserved.set(true);
                    return expectedApplyResult;
                }
        );

        MainMenuBarEnsureDispatchCoordinator.EnsureResult result = subject.ensure(
                null,
                () -> createdMenuBar,
                syncCalls::incrementAndGet
        );

        assertThat(result.menuBar()).isSameAs(createdSwingMenuBar);
        assertThat(result.created()).isTrue();
        assertThat(result.createdApplyResult()).isSameAs(expectedApplyResult);
        assertThat(applySyncObserved.get()).isTrue();
        assertThat(syncCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Ensure validates constructor and arguments")
    void ensure_whenDependencyOrArgumentMissing_throwsException() {
        assertThatThrownBy(() -> new MainMenuBarEnsureDispatchCoordinator(
                (MainMenuBarEnsureDispatchCoordinator.EnsureAction) null,
                (createdMenuBar, syncTogglePreviewMenuSelection) -> null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensureAction");

        var subject = new MainMenuBarEnsureDispatchCoordinator(
                (existingMenuBar, menuBarCreator, onCreated) -> new JMenuBar(),
                (createdMenuBar, syncTogglePreviewMenuSelection) -> null
        );

        assertThatThrownBy(() -> subject.ensure(null, null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menuBarCreator");
    }
}
