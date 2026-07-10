package com.github.drafael.chat4j.settings;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;

public class WindowPlacementCoordinator {

    public static final int DEFAULT_WINDOW_WIDTH = 1000;
    public static final int DEFAULT_WINDOW_HEIGHT = 700;

    private static final int DISPLAY_CHECK_INTERVAL_MS = 2_000;
    private static final int PRIMARY_REPAIR_OFFSET = 50;

    private final WindowStateSettings settingsCoordinator;
    private final ScreenResolver screenResolver;

    public WindowPlacementCoordinator(@NonNull WindowStateSettings settingsCoordinator) {
        this(settingsCoordinator, WindowPlacementCoordinator::currentAwtScreens);
    }

    WindowPlacementCoordinator(
            @NonNull WindowStateSettings settingsCoordinator,
            @NonNull ScreenResolver screenResolver
    ) {
        this.settingsCoordinator = settingsCoordinator;
        this.screenResolver = screenResolver;
    }

    public void save(@NonNull Window window) {
        Rectangle windowBounds = window.getBounds();
        Optional<ScreenSnapshot> selectedScreen = selectLargestIntersectionScreen(windowBounds, screenResolver.currentScreens());
        WindowPlacementSnapshot snapshot = selectedScreen
                .map(screen -> new WindowPlacementSnapshot(windowBounds, screen.bounds(), screen.id()))
                .orElseGet(() -> new WindowPlacementSnapshot(windowBounds));
        settingsCoordinator.save(snapshot);
    }

    public boolean restore(@NonNull Window window, int defaultWidth, int defaultHeight) {
        Optional<WindowPlacementSnapshot> savedPlacement = settingsCoordinator.load();
        Rectangle restoredBounds = resolveInitialBounds(savedPlacement, defaultWidth, defaultHeight, screenResolver.currentScreens());
        window.setBounds(restoredBounds);
        return savedPlacement.isPresent();
    }

    public void registerDisplayChangeHandler(@NonNull Window window) {
        AtomicReference<String> lastSignature = new AtomicReference<>(screenLayoutSignature(screenResolver.currentScreens()));
        AtomicBoolean wasDisplayable = new AtomicBoolean(window.isDisplayable());
        Timer timer = new Timer(DISPLAY_CHECK_INTERVAL_MS, event -> {
            if (window.isDisplayable()) {
                wasDisplayable.set(true);
            }
            if (wasDisplayable.get() && !window.isDisplayable()) {
                ((Timer) event.getSource()).stop();
                return;
            }

            List<ScreenSnapshot> screens = screenResolver.currentScreens();
            String signature = screenLayoutSignature(screens);
            if (StringUtils.equals(signature, lastSignature.get())) {
                return;
            }

            lastSignature.set(signature);
            SwingUtilities.invokeLater(() -> repairPlacementAfterDisplayChange(window, screens));
        });
        timer.setRepeats(true);
        timer.start();
        window.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                timer.stop();
            }
        });
    }

    Rectangle resolveInitialBounds(
            Optional<WindowPlacementSnapshot> savedPlacement,
            int defaultWidth,
            int defaultHeight,
            List<ScreenSnapshot> screens
    ) {
        if (savedPlacement.isEmpty()) {
            return defaultBounds(defaultWidth, defaultHeight, screens);
        }

        WindowPlacementSnapshot placement = savedPlacement.get();
        return selectRestoreTargetScreen(placement, screens)
                .map(screen -> restoredBoundsForScreen(placement, screen))
                .orElseGet(() -> defaultBounds(defaultWidth, defaultHeight, screens));
    }

    Optional<ScreenSnapshot> selectLargestIntersectionScreen(Rectangle windowBounds, List<ScreenSnapshot> screens) {
        Optional<ScreenSnapshot> intersectingScreen = screens.stream()
                .max(Comparator.comparingLong(screen -> screen.intersectionArea(windowBounds)))
                .filter(screen -> screen.intersectionArea(windowBounds) > 0L);
        return intersectingScreen.or(() -> primaryScreen(screens));
    }

    Rectangle repairBoundsAfterDisplayChange(Rectangle currentBounds, List<ScreenSnapshot> screens) {
        if (screens.isEmpty()) {
            return new Rectangle(currentBounds);
        }

        Point topLeft = currentBounds.getLocation();
        boolean topLeftVisible = screens.stream().anyMatch(screen -> screen.contains(topLeft));
        if (topLeftVisible) {
            return new Rectangle(currentBounds);
        }

        ScreenSnapshot primary = primaryScreen(screens).orElse(screens.getFirst());
        Rectangle primaryBounds = primary.bounds();
        int width = Math.min(currentBounds.width, primaryBounds.width);
        int height = Math.min(currentBounds.height, primaryBounds.height);
        Rectangle repairedBounds = new Rectangle(
                primaryBounds.x + PRIMARY_REPAIR_OFFSET,
                primaryBounds.y + PRIMARY_REPAIR_OFFSET,
                width,
                height
        );
        return clampWithinScreen(repairedBounds, primary);
    }

    private void repairPlacementAfterDisplayChange(Window window, List<ScreenSnapshot> screens) {
        Rectangle repairedBounds = repairBoundsAfterDisplayChange(window.getBounds(), screens);
        if (!repairedBounds.equals(window.getBounds())) {
            window.setBounds(repairedBounds);
        }
    }

    private Optional<ScreenSnapshot> selectRestoreTargetScreen(WindowPlacementSnapshot placement, List<ScreenSnapshot> screens) {
        if (screens.isEmpty()) {
            return Optional.empty();
        }

        Rectangle savedScreenBounds = placement.screenBounds();
        if (savedScreenBounds != null) {
            Optional<ScreenSnapshot> exactBoundsMatch = screens.stream()
                    .filter(screen -> screen.bounds().equals(savedScreenBounds))
                    .findFirst();
            if (exactBoundsMatch.isPresent()) {
                return exactBoundsMatch;
            }

            List<ScreenSnapshot> sameSizeScreens = screens.stream()
                    .filter(screen -> screen.sameSize(savedScreenBounds))
                    .toList();
            Optional<ScreenSnapshot> matchingIdScreen = sameSizeScreens.stream()
                    .filter(screen -> StringUtils.equals(screen.id(), placement.screenId()))
                    .findFirst();
            if (matchingIdScreen.isPresent()) {
                return matchingIdScreen;
            }
            if (!sameSizeScreens.isEmpty()) {
                return Optional.of(sameSizeScreens.getFirst());
            }
        }

        return screens.stream()
                .max(Comparator.comparingLong(screen -> screen.intersectionArea(placement.windowBounds())))
                .filter(screen -> screen.intersectionArea(placement.windowBounds()) > 0L);
    }

    private Rectangle restoredBoundsForScreen(WindowPlacementSnapshot placement, ScreenSnapshot targetScreen) {
        Rectangle savedWindow = placement.windowBounds();
        Rectangle targetBounds = targetScreen.bounds();
        Rectangle candidate = new Rectangle(savedWindow);
        Rectangle savedScreen = placement.screenBounds();
        if (savedScreen != null) {
            candidate.x = targetBounds.x + savedWindow.x - savedScreen.x;
            candidate.y = targetBounds.y + savedWindow.y - savedScreen.y;
        }
        return clampWithinScreen(candidate, targetScreen);
    }

    private Rectangle defaultBounds(int defaultWidth, int defaultHeight, List<ScreenSnapshot> screens) {
        if (screens.isEmpty()) {
            return new Rectangle(0, 0, Math.max(1, defaultWidth), Math.max(1, defaultHeight));
        }

        ScreenSnapshot primary = primaryScreen(screens).orElse(screens.getFirst());
        Rectangle primaryBounds = primary.bounds();
        int width = Math.min(Math.max(1, defaultWidth), primaryBounds.width);
        int height = Math.min(Math.max(1, defaultHeight), primaryBounds.height);
        int x = primaryBounds.x + (primaryBounds.width - width) / 2;
        int y = primaryBounds.y + (primaryBounds.height - height) / 2;
        return new Rectangle(x, y, width, height);
    }

    private Rectangle clampWithinScreen(Rectangle bounds, ScreenSnapshot screen) {
        Rectangle screenBounds = screen.bounds();
        int width = Math.min(Math.max(1, bounds.width), screenBounds.width);
        int height = Math.min(Math.max(1, bounds.height), screenBounds.height);
        int x = clamp(bounds.x, screenBounds.x, screenBounds.x + screenBounds.width - width);
        int y = clamp(bounds.y, screenBounds.y, screenBounds.y + screenBounds.height - height);
        return new Rectangle(x, y, width, height);
    }

    private int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private Optional<ScreenSnapshot> primaryScreen(List<ScreenSnapshot> screens) {
        return screens.stream()
                .filter(ScreenSnapshot::primary)
                .findFirst()
                .or(() -> screens.stream().findFirst());
    }

    private String screenLayoutSignature(List<ScreenSnapshot> screens) {
        return screens.stream()
                .map(screen -> "%d,%d,%d,%d,%s,%s".formatted(
                        screen.bounds().x,
                        screen.bounds().y,
                        screen.bounds().width,
                        screen.bounds().height,
                        screen.id(),
                        screen.primary()
                ))
                .sorted()
                .collect(joining("|"));
    }

    private static List<ScreenSnapshot> currentAwtScreens() {
        try {
            GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice primaryDevice = graphicsEnvironment.getDefaultScreenDevice();
            return Arrays.stream(graphicsEnvironment.getScreenDevices())
                    .map(device -> new ScreenSnapshot(
                            device.getDefaultConfiguration().getBounds(),
                            device.getIDstring(),
                            device.equals(primaryDevice)
                    ))
                    .toList();
        } catch (HeadlessException | SecurityException e) {
            return emptyList();
        }
    }

    @FunctionalInterface
    interface ScreenResolver {
        List<ScreenSnapshot> currentScreens();
    }
}
