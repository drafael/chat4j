package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class WindowStateSettingsCoordinatorTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Save persists window and monitor bounds entries")
    void save_whenCalled_persistsWindowAndMonitorSettings() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-save");
        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        subject.save(new WindowPlacementSnapshot(
                new Rectangle(10, 20, 900, 600),
                new Rectangle(-1440, 0, 1440, 900),
                "screen-2"
        ));

        assertThat(settingsRepo.get(SettingsKeys.WINDOW_X)).contains("10");
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_Y)).contains("20");
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_WIDTH)).contains("900");
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_HEIGHT)).contains("600");
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_X)).contains("-1440");
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_Y)).contains("0");
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_WIDTH)).contains("1440");
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_HEIGHT)).contains("900");
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_ID)).contains("screen-2");
    }

    @Test
    @DisplayName("Save clears stale monitor metadata for window-only snapshots")
    void save_whenSnapshotHasNoScreen_clearsStaleMonitorSettings() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-save-window-only");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_X, "-1440");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_Y, "0");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_WIDTH, "1440");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_HEIGHT, "900");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_ID, "screen-2");
        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        subject.save(new WindowPlacementSnapshot(new Rectangle(10, 20, 900, 600)));

        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_X)).isEmpty();
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_Y)).isEmpty();
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_WIDTH)).isEmpty();
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_HEIGHT)).isEmpty();
        assertThat(settingsRepo.get(SettingsKeys.WINDOW_SCREEN_ID)).isEmpty();
    }

    @Test
    @DisplayName("Load returns full placement when monitor metadata is stored")
    void load_whenFullPlacementExists_returnsPlacement() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-full");
        settingsRepo.put(SettingsKeys.WINDOW_X, "100");
        settingsRepo.put(SettingsKeys.WINDOW_Y, "80");
        settingsRepo.put(SettingsKeys.WINDOW_WIDTH, "800");
        settingsRepo.put(SettingsKeys.WINDOW_HEIGHT, "500");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_X, "0");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_Y, "-900");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_WIDTH, "1600");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_HEIGHT, "900");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_ID, "screen-1");

        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        Optional<WindowPlacementSnapshot> loaded = subject.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().windowBounds()).isEqualTo(new Rectangle(100, 80, 800, 500));
        assertThat(loaded.orElseThrow().screenBounds()).isEqualTo(new Rectangle(0, -900, 1600, 900));
        assertThat(loaded.orElseThrow().screenId()).isEqualTo("screen-1");
    }

    @Test
    @DisplayName("Load supports legacy window-only settings")
    void load_whenOnlyWindowBoundsExist_returnsLegacyPlacement() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-legacy");
        settingsRepo.put(SettingsKeys.WINDOW_X, "100");
        settingsRepo.put(SettingsKeys.WINDOW_Y, "80");
        settingsRepo.put(SettingsKeys.WINDOW_WIDTH, "800");
        settingsRepo.put(SettingsKeys.WINDOW_HEIGHT, "500");

        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        Optional<WindowPlacementSnapshot> loaded = subject.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().windowBounds()).isEqualTo(new Rectangle(100, 80, 800, 500));
        assertThat(loaded.orElseThrow().screenBounds()).isNull();
        assertThat(loaded.orElseThrow().screenId()).isNull();
    }

    @Test
    @DisplayName("Load returns empty when stored window values are invalid")
    void load_whenStoredWindowValuesAreInvalid_returnsEmpty() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-invalid");
        settingsRepo.put(SettingsKeys.WINDOW_X, "abc");
        settingsRepo.put(SettingsKeys.WINDOW_Y, "80");
        settingsRepo.put(SettingsKeys.WINDOW_WIDTH, "800");
        settingsRepo.put(SettingsKeys.WINDOW_HEIGHT, "500");

        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        Optional<WindowPlacementSnapshot> loaded = subject.load();

        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("Load ignores invalid monitor metadata while preserving valid legacy bounds")
    void load_whenMonitorMetadataIsInvalid_returnsWindowOnlyPlacement() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-invalid-monitor");
        settingsRepo.put(SettingsKeys.WINDOW_X, "100");
        settingsRepo.put(SettingsKeys.WINDOW_Y, "80");
        settingsRepo.put(SettingsKeys.WINDOW_WIDTH, "800");
        settingsRepo.put(SettingsKeys.WINDOW_HEIGHT, "500");
        settingsRepo.put(SettingsKeys.WINDOW_SCREEN_X, "nope");

        var subject = new WindowStateSettingsCoordinator(settingsRepo);

        Optional<WindowPlacementSnapshot> loaded = subject.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().windowBounds()).isEqualTo(new Rectangle(100, 80, 800, 500));
        assertThat(loaded.orElseThrow().screenBounds()).isNull();
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }
}
