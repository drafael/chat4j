package com.github.drafael.chat4j.settings;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

record ScreenSnapshot(
        Rectangle bounds,
        String id,
        boolean primary
) {

    ScreenSnapshot {
        bounds = new Rectangle(Objects.requireNonNull(bounds, "bounds"));
        id = StringUtils.trimToNull(id);
    }

    long intersectionArea(Rectangle rectangle) {
        Rectangle intersection = bounds.intersection(rectangle);
        return intersection.isEmpty() ? 0L : (long) intersection.width * intersection.height;
    }

    boolean contains(Point point) {
        return bounds.contains(point);
    }

    boolean sameSize(Rectangle otherBounds) {
        return otherBounds != null && bounds.width == otherBounds.width && bounds.height == otherBounds.height;
    }

    @Override
    public Rectangle bounds() {
        return new Rectangle(bounds);
    }
}
