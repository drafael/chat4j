package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Rectangle;
import java.util.Optional;
import lombok.NonNull;

public class WindowStateSettingsCoordinator {

    private static final String WINDOW_X_KEY = SettingsKeys.WINDOW_X;
    private static final String WINDOW_Y_KEY = SettingsKeys.WINDOW_Y;
    private static final String WINDOW_WIDTH_KEY = SettingsKeys.WINDOW_WIDTH;
    private static final String WINDOW_HEIGHT_KEY = SettingsKeys.WINDOW_HEIGHT;
    private static final String WINDOW_SCREEN_X_KEY = SettingsKeys.WINDOW_SCREEN_X;
    private static final String WINDOW_SCREEN_Y_KEY = SettingsKeys.WINDOW_SCREEN_Y;
    private static final String WINDOW_SCREEN_WIDTH_KEY = SettingsKeys.WINDOW_SCREEN_WIDTH;
    private static final String WINDOW_SCREEN_HEIGHT_KEY = SettingsKeys.WINDOW_SCREEN_HEIGHT;
    private static final String WINDOW_SCREEN_ID_KEY = SettingsKeys.WINDOW_SCREEN_ID;

    private final SettingsRepository settingsRepo;

    public WindowStateSettingsCoordinator(@NonNull SettingsRepository settingsRepo) {
        this.settingsRepo = settingsRepo;
    }

    public void save(@NonNull WindowPlacementSnapshot snapshot) {

        try {
            Rectangle windowBounds = snapshot.windowBounds();
            settingsRepo.put(WINDOW_X_KEY, String.valueOf(windowBounds.x));
            settingsRepo.put(WINDOW_Y_KEY, String.valueOf(windowBounds.y));
            settingsRepo.put(WINDOW_WIDTH_KEY, String.valueOf(windowBounds.width));
            settingsRepo.put(WINDOW_HEIGHT_KEY, String.valueOf(windowBounds.height));

            Rectangle screenBounds = snapshot.screenBounds();
            if (screenBounds != null) {
                settingsRepo.put(WINDOW_SCREEN_X_KEY, String.valueOf(screenBounds.x));
                settingsRepo.put(WINDOW_SCREEN_Y_KEY, String.valueOf(screenBounds.y));
                settingsRepo.put(WINDOW_SCREEN_WIDTH_KEY, String.valueOf(screenBounds.width));
                settingsRepo.put(WINDOW_SCREEN_HEIGHT_KEY, String.valueOf(screenBounds.height));
                settingsRepo.put(WINDOW_SCREEN_ID_KEY, snapshot.screenId());
            } else {
                clearScreenMetadata();
            }
        } catch (Exception ignored) {
            // Window placement persistence is best-effort.
        }
    }

    public void save(@NonNull Rectangle bounds) {
        save(new WindowPlacementSnapshot(bounds));
    }

    private void clearScreenMetadata() {
        removeSetting(WINDOW_SCREEN_X_KEY);
        removeSetting(WINDOW_SCREEN_Y_KEY);
        removeSetting(WINDOW_SCREEN_WIDTH_KEY);
        removeSetting(WINDOW_SCREEN_HEIGHT_KEY);
        removeSetting(WINDOW_SCREEN_ID_KEY);
    }

    private void removeSetting(String key) {
        try {
            settingsRepo.remove(key);
        } catch (Exception ignored) {
            // Window placement persistence is best-effort.
        }
    }

    public Optional<WindowPlacementSnapshot> load() {

        try {
            Optional<Rectangle> windowBounds = loadWindowBounds();
            if (windowBounds.isEmpty()) {
                return Optional.empty();
            }

            Rectangle screenBounds = loadScreenBounds().orElse(null);
            String screenId = settingsRepo.get(WINDOW_SCREEN_ID_KEY).orElse(null);
            return Optional.of(new WindowPlacementSnapshot(windowBounds.get(), screenBounds, screenId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Rectangle> loadWindowBounds() {
        Optional<Integer> x = readInt(WINDOW_X_KEY);
        Optional<Integer> y = readInt(WINDOW_Y_KEY);
        Optional<Integer> width = readInt(WINDOW_WIDTH_KEY);
        Optional<Integer> height = readInt(WINDOW_HEIGHT_KEY);
        if (x.isEmpty() || y.isEmpty() || width.isEmpty() || height.isEmpty()) {
            return Optional.empty();
        }

        Rectangle bounds = new Rectangle(x.get(), y.get(), width.get(), height.get());
        return hasUsableSize(bounds) ? Optional.of(bounds) : Optional.empty();
    }

    private Optional<Rectangle> loadScreenBounds() {
        Optional<Integer> x = readInt(WINDOW_SCREEN_X_KEY);
        Optional<Integer> y = readInt(WINDOW_SCREEN_Y_KEY);
        Optional<Integer> width = readInt(WINDOW_SCREEN_WIDTH_KEY);
        Optional<Integer> height = readInt(WINDOW_SCREEN_HEIGHT_KEY);
        if (x.isEmpty() || y.isEmpty() || width.isEmpty() || height.isEmpty()) {
            return Optional.empty();
        }

        Rectangle bounds = new Rectangle(x.get(), y.get(), width.get(), height.get());
        return hasUsableSize(bounds) ? Optional.of(bounds) : Optional.empty();
    }

    private Optional<Integer> readInt(String key) {
        try {
            return settingsRepo.get(key).map(Integer::parseInt);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private boolean hasUsableSize(Rectangle bounds) {
        return bounds.width > 0 && bounds.height > 0;
    }
}
