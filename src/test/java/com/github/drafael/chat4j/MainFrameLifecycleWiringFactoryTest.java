package com.github.drafael.chat4j;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.util.MenuPopupVisibleRunner;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainFrameLifecycleWiringFactoryTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Create builds non-null lifecycle wiring graph")
    void create_whenCalled_buildsLifecycleWiring() {
        var subject = new MainFrameLifecycleWiringFactory();
        var settingsRepo = settingsRepo("mainframe-lifecycle-wiring");

        MainFrameLifecycleWiringFactory.LifecycleWiring wiring = subject.create(
                settingsRepo,
                new MenuPopupVisibleRunner()
        );

        assertThat(wiring.modelMenuDirtyRefreshCoordinator()).isNotNull();
        assertThat(wiring.modelMenuDirtyRefreshTriggerCoordinator()).isNotNull();
        assertThat(wiring.lookAndFeelMenuRefreshCoordinator()).isNotNull();
        assertThat(wiring.windowPlacementCoordinator()).isNotNull();
    }

    @Test
    @DisplayName("Create validates required dependencies")
    void create_whenRequiredDependencyMissing_throwsException() {
        var subject = new MainFrameLifecycleWiringFactory();
        var settingsRepo = settingsRepo("mainframe-lifecycle-wiring-validation");

        assertThatThrownBy(() -> subject.create(null, new MenuPopupVisibleRunner()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsRepo");

        assertThatThrownBy(() -> subject.create(settingsRepo, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("menuPopupVisibleRunner");
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }
}
