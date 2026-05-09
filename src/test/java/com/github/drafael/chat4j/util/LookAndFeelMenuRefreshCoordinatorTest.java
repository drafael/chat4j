package com.github.drafael.chat4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LookAndFeelMenuRefreshCoordinatorTest {

    @Test
    @DisplayName("Refresh clears icon cache, marks models dirty, and triggers popup-visible menu refreshes")
    void refresh_whenCalled_runsExpectedRefreshFlowInOrder() {
        var runner = new RecordingMenuPopupVisibleRunner();
        var subject = new LookAndFeelMenuRefreshCoordinator(runner);
        var calls = new ArrayList<String>();
        JMenu modelsMenu = new JMenu("Model");
        JMenu fontMenu = new JMenu("Font");

        subject.refresh(
                () -> calls.add("clear-cache"),
                () -> calls.add("mark-dirty"),
                modelsMenu,
                () -> calls.add("ensure-models"),
                fontMenu,
                () -> calls.add("ensure-font")
        );

        assertThat(calls).containsExactly(
                "clear-cache",
                "mark-dirty",
                "ensure-models",
                "ensure-font"
        );
        assertThat(runner.calls).containsExactly("runner:Model", "runner:Font");
    }

    @Test
    @DisplayName("Refresh validates required callbacks")
    void refresh_whenCallbackMissing_throwsException() {
        var subject = new LookAndFeelMenuRefreshCoordinator(new RecordingMenuPopupVisibleRunner());

        assertThatThrownBy(() -> subject.refresh(
                null,
                () -> {
                },
                new JMenu("Model"),
                () -> {
                },
                new JMenu("Font"),
                () -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clearProviderIconCache");

        assertThatThrownBy(() -> subject.refresh(
                () -> {
                },
                () -> {
                },
                new JMenu("Model"),
                null,
                new JMenu("Font"),
                () -> {
                }
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensureModelsMenuReady");
    }

    @Test
    @DisplayName("Constructor validates menu popup visible runner")
    void constructor_whenRunnerMissing_throwsException() {
        assertThatThrownBy(() -> new LookAndFeelMenuRefreshCoordinator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menuPopupVisibleRunner");
    }

    private static class RecordingMenuPopupVisibleRunner extends MenuPopupVisibleRunner {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void runIfVisible(JMenu menu, Runnable action) {
            calls.add("runner:%s".formatted(menu != null ? menu.getText() : "null"));
            action.run();
        }
    }
}
