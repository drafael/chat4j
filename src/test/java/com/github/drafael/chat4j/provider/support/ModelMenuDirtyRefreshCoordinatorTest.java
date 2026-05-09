package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.util.MenuPopupVisibleRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelMenuDirtyRefreshCoordinatorTest {

    @Test
    @DisplayName("Refresh marks menu dirty and delegates popup-visible refresh")
    void refresh_whenCalled_marksDirtyAndDelegatesPopupRefresh() {
        var runner = new RecordingMenuPopupVisibleRunner();
        var subject = new ModelMenuDirtyRefreshCoordinator(runner);
        var calls = new ArrayList<String>();
        JMenu modelsMenu = new JMenu("Model");

        subject.refresh(modelsMenu, () -> calls.add("mark-dirty"), () -> calls.add("ensure-ready"));

        assertThat(calls).containsExactly("mark-dirty", "ensure-ready");
        assertThat(runner.calls).containsExactly("runner:Model");
    }

    @Test
    @DisplayName("Refresh validates required callbacks")
    void refresh_whenCallbackMissing_throwsException() {
        var subject = new ModelMenuDirtyRefreshCoordinator(new RecordingMenuPopupVisibleRunner());

        assertThatThrownBy(() -> subject.refresh(new JMenu("Model"), null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("markModelsMenuDirty");

        assertThatThrownBy(() -> subject.refresh(new JMenu("Model"), () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensureModelsMenuReady");
    }

    @Test
    @DisplayName("Constructor validates menu popup visible runner")
    void constructor_whenRunnerMissing_throwsException() {
        assertThatThrownBy(() -> new ModelMenuDirtyRefreshCoordinator(null))
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
