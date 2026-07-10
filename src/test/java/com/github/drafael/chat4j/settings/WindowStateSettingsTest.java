package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WindowStateSettingsTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Save persists window and monitor bounds entries")
    void save_whenCalled_persistsWindowAndMonitorSettings() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-save");
        var subject = new WindowStateSettings(settingsRepo);

        subject.save(new WindowPlacementSnapshot(
                new Rectangle(10, 20, 900, 600),
                new Rectangle(-1440, 0, 1440, 900),
                "screen-2"
        ));

        assertThat(settingsRepo.get("chat4j.ui.window.x")).contains("10");
        assertThat(settingsRepo.get("chat4j.ui.window.y")).contains("20");
        assertThat(settingsRepo.get("chat4j.ui.window.width")).contains("900");
        assertThat(settingsRepo.get("chat4j.ui.window.height")).contains("600");
        assertThat(settingsRepo.get("chat4j.ui.window.screen.x")).contains("-1440");
        assertThat(settingsRepo.get("chat4j.ui.window.screen.y")).contains("0");
        assertThat(settingsRepo.get("chat4j.ui.window.screen.width")).contains("1440");
        assertThat(settingsRepo.get("chat4j.ui.window.screen.height")).contains("900");
        assertThat(settingsRepo.get("chat4j.ui.window.screen.id")).contains("screen-2");
    }

    @Test
    @DisplayName("Save clears stale monitor metadata for window-only snapshots")
    void save_whenSnapshotHasNoScreen_clearsStaleMonitorSettings() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-save-window-only");
        settingsRepo.put("chat4j.ui.window.screen.x", "-1440");
        settingsRepo.put("chat4j.ui.window.screen.y", "0");
        settingsRepo.put("chat4j.ui.window.screen.width", "1440");
        settingsRepo.put("chat4j.ui.window.screen.height", "900");
        settingsRepo.put("chat4j.ui.window.screen.id", "screen-2");
        var subject = new WindowStateSettings(settingsRepo);

        subject.save(new WindowPlacementSnapshot(new Rectangle(10, 20, 900, 600)));

        assertThat(settingsRepo.get("chat4j.ui.window.screen.x")).isEmpty();
        assertThat(settingsRepo.get("chat4j.ui.window.screen.y")).isEmpty();
        assertThat(settingsRepo.get("chat4j.ui.window.screen.width")).isEmpty();
        assertThat(settingsRepo.get("chat4j.ui.window.screen.height")).isEmpty();
        assertThat(settingsRepo.get("chat4j.ui.window.screen.id")).isEmpty();
    }

    @Test
    @DisplayName("Load returns full placement when monitor metadata is stored")
    void load_whenFullPlacementExists_returnsPlacement() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-full");
        settingsRepo.put("chat4j.ui.window.x", "100");
        settingsRepo.put("chat4j.ui.window.y", "80");
        settingsRepo.put("chat4j.ui.window.width", "800");
        settingsRepo.put("chat4j.ui.window.height", "500");
        settingsRepo.put("chat4j.ui.window.screen.x", "0");
        settingsRepo.put("chat4j.ui.window.screen.y", "-900");
        settingsRepo.put("chat4j.ui.window.screen.width", "1600");
        settingsRepo.put("chat4j.ui.window.screen.height", "900");
        settingsRepo.put("chat4j.ui.window.screen.id", "screen-1");

        var subject = new WindowStateSettings(settingsRepo);

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
        settingsRepo.put("chat4j.ui.window.x", "100");
        settingsRepo.put("chat4j.ui.window.y", "80");
        settingsRepo.put("chat4j.ui.window.width", "800");
        settingsRepo.put("chat4j.ui.window.height", "500");

        var subject = new WindowStateSettings(settingsRepo);

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
        settingsRepo.put("chat4j.ui.window.x", "abc");
        settingsRepo.put("chat4j.ui.window.y", "80");
        settingsRepo.put("chat4j.ui.window.width", "800");
        settingsRepo.put("chat4j.ui.window.height", "500");

        var subject = new WindowStateSettings(settingsRepo);

        Optional<WindowPlacementSnapshot> loaded = subject.load();

        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("Load ignores invalid monitor metadata while preserving valid legacy bounds")
    void load_whenMonitorMetadataIsInvalid_returnsWindowOnlyPlacement() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-invalid-monitor");
        settingsRepo.put("chat4j.ui.window.x", "100");
        settingsRepo.put("chat4j.ui.window.y", "80");
        settingsRepo.put("chat4j.ui.window.width", "800");
        settingsRepo.put("chat4j.ui.window.height", "500");
        settingsRepo.put("chat4j.ui.window.screen.x", "nope");

        var subject = new WindowStateSettings(settingsRepo);

        Optional<WindowPlacementSnapshot> loaded = subject.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().windowBounds()).isEqualTo(new Rectangle(100, 80, 800, 500));
        assertThat(loaded.orElseThrow().screenBounds()).isNull();
    }

    @Test
    @DisplayName("Load ignores unusable monitor bounds while preserving valid window bounds")
    void load_whenMonitorBoundsHaveUnusableSize_returnsWindowOnlyPlacement() throws Exception {
        SettingsRepository settingsRepo = settingsRepo("window-state-unusable-monitor");
        settingsRepo.put("chat4j.ui.window.x", "100");
        settingsRepo.put("chat4j.ui.window.y", "80");
        settingsRepo.put("chat4j.ui.window.width", "800");
        settingsRepo.put("chat4j.ui.window.height", "500");
        settingsRepo.put("chat4j.ui.window.screen.x", "0");
        settingsRepo.put("chat4j.ui.window.screen.y", "0");
        settingsRepo.put("chat4j.ui.window.screen.width", "0");
        settingsRepo.put("chat4j.ui.window.screen.height", "900");

        var subject = new WindowStateSettings(settingsRepo);

        Optional<WindowPlacementSnapshot> loaded = subject.load();

        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().screenBounds()).isNull();
    }

    @Test
    @DisplayName("Save failures are best effort")
    void save_whenRepositoryFails_doesNotThrow() {
        var subject = new WindowStateSettings(new ThrowingSettingsRepo());

        assertThatCode(() -> subject.save(new WindowPlacementSnapshot(new Rectangle(10, 20, 900, 600))))
                .doesNotThrowAnyException();
    }

    private SettingsRepository settingsRepo(String name) {
        return new SettingsRepository(tempDir.resolve("%s.properties".formatted(name)));
    }

    private static class ThrowingSettingsRepo extends SettingsRepository {

        private ThrowingSettingsRepo() {
            super(Path.of("unused-window-state-settings.properties"));
        }

        @Override
        public void put(String key, String value) {
            throw new IllegalStateException("forced failure");
        }
    }
}
