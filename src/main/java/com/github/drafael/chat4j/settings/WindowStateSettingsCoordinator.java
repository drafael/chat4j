package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.storage.SettingsRepo;
import org.apache.commons.lang3.Validate;

import java.awt.Rectangle;
import java.util.Optional;

public class WindowStateSettingsCoordinator {

    private static final String WINDOW_X_KEY = "window.x";
    private static final String WINDOW_Y_KEY = "window.y";
    private static final String WINDOW_WIDTH_KEY = "window.width";
    private static final String WINDOW_HEIGHT_KEY = "window.height";

    private final SettingsRepo settingsRepo;

    public WindowStateSettingsCoordinator(SettingsRepo settingsRepo) {
        this.settingsRepo = Validate.notNull(settingsRepo, "settingsRepo must not be null");
    }

    public void save(Rectangle bounds) {
        Validate.notNull(bounds, "bounds must not be null");

        try {
            settingsRepo.put(WINDOW_X_KEY, String.valueOf(bounds.x));
            settingsRepo.put(WINDOW_Y_KEY, String.valueOf(bounds.y));
            settingsRepo.put(WINDOW_WIDTH_KEY, String.valueOf(bounds.width));
            settingsRepo.put(WINDOW_HEIGHT_KEY, String.valueOf(bounds.height));
        } catch (Exception ignored) {
            // Window bounds persistence is best-effort.
        }
    }

    public Optional<Rectangle> loadIfVisible(Rectangle visibleScreenBounds) {
        Validate.notNull(visibleScreenBounds, "visibleScreenBounds must not be null");

        try {
            int x = Integer.parseInt(settingsRepo.get(WINDOW_X_KEY, "0"));
            int y = Integer.parseInt(settingsRepo.get(WINDOW_Y_KEY, "0"));
            int width = Integer.parseInt(settingsRepo.get(WINDOW_WIDTH_KEY, "1000"));
            int height = Integer.parseInt(settingsRepo.get(WINDOW_HEIGHT_KEY, "700"));
            Rectangle bounds = new Rectangle(x, y, width, height);
            return visibleScreenBounds.intersects(bounds) ? Optional.of(bounds) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
