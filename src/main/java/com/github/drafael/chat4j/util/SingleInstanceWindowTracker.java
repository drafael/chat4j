package com.github.drafael.chat4j.util;

import java.util.function.Predicate;

public class SingleInstanceWindowTracker<T> {

    private T activeWindow;

    public T get() {
        return activeWindow;
    }

    public void set(T activeWindow) {
        this.activeWindow = activeWindow;
    }

    public void clear() {
        activeWindow = null;
    }

    public boolean hasActive(Predicate<T> isActivePredicate) {
        return activeWindow != null && isActivePredicate.test(activeWindow);
    }
}
