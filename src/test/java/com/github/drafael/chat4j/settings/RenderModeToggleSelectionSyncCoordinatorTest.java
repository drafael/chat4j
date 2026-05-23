package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.chat.RenderMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderModeToggleSelectionSyncCoordinatorTest {

    @Test
    @DisplayName("Sync updates toggle selection and flips syncing flag around update")
    void sync_whenMenuItemPresent_updatesSelectionAndFlipsSyncFlag() {
        var subject = new RenderModeToggleSelectionSyncCoordinator();
        JCheckBoxMenuItem toggle = new JCheckBoxMenuItem("Toggle Preview");
        var syncStates = new ArrayList<Boolean>();

        boolean synced = subject.sync(toggle, RenderMode.PREVIEW, syncStates::add);

        assertThat(synced).isTrue();
        assertThat(toggle.isSelected()).isTrue();
        assertThat(syncStates).containsExactly(true, false);
    }

    @Test
    @DisplayName("Sync returns false without changing sync flag when menu item is absent")
    void sync_whenMenuItemMissing_returnsFalseWithoutSyncStateChanges() {
        var subject = new RenderModeToggleSelectionSyncCoordinator();
        var syncStates = new ArrayList<Boolean>();

        boolean synced = subject.sync(null, RenderMode.MARKDOWN, syncStates::add);

        assertThat(synced).isFalse();
        assertThat(syncStates).isEmpty();
    }

    @Test
    @DisplayName("Sync always resets syncing flag even when menu selection update throws")
    void sync_whenSelectionThrows_resetsSyncFlagInFinally() {
        var subject = new RenderModeToggleSelectionSyncCoordinator();
        var syncStates = new ArrayList<Boolean>();
        var broken = new ThrowingCheckBoxMenuItem();
        broken.throwOnSet = true;

        assertThatThrownBy(() -> subject.sync(broken, RenderMode.PREVIEW, syncStates::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
        assertThat(syncStates).containsExactly(true, false);
    }

    @Test
    @DisplayName("Sync validates required arguments")
    void sync_whenArgumentMissing_throwsException() {
        var subject = new RenderModeToggleSelectionSyncCoordinator();

        assertThatThrownBy(() -> subject.sync(new JCheckBoxMenuItem(), null, value -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currentRenderMode");

        assertThatThrownBy(() -> subject.sync(new JCheckBoxMenuItem(), RenderMode.PREVIEW, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("setSyncingPreviewMenuSelection");
    }

    private static class ThrowingCheckBoxMenuItem extends JCheckBoxMenuItem {
        private boolean throwOnSet;

        @Override
        public void setSelected(boolean b) {
            if (throwOnSet) {
                throw new IllegalStateException("boom");
            }
            super.setSelected(b);
        }
    }
}
