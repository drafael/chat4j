package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Rectangle;
import java.awt.Window;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WindowPlacementCoordinatorTest {

    @TempDir
    private Path tempDir;

    @Test
    @DisplayName("Default restore centers the window on the primary screen")
    void resolveInitialBounds_whenNoPlacementExists_centersDefaultSizeOnPrimaryScreen() {
        WindowPlacementCoordinator subject = subject(screens(
                screen(new Rectangle(-1440, 0, 1440, 900), "external", false),
                screen(new Rectangle(0, 0, 1920, 1080), "built-in", true)
        ));

        Rectangle resolved = subject.resolveInitialBounds(Optional.empty(), 1000, 700, screens(
                screen(new Rectangle(-1440, 0, 1440, 900), "external", false),
                screen(new Rectangle(0, 0, 1920, 1080), "built-in", true)
        ));

        assertThat(resolved).isEqualTo(new Rectangle(460, 190, 1000, 700));
    }

    @Test
    @DisplayName("Save persists the screen with the largest window intersection")
    void save_whenWindowSpansScreens_persistsScreenWithLargestIntersection() {
        var settingsCoordinator = new WindowStateSettings(
                new SettingsRepository(tempDir.resolve("save-settings.properties"))
        );
        WindowPlacementCoordinator subject = new WindowPlacementCoordinator(settingsCoordinator, () -> screens(
                screen(new Rectangle(0, 0, 1000, 800), "left", true),
                screen(new Rectangle(1000, 0, 1000, 800), "right", false)
        ));
        Window window = mock(Window.class);
        when(window.getBounds()).thenReturn(new Rectangle(900, 100, 400, 300));

        subject.save(window);

        Optional<WindowPlacementSnapshot> saved = settingsCoordinator.load();
        assertThat(saved).isPresent();
        assertThat(saved.orElseThrow().screenId()).isEqualTo("right");
        assertThat(saved.orElseThrow().screenBounds()).isEqualTo(new Rectangle(1000, 0, 1000, 800));
    }

    @Test
    @DisplayName("Restore uses exact saved monitor bounds before other matching strategies")
    void resolveInitialBounds_whenSavedScreenBoundsMatch_restoresRelativeToMatchingScreen() {
        WindowPlacementCoordinator subject = subject(screens());
        WindowPlacementSnapshot placement = new WindowPlacementSnapshot(
                new Rectangle(2020, 100, 800, 600),
                new Rectangle(1920, 0, 1920, 1080),
                "external"
        );

        Rectangle resolved = subject.resolveInitialBounds(Optional.of(placement), 1000, 700, screens(
                screen(new Rectangle(0, 0, 1920, 1080), "built-in", true),
                screen(new Rectangle(1920, 0, 1920, 1080), "renamed-external", false)
        ));

        assertThat(resolved).isEqualTo(new Rectangle(2020, 100, 800, 600));
    }

    @Test
    @DisplayName("Restore uses screen ID only as a same-size tie breaker")
    void resolveInitialBounds_whenSameSizeScreensExist_prefersMatchingScreenId() {
        WindowPlacementCoordinator subject = subject(screens());
        WindowPlacementSnapshot placement = new WindowPlacementSnapshot(
                new Rectangle(200, 200, 800, 600),
                new Rectangle(100, 100, 1440, 900),
                "external"
        );

        Rectangle resolved = subject.resolveInitialBounds(Optional.of(placement), 1000, 700, screens(
                screen(new Rectangle(0, 0, 1440, 900), "built-in", true),
                screen(new Rectangle(-1440, 0, 1440, 900), "external", false)
        ));

        assertThat(resolved).isEqualTo(new Rectangle(-1340, 100, 800, 600));
    }

    @Test
    @DisplayName("Legacy window-only restore falls back to an intersecting screen")
    void resolveInitialBounds_whenLegacyBoundsIntersectScreen_clampsBoundsOnIntersectingScreen() {
        WindowPlacementCoordinator subject = subject(screens());
        WindowPlacementSnapshot placement = new WindowPlacementSnapshot(new Rectangle(-1200, 100, 800, 600));

        Rectangle resolved = subject.resolveInitialBounds(Optional.of(placement), 1000, 700, screens(
                screen(new Rectangle(0, 0, 1920, 1080), "built-in", true),
                screen(new Rectangle(-1440, 0, 1440, 900), "external", false)
        ));

        assertThat(resolved).isEqualTo(new Rectangle(-1200, 100, 800, 600));
    }

    @Test
    @DisplayName("Restore centers on primary when no saved monitor or window bounds match current screens")
    void resolveInitialBounds_whenNoSuitableScreenExists_centersOnPrimaryScreen() {
        WindowPlacementCoordinator subject = subject(screens());
        WindowPlacementSnapshot placement = new WindowPlacementSnapshot(
                new Rectangle(5100, 5100, 800, 600),
                new Rectangle(5000, 5000, 1600, 900),
                "missing"
        );

        Rectangle resolved = subject.resolveInitialBounds(Optional.of(placement), 1000, 700, screens(
                screen(new Rectangle(0, 0, 1920, 1080), "built-in", true)
        ));

        assertThat(resolved).isEqualTo(new Rectangle(460, 190, 1000, 700));
    }

    @Test
    @DisplayName("Restore supports negative monitor coordinates")
    void resolveInitialBounds_whenSavedScreenWasLeftOfPrimary_restoresUsingNegativeCoordinates() {
        WindowPlacementCoordinator subject = subject(screens());
        WindowPlacementSnapshot placement = new WindowPlacementSnapshot(
                new Rectangle(-1300, 100, 700, 500),
                new Rectangle(-1440, 0, 1440, 900),
                "left"
        );

        Rectangle resolved = subject.resolveInitialBounds(Optional.of(placement), 1000, 700, screens(
                screen(new Rectangle(-1440, 0, 1440, 900), "left", false),
                screen(new Rectangle(0, 0, 1920, 1080), "primary", true)
        ));

        assertThat(resolved).isEqualTo(new Rectangle(-1300, 100, 700, 500));
    }

    @Test
    @DisplayName("Restore reduces oversized saved windows to fit the target screen")
    void resolveInitialBounds_whenSavedWindowIsLargerThanTarget_reducesSizeAndClamps() {
        WindowPlacementCoordinator subject = subject(screens());
        WindowPlacementSnapshot placement = new WindowPlacementSnapshot(
                new Rectangle(100, 100, 2000, 1200),
                new Rectangle(0, 0, 1920, 1080),
                "primary"
        );

        Rectangle resolved = subject.resolveInitialBounds(Optional.of(placement), 1000, 700, screens(
                screen(new Rectangle(0, 0, 1280, 720), "smaller", true)
        ));

        assertThat(resolved).isEqualTo(new Rectangle(0, 0, 1280, 720));
    }

    @Test
    @DisplayName("Runtime repair moves an offscreen top-left corner to the primary screen")
    void repairBoundsAfterDisplayChange_whenTopLeftIsOffscreen_movesWindowToPrimaryScreen() {
        WindowPlacementCoordinator subject = subject(screens());

        Rectangle repaired = subject.repairBoundsAfterDisplayChange(new Rectangle(3000, 200, 900, 600), screens(
                screen(new Rectangle(0, 0, 1920, 1080), "primary", true)
        ));

        assertThat(repaired).isEqualTo(new Rectangle(50, 50, 900, 600));
    }

    @Test
    @DisplayName("Runtime repair keeps visible top-left bounds unchanged")
    void repairBoundsAfterDisplayChange_whenTopLeftIsVisible_keepsCurrentBounds() {
        WindowPlacementCoordinator subject = subject(screens());
        Rectangle currentBounds = new Rectangle(-100, 50, 900, 600);

        Rectangle repaired = subject.repairBoundsAfterDisplayChange(currentBounds, screens(
                screen(new Rectangle(-1440, 0, 1440, 900), "external", false),
                screen(new Rectangle(0, 0, 1920, 1080), "primary", true)
        ));

        assertThat(repaired).isEqualTo(currentBounds);
    }

    private WindowPlacementCoordinator subject(List<ScreenSnapshot> screens) {
        var settingsCoordinator = new WindowStateSettings(
                new SettingsRepository(tempDir.resolve("settings.properties"))
        );
        return new WindowPlacementCoordinator(settingsCoordinator, () -> screens);
    }

    private List<ScreenSnapshot> screens(ScreenSnapshot... screens) {
        return List.of(screens);
    }

    private ScreenSnapshot screen(Rectangle bounds, String id, boolean primary) {
        return new ScreenSnapshot(bounds, id, primary);
    }
}
