package com.github.drafael.chat4j.settings;

import java.awt.Rectangle;
import java.util.Objects;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public record WindowPlacementSnapshot(
        Rectangle windowBounds,
        Rectangle screenBounds,
        String screenId
) {

    public WindowPlacementSnapshot {
        windowBounds = new Rectangle(Objects.requireNonNull(windowBounds, "windowBounds"));
        screenBounds = screenBounds == null ? null : new Rectangle(screenBounds);
        screenId = StringUtils.trimToNull(screenId);
    }

    public WindowPlacementSnapshot(@NonNull Rectangle windowBounds) {
        this(windowBounds, null, null);
    }

    public boolean hasScreenBounds() {
        return screenBounds != null;
    }

    @Override
    public Rectangle windowBounds() {
        return new Rectangle(windowBounds);
    }

    @Override
    public Rectangle screenBounds() {
        return screenBounds == null ? null : new Rectangle(screenBounds);
    }
}
