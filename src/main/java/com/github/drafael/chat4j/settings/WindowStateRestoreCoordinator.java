package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

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
            StoredBoundsLoader storedBoundsLoader,
            VisibleScreenBoundsResolver visibleScreenBoundsResolver
    ) {
        this.storedBoundsLoader = Validate.notNull(storedBoundsLoader, "storedBoundsLoader must not be null");
        this.visibleScreenBoundsResolver = Validate.notNull(
                visibleScreenBoundsResolver,
                "visibleScreenBoundsResolver must not be null"
        );
    }

    public boolean restore(Consumer<Rectangle> applyBounds, Runnable applyDefaultWindowState) {
        Validate.notNull(applyBounds, "applyBounds must not be null");
        Validate.notNull(applyDefaultWindowState, "applyDefaultWindowState must not be null");

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
