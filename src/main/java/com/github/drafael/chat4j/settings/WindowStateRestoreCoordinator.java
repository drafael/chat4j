package com.github.drafael.chat4j.settings;

import lombok.NonNull;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.Optional;
import java.util.function.Consumer;

public class WindowStateRestoreCoordinator {

    private final StoredBoundsLoader storedBoundsLoader;
    private final VisibleScreenBoundsResolver visibleScreenBoundsResolver;

    public WindowStateRestoreCoordinator(WindowStateSettingsCoordinator windowStateSettingsCoordinator) {
        this(
                windowStateSettingsCoordinator::loadIfVisible,
                () -> GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice()
                        .getDefaultConfiguration()
                        .getBounds()
        );
    }

    WindowStateRestoreCoordinator(
            @NonNull StoredBoundsLoader storedBoundsLoader,
            @NonNull VisibleScreenBoundsResolver visibleScreenBoundsResolver
    ) {
        this.storedBoundsLoader = storedBoundsLoader;
        this.visibleScreenBoundsResolver = visibleScreenBoundsResolver;
    }

    public boolean restore(@NonNull Consumer<Rectangle> applyBounds, @NonNull Runnable applyDefaultWindowState) {

        Rectangle visibleScreenBounds = visibleScreenBoundsResolver.resolveVisibleBounds();
        Optional<Rectangle> bounds = storedBoundsLoader.loadIfVisible(visibleScreenBounds);
        if (bounds.isPresent()) {
            applyBounds.accept(bounds.get());
            return true;
        }

        applyDefaultWindowState.run();
        return false;
    }

    @FunctionalInterface
    interface StoredBoundsLoader {
        Optional<Rectangle> loadIfVisible(Rectangle visibleScreenBounds);
    }

    @FunctionalInterface
    interface VisibleScreenBoundsResolver {
        Rectangle resolveVisibleBounds();
    }
}
